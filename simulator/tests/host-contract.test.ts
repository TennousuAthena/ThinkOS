import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {Runtime} from '../web/runtime.ts';
import {Health,eventAllowed} from '../web/values.ts';

const schema=JSON.parse(readFileSync(new URL('../../shared/schema/settings/system.json',import.meta.url),'utf8'));
function harness(t:any){
 const emitted:any[]=[],events:any[]=[],resumes:any[]=[],rpcCalls:any[]=[];
 const session:any={token:'test',revision:'1',plugins:[],bootstrap:'',settings:schema,environment:{locale:'en-US',timezone:'Asia/Hong_Kong',hour24:true},storage:{},profileSettings:{system:{},plugins:{}}};
 const runtime=new Runtime(session,value=>emitted.push(value),async(name,args)=>{rpcCalls.push({name,args});return name==='media'?[{status:'playing'}]:[];});
 function add(id:string,manifest:any={}){
  let nextTask=2;
  const instance:any={pkg:{id,digest:id,factory:true,files:{},manifest:{api:10,name:id,version:'1.0',settings:{example:{type:'number',default:1,min:0,max:10}},...manifest}},
   state:{},store:{},config:{example:1},strings:{},grants:new Set(['system.settings','system.device','system.apps','system.plugins','location','media']),
   live:true,committed:true,startTask:0,tap:performance.now(),updateTap:performance.now(),onboardingTap:performance.now(),timers:new Map(),pending:new Set(),health:new Health(),commandTimes:new Map(),intercept:false,generation:1,
   vm:{start(_code:any,_name:any,entry:any,args:any){events.push({id,entry,args});return {status:0,taskId:nextTask++};},resume(taskId:number,values:any[]){resumes.push({id,taskId,values});return {status:0,taskId};},destroy(){}}};
  runtime.active.set(id,instance);return instance;
 }
 const caller=add('caller');
 const call=(method:string,args:any[]=[],instance=caller)=>(runtime as any).host(instance,method,args,1);
 const awaitCall=async(method:string,args:any[]=[],instance=caller)=>{const previous=resumes.length;assert.equal(call(method,args,instance),null);await new Promise(resolve=>setImmediate(resolve));assert.equal(resumes.length,previous+1,'await must resume exactly once');return resumes.at(-1).values;};
 t.after(()=>runtime.shutdown());return {runtime,caller,call,awaitCall,add,emitted,events,rpcCalls};
}
test('every native registered host method is routed by the simulator',()=>{
 const native=readFileSync(new URL('../../android/app/src/main/java/moe/kiwi/thinkos/plugin/PluginRuntime.kt',import.meta.url),'utf8');
 const route=native.slice(native.indexOf('private val hostCapabilities:'),native.indexOf('private val settingsListener'));
 const methods=[...route.matchAll(/listOf\(([^)]*)\)\.forEach \{ put\(it,/g)].flatMap(match=>[...match[1].matchAll(/"([^"]+)"/g)].map(value=>value[1]));
 const simulator=readFileSync(new URL('../web/runtime.ts',import.meta.url),'utf8');const cases=new Set([...simulator.matchAll(/case '([^']+)'/g)].map(value=>value[1]));
 assert.ok(methods.length>70);for(const method of methods)assert.ok(cases.has(method)||method.startsWith('media.')&&simulator.includes("method.startsWith('media.')"),method);
});
test('system settings writes return values without destroying plugin config',async t=>{
 const {call,events,caller}=harness(t);
 assert.deepEqual(call('settings.set',['system','units.system','imperial']),{value:'imperial'});
 const many=call('settings.setMany',['caller',{example:3}]);assert.equal(many.value.example,3);
 await Promise.resolve();assert.equal(caller.config.example,3);
 assert.ok(events.some(event=>event.args[0]==='units'));
 assert.ok(events.filter(event=>event.args[0]==='config').every(event=>event.args.length===2&&typeof event.args[1]==='object'));
});
test('state media projection requires permission and state reserves host-owned keys',t=>{
 const {runtime,caller,call}=harness(t);runtime.media={state:'playing',position:7};
 assert.equal(call('state.get',['media.position']),7);caller.grants.delete('media');assert.throws(()=>call('state.get',['media']));
 for(const key of ['config','media','uses'])assert.throws(()=>call('state.set',[{[key]:{}}]),key);
 caller.pkg.manifest.api=5;assert.doesNotThrow(()=>call('state.set',[{uses:{legacy:true}}]));
});
test('store.set follows actual JsonDocument recursive merge and bounds keys',t=>{
 const {call}=harness(t);call('store.set',['example',{keep:1,change:1}]);call('store.set',['example',{change:2}]);
 assert.deepEqual({...call('store.get',['example'])},{keep:1,change:2});call('store.set',['example',null]);assert.equal(call('store.get',['example']),null);
 assert.throws(()=>call('store.set',['',1]));assert.throws(()=>call('store.set',['x'.repeat(129),1]));
});
test('public data errors distinguish declarations, availability, shapes, grants and empty values',t=>{
 const {caller,call,add}=harness(t);assert.deepEqual(call('data.get',['x']),{error:'undeclared'});
 caller.pkg.manifest.uses={x:{export:'provider.x',shape:'record'}};assert.deepEqual(call('data.get',['x']),{error:'missing'});
 const provider=add('provider',{exports:{x:{path:'public.x',shape:'wrong'}}});assert.deepEqual(call('data.get',['x']),{error:'shape_mismatch'});
 provider.pkg.manifest.exports.x={path:'public.x',shape:'record',requires:['network']};assert.deepEqual(call('data.get',['x']),{error:'permission_required'});
 caller.grants.add('network');assert.deepEqual(call('data.get',['x']),{error:'empty'});
 provider.state={public:{x:{number:1}}};assert.equal(call('data.get',['x']).text,'{"number":1}');
 provider.state.public.x='x'.repeat(65537);assert.deepEqual(call('data.get',['x']),{error:'too_large'});
});
test('cross-plugin commands preserve native errors and caller provenance',async t=>{
 const {caller,call,add,events}=harness(t);assert.deepEqual(call('command.call',['provider.refresh',{}]),{error:'undeclared'});
 caller.pkg.manifest.calls=['provider.refresh'];assert.deepEqual(call('command.call',['provider.refresh',{}]),{error:'unavailable'});
 const provider=add('provider');assert.deepEqual(call('command.call',['provider.refresh',{}]),{error:'not_public'});
 provider.pkg.manifest.commands=[{id:'refresh',public:true,cooldown:0}];assert.deepEqual(call('command.call',['provider.refresh',{value:'x'.repeat(4096)}]),{error:'too_large'});
 assert.deepEqual(call('command.call',['provider.refresh',{value:1}]),{ok:true});await Promise.resolve();
 assert.deepEqual(events.find(event=>event.id==='provider'&&event.args[0]==='command')?.args,['command','refresh',{value:1},'plugin','caller']);
});
test('onboarding and device snapshot include fields required by bundled welcome plugin',t=>{
 const {call}=harness(t),onboarding=call('onboarding.status');assert.equal(onboarding.version,1);assert.ok(Number(onboarding.greeting_seconds)>0);assert.equal(typeof onboarding.active,'boolean');assert.equal(typeof onboarding.updater,'boolean');
 const device=call('device.get');assert.equal(typeof device.online,'boolean');assert.ok(Number.isInteger(device.brightness)&&device.brightness>=0&&device.brightness<=100);assert.ok(Number.isInteger(device.volume));
});
test('mode event is unavailable before API 10, independent of grants',()=>{
 assert.equal(eventAllowed('mode',9,new Set()),false);assert.equal(eventAllowed('mode',10,new Set()),true);
});
test('unrelated timer writes cannot consume a pending Back response',t=>{
 const {call,caller,emitted}=harness(t);call('state.set',[{timer:1}]);assert.ok(!emitted.some(e=>e.type==='backAnswered'));
 caller.backSlice=true;call('state.get',['timer']);assert.ok(!emitted.some(e=>e.type==='backAnswered'));
 call('state.set',[{page:2}]);assert.equal(emitted.filter(e=>e.type==='backAnswered').length,1);
});
test('environment patches preserve elapsed virtual time until now is explicitly set',t=>{
 let elapsed=100;t.mock.method(performance,'now',()=>elapsed);
 const {runtime}=harness(t);runtime.configure({now:2000});elapsed=1600;assert.equal(runtime.now(),3500);
 runtime.configure({locale:'fr'});assert.equal(runtime.now(),3500);elapsed=2100;runtime.configure({mode:'redshift',timezone:'Europe/Paris'});assert.equal(runtime.now(),4000);
 runtime.configure({now:9000});assert.equal(runtime.now(),9000);elapsed=2200;assert.equal(runtime.now(),9100);
});
test('shutdown stop can save state and storage before its committed grant is released',t=>{
 const {runtime,caller,call,emitted}=harness(t);let stopped=0;
 caller.vm.start=(_code:any,_name:any,entry:any,args:any[])=>{
  assert.equal(entry,'dispatch');assert.deepEqual(args,['stop']);stopped++;
  call('state.set',[{stopped:true}]);call('store.set',['saved',call('state.get',['stopped'])]);call('log',['info','stop saved']);
  call('timer.cancel',[17]);assert.throws(()=>call('page.focus',['main']),/stopping/);assert.throws(()=>call('http.get',['https://example.test']),/stopping/);
  return {status:0,taskId:2};
 };
 runtime.shutdown();assert.equal(stopped,1);assert.equal(caller.store.saved,true);assert.equal(caller.state.stopped,true);
 assert.deepEqual(emitted.filter(value=>value.type==='store').map(value=>({digest:value.digest,value:{...value.value}})),[{digest:'caller',value:{saved:true}}]);
 assert.ok(emitted.findIndex(value=>value.type==='store')<emitted.findIndex(value=>value.type==='release'));
 assert.equal(emitted.filter(value=>value.type==='state').length,0);assert.equal(emitted.filter(value=>value.type==='log').length,1);
 assert.throws(()=>call('store.set',['late',true]),/stopped/);runtime.shutdown();assert.equal(stopped,1);
});
test('replacement stop shares the committed store and only flushes its new digest',async t=>{
 const {runtime,caller,call,add,emitted}=harness(t);caller.store={generation:'old'};
 caller.vm.start=()=>{
  assert.equal(call('store.get',['generation'],caller),'new');call('store.set',['savedDuringStop',true],caller);call('state.set',[{stopped:true}],caller);
  return {status:0,taskId:2};
 };
 const replacement=add('caller');replacement.pkg={...replacement.pkg,digest:'new'};replacement.store={generation:'new'};replacement.state={generation:'new'};replacement.committed=false;
 runtime.active.set('caller',caller);runtime.candidates.set('caller',replacement);
 await (runtime as any).commit(replacement);
 assert.equal(runtime.active.get('caller'),replacement);assert.deepEqual({...replacement.store},{generation:'new',savedDuringStop:true});
 const saves=emitted.filter(value=>value.type==='store');assert.equal(saves.length,1);assert.equal(saves[0].digest,'new');assert.equal(saves[0].value.savedDuringStop,true);
 assert.ok(emitted.filter(value=>value.type==='state').every(value=>value.plugins.caller?.state.stopped===undefined));
});
test('environment replacement clears optional fixtures and broadcasts effective changes',t=>{
 const {runtime,caller,call,events,emitted}=harness(t);caller.grants.add('presence');t.mock.method(Date,'now',()=>1800000000000);
 runtime.configure({now:1700000000000,location:{name:'Manual',latitude:10,longitude:20,timezone:'Europe/Paris'},http:{'https://example.test':{status:200,body:'old'}},mode:'sleep',presence:true,attention:'SLEEP'});
 events.length=0;emitted.length=0;
 runtime.configure({locale:'en-US',timezone:'Asia/Tokyo',hour24:false},false,true);
 for(const key of ['now','location','http'])assert.equal(Object.hasOwn(runtime.environment,key),false,key);
 assert.equal(runtime.now(),1800000000000);assert.equal(call('location.get').timezone,'Asia/Tokyo');assert.equal(call('locale').hour24,false);
 assert.equal(events.filter(event=>event.args[0]==='locale').length,1);assert.equal(events.filter(event=>event.args[0]==='location').length,1);
 assert.ok(events.some(event=>event.args[0]==='mode'&&event.args[1]==='day'));assert.ok(events.some(event=>event.args[0]==='presence'&&event.args[1]===false));
 assert.ok(events.some(event=>event.args[0]==='attention'&&event.args[1]==='AWAKE'));assert.ok(!emitted.some(value=>value.type==='environmentPatch'));
 events.length=0;runtime.configure({...runtime.environment},false,true);assert.equal(events.length,0);
 runtime.configure({hour24:true},false);assert.equal(events.filter(event=>event.args[0]==='locale').length,1);
});
test('trusted form events mint separate native navigation, update and device credits',async t=>{
 let at=10000;t.mock.method(performance,'now',()=>at);
 const {runtime,caller,call,awaitCall,emitted}=harness(t);caller.grants.add('system.update');caller.pkg.manifest.pages=[{id:'main'}];
 caller.tap=-Infinity;caller.updateTap=null;caller.onboardingTap=null;
 runtime.input('caller','form.change',['location',{timezone:'America/New_York'}],true);
 assert.equal(caller.tap,-Infinity);assert.equal(caller.updateTap,null);assert.equal(caller.onboardingTap,at);
 assert.deepEqual(await awaitCall('device.setTimeZone',['America/New_York']),[true]);assert.deepEqual(await awaitCall('update.open'),[null,'tap']);
 assert.throws(()=>call('settings.open',['system']),/tap/);assert.doesNotThrow(()=>call('onboarding.open',['resume']));
 call('page.focus',['main']);assert.equal(emitted.findLast(value=>value.type==='navigate').answersTap,false);
 runtime.input('caller','form.change',['enabled',true],true);assert.equal(caller.updateTap,at);assert.equal(caller.tap,-Infinity);
 assert.deepEqual(await awaitCall('update.open'),[true]);assert.doesNotThrow(()=>call('plugins.setAuto',['absent',true]));
 assert.throws(()=>call('settings.open',['system']),/tap/);
 caller.updateTap=null;runtime.input('caller','form.change',['enabled',true,'extra'],true);assert.equal(caller.updateTap,null);
 caller.onboardingTap=null;runtime.input('caller','form.change',['place',{}],false);assert.equal(caller.onboardingTap,null);
 assert.deepEqual(await awaitCall('device.setTimeZone',['Europe/London']),[false]);assert.deepEqual(await awaitCall('device.powerOff'),[false]);
 runtime.input('caller','tap',['open'],true);assert.equal(caller.tap,at);assert.equal(caller.updateTap,at);assert.equal(caller.onboardingTap,at);
 call('page.focus',['main']);assert.equal(emitted.findLast(value=>value.type==='navigate').answersTap,true);
 at+=5000;assert.throws(()=>call('settings.open',['system']),/tap/);assert.deepEqual(await awaitCall('device.setLocale',['en-US']),[true]);
 at++;assert.deepEqual(await awaitCall('device.setLocale',['fr']),[false]);assert.deepEqual(await awaitCall('update.open'),[null,'tap']);
 assert.deepEqual(await awaitCall('device.setMode',['sleep']),[null,'device mode needs a recent touch']);
});
test('asynchronous controls check captured event credit when work begins',async t=>{
 let at=10000;t.mock.method(performance,'now',()=>at);
 const {runtime,caller,call,awaitCall,emitted}=harness(t);caller.grants.add('system.update');
 caller.onboardingTap=at-5001;
 assert.equal(call('device.setTimeZone',['Europe/London']),null);
 runtime.input('caller','form.change',['place',{}],true);
 await new Promise(resolve=>setImmediate(resolve));assert.equal(runtime.environment.timezone,'Asia/Hong_Kong');
 runtime.settings['update.manual_grace_s']=1;caller.updateTap=at-1001;
 assert.deepEqual(await awaitCall('update.open'),[null,'tap']);
 const notices=emitted.filter(value=>value.type==='notice').length;call('device.powerOff');runtime.shutdown();
 await new Promise(resolve=>setImmediate(resolve));assert.equal(emitted.filter(value=>value.type==='notice').length,notices);
});
test('simulated registry installs and updates export only the closed installed-record contract',async t=>{
 const {runtime,awaitCall,call}=harness(t);
 const entry={id:'example',version:'1.0.0',digest:'a'.repeat(64),repo:'owner/example',commit:'b'.repeat(40),tier:'community',name:'Example',author:'Author',license:'MIT',tags:['test'],size:1,permissions:['media'],summary:'A plugin',why:{media:'Play audio'},installed:false,update:false,revoked:false,error:null};
 runtime.configure({catalog:{items:[entry]},operationResults:{'plugins.install':{ok:true},'plugins.update':{ok:true}}});
 assert.deepEqual(await awaitCall('plugins.install',['example']),[true]);
 const installed=call('plugins.installed')[0];
 assert.deepEqual(Object.keys(installed).sort(),['id','version','digest','repo','commit','tier','name','auto','enabled','revoked','update','error','available_version','apps'].sort());
 assert.equal(installed.auto,true);assert.deepEqual(installed.apps,[]);assert.equal(installed.error,'');
 call('plugins.setAuto',['example',false]);runtime.environment.catalog.items[0]={...entry,version:'1.1.0',digest:'c'.repeat(64),commit:'d'.repeat(40)};
 assert.deepEqual(await awaitCall('plugins.update',['example']),[true]);const updated=call('plugins.installed')[0];assert.equal(updated.auto,false);assert.equal(updated.version,'1.1.0');assert.equal(updated.available_version,'1.1.0');
 // Use the gateway's actual import validator on JSON export, including external schema refs.
 execFileSync('python3',['-I','-c','import json,sys;sys.path.insert(0,sys.argv[1]);from simulator.contracts import request;request("Environment",json.load(sys.stdin))',fileURLToPath(new URL('../../',import.meta.url))],{input:JSON.stringify(runtime.environment),encoding:'utf8'});
});
test('native media public_only API gates apply even to false and permit ordinary old-API playback',async t=>{
 const {caller,awaitCall,rpcCalls}=harness(t);caller.grants.add('network');caller.pkg.manifest.api=9;
 assert.deepEqual(await awaitCall('media.load',[[{url:'https://example.test/audio.mp3'}]]),[{status:'playing'}]);
 assert.deepEqual(await awaitCall('media.probe',['https://example.test/audio.mp3']),[{status:'playing'}]);
 const before=rpcCalls.length;
 for(const value of [false,true]){
  const load=await awaitCall('media.load',[[{url:'https://example.test/audio.mp3',public_only:value}]]);assert.equal(load[0],null);assert.match(load[1],/media\.public_only.*newer plugin API/);
  const probe=await awaitCall('media.probe',['https://example.test/audio.mp3',{public_only:value}]);assert.equal(probe[0],null);assert.match(probe[1],/media\.probe\.public_only.*newer plugin API/);
 }
 assert.equal(rpcCalls.length,before);caller.pkg.manifest.api=10;
 assert.deepEqual(await awaitCall('media.probe',['https://example.test/audio.mp3',{public_only:false}]),[{status:'playing'}]);
 assert.equal((await awaitCall('media.probe',['https://example.test/audio.mp3',{public_only:'false'}]))[0],null);
 assert.equal((await awaitCall('media.probe',['https://example.test/audio.mp3',{unexpected:true}]))[0],null);
});
test('media grants cover subtitles and invalid controls fail asynchronously before RPC',async t=>{
 const {caller,awaitCall,rpcCalls}=harness(t);caller.grants.add('library');
 const noNetwork=await awaitCall('media.load',[[{url:'/audio.mp3',subtitles:[{url:'https://example.test/text.vtt'}]}]]);assert.equal(noNetwork[0],null);assert.match(noNetwork[1],/network permission/);assert.equal(rpcCalls.length,0);
 caller.grants.delete('library');caller.grants.add('network');
 const noLibrary=await awaitCall('media.load',[[{url:'https://example.test/audio.mp3',subtitles:[{url:'/text.vtt'}]}]]);assert.equal(noLibrary[0],null);assert.match(noLibrary[1],/library permission/);assert.equal(rpcCalls.length,0);
 for(const [method,args] of [['media.shuffle',[1]],['media.seek',[-1]],['media.seek',['1']],['media.volume',[1.1]],['media.audioTrack',[0.5]],['media.textTrack',[513]],['media.load',[[{url:'https://example.test/audio.mp3'}],{autoplay:'yes'}]]] as const){assert.equal((await awaitCall(method,[...args]))[0],null,method);}
 assert.equal(rpcCalls.length,0);assert.deepEqual(await awaitCall('media.textTrack',[0]),[{status:'playing'}]);
 caller.grants.delete('media');const denied=await awaitCall('media.play');assert.equal(denied[0],null);assert.match(denied[1],/media permission/);
 const libraryDenied=await awaitCall('library.list',[{kind:'audio'}]);assert.equal(libraryDenied[0],null);assert.match(libraryDenied[1],/library permission/);
});
