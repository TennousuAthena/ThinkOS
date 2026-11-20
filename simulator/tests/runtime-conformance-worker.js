import { Runtime } from '/web/runtime.ts';

const corpus = await fetch('/test-data.json').then(response => response.json());
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const check = (condition, message) => { if (!condition) throw Error(message); };
async function until(condition, label) { for (let n = 0; n < 150; n++) { if (condition()) return; await pause(10); } throw Error('Timed out: ' + label); }
const encode = value => btoa(String.fromCharCode(...new TextEncoder().encode(value)));
function pkg(id, code, digest = 'one', extra = {}) {
  return { id, digest, factory: true, manifest: { id, api: 10, version: '1', main: 'main.lua', permissions: [], ...extra }, files: { 'main.lua': encode(code) } };
}
function fixture(overrides = {}, rpcOverride) {
  const messages = [], requests = [];
  const session = { ...corpus, plugins: [], settings: { fields: { level: { type: 'number', default: 1, min: 0, max: 10 } } }, environment: { ...corpus.environment }, ...overrides };
  const runtime = new Runtime(session, value => messages.push(value), async (name, args) => {
    requests.push({ name, args });
    if (rpcOverride) { const result = rpcOverride(name, args); if (result !== undefined) return result; }
    if (name === 'runtime') return { ok: true };
    if (name === 'library') return { items: [], directory: '/fixture', scanned: 0, truncated: false };
    if (name === 'dns') return { entries: [] };
    if (name === 'media') return [{ status: 'stopped', queue: [], position: 0, duration: 0 }];
    throw Error('Unexpected RPC ' + name);
  });
  return { runtime, messages, requests };
}
const results = [];
async function run(name, body) {
  let f;
  try { f = fixture(); await body(f); results.push({ name, ok: true }); }
  catch (error) { results.push({ name, ok: false, error: String(error), logs: f?.messages.filter(message => message.type === 'log').slice(-5) }); }
  finally { f?.runtime.shutdown(); }
}

await run('All 15 built-in plugins start and receive every declared page event', async f => {
  f.runtime.shutdown(); const builtins = fixture({ plugins: corpus.plugins, settings: corpus.settings }); f.runtime = builtins.runtime; f.messages = builtins.messages;
  await f.runtime.loadAll(corpus.plugins);
  await until(() => f.runtime.candidates.size === 0, 'built-in startup');
  check(f.runtime.active.size === 15, `expected 15 plugins, got ${f.runtime.active.size}`);
  check(!f.messages.some(message => message.type === 'rejected'), 'built-in rejected');
  const before = f.messages.length;
  for (const p of corpus.plugins) for (const page of p.manifest.pages ?? []) {
    f.runtime.event(p.id, 'page.enter', page.id, page.variants?.[0]?.id ?? page.variants?.[0] ?? 'main', p.id === 'settings' ? { scope: 'system' } : null);
    f.runtime.event(p.id, 'page.leave', page.id);
  }
  await pause(100);
  check(!f.messages.slice(before).some(message => message.type === 'log' && message.level === 'error'), 'page event error');
});

await run('Rejected hot reload retains old state, storage, permissions and dispatch', async f => {
  const old = pkg('fixture', "tos.on('start',function()tos.state.set{value=1};tos.store.set('stable',7)end);tos.on('tap',function()tos.state.set{clicked=true}end)");
  await f.runtime.loadAll([old]); await until(() => f.runtime.active.has('fixture'), 'old commit');
  const original = f.runtime.active.get('fixture');
  await f.runtime.load(pkg('fixture', "tos.on('start',function()tos.state.set{value=9};tos.store.set('stable',99);error('reject candidate')end)", 'bad', { permissions: ['network'] }));
  await until(() => !f.runtime.candidates.size, 'candidate rejection');
  check(f.runtime.active.get('fixture') === original, 'old instance was replaced');
  check(original.state.value === 1 && original.store.stable === 7 && !original.grants.has('network'), 'failed candidate changed old instance');
  f.runtime.input('fixture', 'tap', [], true); check(original.state.clicked === true, 'old event handler stopped');
});

await run('Removing an asynchronously starting candidate prevents its later commit', async f => {
  await f.runtime.loadAll([pkg('removed', "tos.on('start',function()tos.timer.sleep(.05);tos.state.set{late=true}end)")]);
  check(f.runtime.candidates.has('removed'), 'fixture did not suspend');
  await f.runtime.loadAll([]); await pause(100);
  check(!f.runtime.active.has('removed') && !f.runtime.candidates.has('removed'), 'removed candidate committed after removal');
});

await run('Replacing a generation drops its pending asynchronous resume', async f => {
  f.runtime.shutdown(); let resolve;
  const x = fixture({ environment: { ...corpus.environment, networkMode: 'live' } }, name => name === 'http' ? new Promise(accept => { resolve = accept; }) : undefined);
  f.runtime = x.runtime; f.messages = x.messages;
  await f.runtime.loadAll([pkg('async', "tos.on('tap',function()local value=tos.http.get('https://fixture.invalid');tos.state.set{late=value.body}end)", 'old', { permissions: ['network'] })]);
  await until(() => f.runtime.active.has('async'), 'old commit'); f.runtime.input('async', 'tap', [], true);
  await until(() => resolve, 'pending HTTP');
  await f.runtime.load(pkg('async', "tos.on('start',function()tos.state.set{generation='new'}end)", 'new', { permissions: ['network'] }));
  await until(() => f.runtime.active.get('async')?.pkg.digest === 'new', 'new commit');
  resolve({ status: 200, headers: {}, body: 'stale' }); await pause(40);
  check(f.runtime.active.get('async').state.late === undefined, 'old response mutated new state');
});

await run('Timer cancellation, self cancellation and replacement stop old callbacks', async f => {
  const code = "tos.on('start',function()local id;id=tos.timer.every(.05,function()tos.state.set{ticks=(tos.state.get('ticks')or 0)+1};tos.timer.cancel(id)end);local cancelled=tos.timer.after(.05,function()tos.state.set{bad=true}end);tos.timer.cancel(cancelled)end)";
  await f.runtime.loadAll([pkg('timer', code)]); await until(() => f.runtime.active.has('timer'), 'timer commit'); await pause(170);
  const old = f.runtime.active.get('timer'); check(old.state.ticks === 1 && old.state.bad === undefined, 'timer cancellation failed');
  await f.runtime.load(pkg('timer', "tos.on('start',function()tos.state.set{new=true}end)", 'new'));
  await until(() => f.runtime.active.get('timer')?.pkg.digest === 'new', 'timer replacement'); await pause(40);
  check(old.timers.size === 0 && old.live === false && old.state.ticks === 1, 'old generation timer survived');
});

await run('State retains int64, integral float, negative zero and container types', async f => {
  await f.runtime.loadAll([pkg('numeric', "tos.on('start',function()tos.state.set(tos.json.encode{wide=9007199254740993,whole=1.0,z=-0.0,empty=tos.json.decode('[]'),object={}});local s=tos.state.get('');assert(math.type(s.whole)=='float' and 1/s.z==-math.huge and s.wide==9007199254740993);tos.state.set{roundtrip=true}end)")]);
  await until(() => !f.runtime.candidates.size, 'numeric startup'); const state = f.runtime.active.get('numeric')?.state;
  check(state?.roundtrip === true && state.wide === 9007199254740993n && state.whole instanceof Number && Object.is(state.z.valueOf(), -0) && Array.isArray(state.empty), 'numeric state changed type');
});

await run('Reserved state keys and media capability access are enforced', async f => {
  await f.runtime.loadAll([pkg('reserved', "tos.on('start',function()local c=pcall(function()tos.state.set{config={secret='x'}}end);local u=pcall(function()tos.state.set{uses={fake=1}}end);local m=pcall(function()tos.state.get('media')end);tos.state.set{configDenied=not c,usesDenied=not u,mediaDenied=not m}end)")]);
  await until(() => f.runtime.active.has('reserved'), 'reserved commit'); const state = f.runtime.active.get('reserved').state;
  check(state.configDenied && state.usesDenied && state.mediaDenied, 'reserved key or media access accepted');
});

await run('System settings changes preserve each plugin immutable config', async f => {
  const consumer = pkg('configured', "tos.on('tap',function()assert(tos.config.answer==7);tos.state.set{configured=true}end)", 'one', { settings: { answer: { type: 'number', default: 7 } } });
  const admin = pkg('admin', "tos.on('tap',function()local ok=tos.settings.set('system','level',2);assert(ok)end)", 'one', { permissions: ['system.settings'] });
  await f.runtime.loadAll([consumer, admin]); await until(() => f.runtime.active.size === 2, 'settings plugins');
  f.runtime.input('admin', 'tap', [], true); await pause(30); f.runtime.input('configured', 'tap', [], true);
  check(f.runtime.settings.level === 2 && f.runtime.active.get('configured').state.configured === true, 'system config broadcast lost plugin config');
});

await run('Media events update permission-checked tos.state.get media snapshot', async f => {
  await f.runtime.loadAll([pkg('media', "tos.on('media',function(m)local current=tos.state.get('media');tos.state.set{status=current and current.status or 'missing'}end)", 'one', { permissions: ['media'] })]);
  await until(() => f.runtime.active.has('media'), 'media consumer');
  f.runtime.event(null, 'media', { status: 'playing', owner: 'media', queue: [], position: 400, duration: 2000 });
  check(f.runtime.active.get('media').state.status === 'playing', 'media state not published');
});

await run('Events and privileged methods obey API and actual grants', async f => {
  await f.runtime.loadAll([pkg('plain', "tos.on('presence',function()tos.state.set{leaked=true}end);tos.on('tap',function()local ok=pcall(tos.settings.list);tos.state.set{denied=not ok}end)")]);
  await until(() => f.runtime.active.has('plain'), 'plain commit'); f.runtime.event(null, 'presence', true); f.runtime.input('plain', 'tap', [], true);
  const state = f.runtime.active.get('plain').state; check(state.leaked === undefined && state.denied === true, 'permission barrier failed');
  const untrusted = pkg('untrusted', 'tos.settings.list()', 'one', { permissions: ['system.settings'] }); untrusted.factory = false;
  await f.runtime.load(untrusted); await until(() => !f.runtime.candidates.has('untrusted'), 'untrusted rejection'); check(!f.runtime.active.has('untrusted'), 'untrusted system permission was granted');
});

await run('Shutdown dispatches stop with synchronous state, store and log before release', async f => {
  const code = `local timer
    tos.on('start',function() timer=tos.timer.every(60,function()end);tos.state.set{value=7} end)
    tos.on('stop',function()
      tos.timer.cancel(timer);tos.state.set{stopped=true};tos.store.set('saved',tos.state.get('value'))
      tos.log.info('stop saved')
    end)`;
  await f.runtime.loadAll([pkg('cleanup', code)]); await until(() => f.runtime.active.has('cleanup'), 'cleanup commit');
  const instance = f.runtime.active.get('cleanup'), before = f.messages.length;
  f.runtime.shutdown();
  const messages = f.messages.slice(before), save = messages.findIndex(message => message.type === 'store'), release = messages.findIndex(message => message.type === 'release');
  check(instance.store.saved === 7 && instance.state.stopped === true, 'stop cleanup did not run');
  check(save >= 0 && save < release && messages[save].digest === 'one' && messages[save].value.saved === 7, 'stop storage was not flushed before release');
  check(messages.some(message => message.type === 'log' && message.message === 'stop saved'), 'stop log missing');
  check(!messages.some(message => message.type === 'state') && instance.timers.size === 0, 'stop published stale state or retained timers');
});

await run('Hot replacement flushes stop storage with new digest and blocks old side effects', async f => {
  f.runtime.shutdown(); let committedDigest, persisted;
  const x = fixture({ environment: { ...corpus.environment, networkMode: 'live' } }, (name, args) => {
    if (name === 'runtime' && args.action === 'commit') { committedDigest = args.digest; return { ok: true }; }
    if (name === 'http') throw Error('stop must not issue HTTP');
  }); f.runtime = x.runtime; f.messages = x.messages; f.requests = x.requests;
  const originalEmit = f.runtime.emit;
  f.runtime.emit = message => {
    if (message.type === 'store') { check(message.digest === committedDigest, 'gateway would reject stale storage digest'); persisted = structuredClone(message.value); }
    originalEmit(message);
  };
  const old = pkg('swap', `tos.on('start',function()tos.store.set('generation','old')end)
    tos.on('stop',function()
      tos.store.set('savedGeneration',tos.store.get('generation'));tos.state.set{stopped=true}
      local navigation=pcall(function()tos.page.focus('main')end)
      local asynchronous=pcall(function()tos.http.get('https://fixture.invalid')end)
      tos.store.set('blocked',{navigation=not navigation,asynchronous=not asynchronous})
    end)`, 'old', { permissions: ['network'], pages: [{ id: 'main', view: 'view.json' }] });
  old.files['view.json'] = encode('{}');
  await f.runtime.loadAll([old]); await until(() => f.runtime.active.has('swap'), 'old cleanup commit');
  const previous = f.runtime.active.get('swap'), before = f.messages.length;
  await f.runtime.load(pkg('swap', "tos.on('start',function()tos.store.set('generation','new');tos.state.set{generation='new'}end)", 'new'));
  await until(() => f.runtime.active.get('swap')?.pkg.digest === 'new', 'replacement cleanup commit');
  const current = f.runtime.active.get('swap'), messages = f.messages.slice(before);
  check(previous.state.stopped === true && current.state.stopped === undefined, 'old state leaked into replacement');
  check(current.store.savedGeneration === 'new' && persisted.savedGeneration === 'new', 'replacement did not persist stop writes');
  check(persisted.blocked.navigation && persisted.blocked.asynchronous, 'stop could navigate or create an await');
  check(messages.filter(message => message.type === 'store').every(message => message.digest === 'new'), 'old digest save escaped');
  check(!messages.some(message => message.type === 'navigate') && !f.requests.some(request => request.name === 'http'), 'old side effect reached host');
  check(messages.filter(message => message.type === 'state').every(message => message.plugins.swap?.state.stopped === undefined), 'old state was published');
});

await run('Candidate timers wait for async startup and commit, and cancellation survives deferral', async f => {
  f.runtime.shutdown(); let releaseCommit;
  const x = fixture({}, (name, args) => name === 'runtime' && args.action === 'commit' ? new Promise(resolve => { releaseCommit = resolve; }) : undefined);
  f.runtime = x.runtime; f.messages = x.messages;
  const code = `tos.on('start',function()
    local repeated;repeated=tos.timer.every(.05,function()tos.state.set{ticks=(tos.state.get('ticks')or 0)+1};tos.timer.cancel(repeated)end)
    tos.timer.after(.05,function()tos.state.set{once=true}end)
    local cancelled=tos.timer.after(.05,function()tos.state.set{cancelled=true}end)
    tos.timer.sleep(.12);assert(tos.state.get('ticks')==nil and tos.state.get('once')==nil)
    tos.timer.cancel(cancelled);tos.state.set{started=true}
  end)`;
  await f.runtime.loadAll([pkg('deferred', code)]); await until(() => releaseCommit, 'deferred commit request'); await pause(100);
  const candidate = f.runtime.candidates.get('deferred');
  check(candidate?.state.started === true && candidate.state.ticks === undefined && candidate.state.once === undefined, 'timer fired before commit');
  releaseCommit({ ok: true }); await until(() => f.runtime.active.has('deferred'), 'deferred committed');
  await until(() => candidate.state.once === true && candidate.state.ticks === 1, 'committed timer callbacks'); await pause(100);
  check(candidate.state.ticks === 1 && candidate.state.cancelled === undefined, 'deferred timer cancellation failed');
});

await run('Removing a deferred candidate cancels its timers before commit acknowledgment', async f => {
  f.runtime.shutdown(); let releaseCommit;
  const x = fixture({}, (name, args) => name === 'runtime' && args.action === 'commit' ? new Promise(resolve => { releaseCommit = resolve; }) : undefined);
  f.runtime = x.runtime; f.messages = x.messages;
  await f.runtime.loadAll([pkg('cancelled', "tos.on('start',function()tos.timer.after(.05,function()tos.state.set{late=true}end)end)")]);
  await until(() => releaseCommit, 'cancelled commit request'); const candidate = f.runtime.candidates.get('cancelled'); await pause(80);
  await f.runtime.loadAll([]); releaseCommit({ ok: true }); await pause(100);
  check(candidate.state.late === undefined && candidate.timers.size === 0 && !candidate.live && !f.runtime.active.has('cancelled'), 'cancelled candidate timer or commit survived');
});

await run('Hour cycle and zone changes broadcast locale; environment replacement removes fixtures', async f => {
  await f.runtime.loadAll([pkg('locale', `tos.on('locale',function()
    tos.state.set{locales=(tos.state.get('locales')or 0)+1,hour24=tos.locale().hour24,zone=tos.tz.home()}
  end);tos.on('location',function()tos.state.set{locations=(tos.state.get('locations')or 0)+1}end)`, 'one', { permissions: ['location'] })]);
  await until(() => f.runtime.active.has('locale'), 'locale consumer');
  f.runtime.configure({hour24:false,timezone:'Asia/Tokyo'});
  const instance = f.runtime.active.get('locale');
  check(instance.state.locales === 1 && instance.state.hour24 === false && instance.state.zone === 'Asia/Tokyo', 'locale change was not broadcast with new settings');
  f.runtime.configure({location:{name:'Fixture',latitude:0,longitude:0,timezone:'Europe/Paris'},http:{fixture:{status:200,body:'old'}}});
  const previousLocations = instance.state.locations, before = Date.now();
  f.runtime.configure({locale:'zh-Hans',hour24:true,timezone:'Europe/London',networkMode:'fixture'},false,true);
  check(instance.state.locales === 2 && instance.state.hour24 === true && instance.state.zone === 'Europe/London', 'replacement locale did not resolve fallback place');
  check(instance.state.locations === previousLocations + 1, 'removing fixture location did not broadcast');
  check(!Object.hasOwn(f.runtime.environment,'now') && !Object.hasOwn(f.runtime.environment,'location') && !Object.hasOwn(f.runtime.environment,'http'), 'replacement retained optional fixtures');
  check(f.runtime.now() >= before && f.runtime.now() - Date.now() < 100, 'removing virtual time did not restore wall clock');
});

await run('Real Lua form changes authorize device setup without minting update or navigation taps', async f => {
  const plugin = pkg('credit', `tos.on('start',function()
    local accepted=tos.device.setTimeZone('Europe/London');assert(accepted==false);tos.state.set{started=true}
  end)
  tos.on('form.change',function(key,value)
    if key=='place' then
      local accepted=tos.device.setTimeZone(value.timezone)
      local opened,reason=tos.update.open()
      local navigation=pcall(function()tos.settings.open('system')end)
      tos.state.set{saved=accepted,zone=tos.tz.home(),updateDenied=opened==nil and reason=='tap',navigationDenied=not navigation,continued=true}
    else
      local opened=tos.update.open();tos.page.focus('main');tos.state.set{updated=opened==true}
    end
  end)`, 'one', { permissions: ['system.device','system.settings','system.update'], pages: [{id:'main',view:'view.json'}] });
  plugin.files['view.json']=encode('{}');
  await f.runtime.loadAll([plugin]);await until(()=>f.runtime.active.has('credit'),'credit commit');
  const instance=f.runtime.active.get('credit');check(instance.state.started===true,'missing touch threw instead of returning false');
  f.runtime.input('credit','form.change',['place',{timezone:'America/New_York'}],true);
  await until(()=>instance.state.continued===true,'object form callback');
  check(instance.state.saved===true&&instance.state.zone==='America/New_York'&&instance.state.updateDenied&&instance.state.navigationDenied,'form credit domains were mixed');
  f.runtime.input('credit','form.change',['toggle',true],true);await until(()=>instance.state.updated===true,'boolean form update');
  const focus=f.messages.findLast(message=>message.type==='navigate'&&message.origin==='page');
  check(focus?.answersTap===false,'boolean form minted page navigation tap');
  f.runtime.input('credit','form.change',['place',{timezone:'Europe/Paris'}],false);
  // An untrusted event cannot refresh any credit, even though it can dispatch.
  const captured=instance.onboardingTap;await pause(20);check(instance.onboardingTap===captured,'untrusted form minted a device credit');
});

postMessage({ ok: results.every(result => result.ok), results });
