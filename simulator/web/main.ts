import './styles.css';
import './workbench.css';
import {Renderer,type Scene,type RendererPlugin} from './renderer.ts';
import {Media} from './media.ts';
import {base64Text,json,parseJson,type Obj} from './values.ts';
import {verifySnapshot} from './snapshot.ts';
import {NativeNav} from '../reference/generated/thinkos-reference.mjs';
import type {Session} from './runtime.ts';
import {locationFromSettings} from './settings-normalization.ts';
import {applyNavigationChange,homePage,openOnboarding,openPage,requestPageFocus,type NavigationContext,type NavigationSelection} from './navigation.ts';

const root=document.querySelector<HTMLDivElement>('#app')!;
root.innerHTML=`<header class="workbar"><strong>ThinkOS</strong><span class="quiet">Simulator</span><span id="connection">连接中</span><span class="spacer"></span><button id="sound">启用声音</button><button id="capture">截图</button></header>
<main class="workspace"><aside class="sidebar"><h2>设备</h2><label>页面<select id="page"></select></label><label>方向<select id="orientation"><option value="landscape">横屏</option><option value="portrait">竖屏</option></select></label><label>主题<select id="theme"><option value="DARK">深色</option><option value="LIGHT">浅色</option><option value="NIGHT">红移</option></select></label><label>字号<select id="fontScale"><option value="1">100%</option><option value="1.3">130%</option></select></label><label>语言<select id="locale"></select></label><label>网络<select id="networkMode"><option value="fixture">固定场景</option><option value="live">真实联网</option></select></label><label>系统插件模拟授权<select id="systemSimulation"><option value="">关闭</option></select></label><h2>插件</h2><div id="plugins"></div><div class="sidebar-actions"><button id="export">导出场景</button><button id="import">导入场景</button><input type="file" id="scenario" accept="application/json" hidden></div></aside>
<section class="stage-area"><div class="stage-toolbar"><button id="back">返回</button><button id="home">首页</button><button id="panel">控制栏</button><span class="spacer"></span><span id="fidelity">视觉待校准</span></div><div class="screen-fit"><canvas id="screen" width="1280" height="800" tabindex="0" aria-label="ThinkOS 模拟设备"></canvas></div><div id="notice" role="status"></div></section>
<aside class="inspector"><div class="inspector-tabs"><button data-tab="state">状态</button><button data-tab="logs">日志</button><button data-tab="trace">API</button></div><pre id="inspect"></pre></aside></main>`;
const $=<T extends HTMLElement>(id:string)=>document.getElementById(id) as T;
let session:Session,renderer:Renderer,worker:Worker,media:Media;
let scene:Scene={plugins:{},page:'home.main',theme:'DARK',orientation:'landscape',fontScale:1,now:Date.now(),locale:'zh-Hans'};
let activeStates:Obj={},logs:Obj[]=[],traces:Obj[]=[],tab='state',selected='clock';
const history=new NativeNav();const intercept=new Map<string,boolean>();const activePackages=new Map<string,any>();
const prepared=new Map<string,{pkg:any,view:RendererPlugin,urls:string[]}>();
let heartbeat=performance.now(),ready=false,backTimer:ReturnType<typeof setTimeout>|undefined;
let clockAnchor=performance.now();
let lastLocal=-Infinity;
let mediaMode='',practice:{id:string;name:string}|null=null;
const urls=new Set<string>();
function inspect(){const content=tab==='logs'?logs:tab==='trace'?traces:activeStates[selected]??{};$('inspect').textContent=json(content);}
function note(message:string){$('notice').textContent=message;}
function send(value:Obj){worker?.postMessage(value);}
async function api(name:string,args:Obj,binary=false,signal?:AbortSignal){
 const response=await fetch('/api/'+name,{method:'POST',headers:{'Content-Type':'application/json','X-ThinkOS-Session':session.token},body:json(args),signal});
 if(!response.ok){let error:any;try{error=await response.json();}catch{}throw Error(error?.error?.message??`HTTP ${response.status}`);}
 return binary?response:parseJson(await response.text());
}
function render(){if(!renderer)return;scene.now=typeof session?.environment.now==='number'?session.environment.now+performance.now()-clockAnchor:Date.now();renderer.setScene(scene);}
function currentVariant(){return scene.variant??scene.plugins[scene.page.split('.')[0]]?.state.variant??'main';}
function navigationContext():NavigationContext{return {pages:pages().map(p=>({key:p.key,owner:p.plugin.manifest.id??p.key.split('.')[0],roles:p.page.roles??[],app:!!p.plugin.manifest.apps?.some((app:Obj)=>app.page===p.page.id),live:p.page.live===true,priority:Number(p.page.priority??0),variant:p.key===scene.page?currentVariant():p.plugin.state.variant??'main'})),current:scene.page,variant:currentVariant(),attention:session?.environment.attention??'AWAKE',now:performance.now(),lastLocal};}
function showNavigation(selection:NavigationSelection|null,params?:Obj){if(selection)changePage(selection.page,false,params,true,selection.variant);}
function home(){lastLocal=performance.now();history.clear();const target=homePage(navigationContext().pages);if(target)changePage(target.key,false);}
function launcher(key?:string){const target=pages().find(p=>p.key===key&&p.page.roles?.includes('launcher'))??pages().find(p=>p.page.roles?.includes('launcher'));if(!target)return;lastLocal=performance.now();history.clear();changePage(target.key,false);}
function chooseWorkbenchPage(key:string){lastLocal=performance.now();history.clear();const context=navigationContext(),target=context.pages.find(p=>p.key===key),base=homePage(context.pages);if(!target)return;if(base&&(target.roles.includes('settings')||target.roles.includes('onboarding')))history.push(base.key,base.variant,target.roles.includes('settings')?'SETTINGS':'ONBOARDING',target.key,target.variant);changePage(key,false);}
function changePage(page:string,push=true,params?:Obj,force=false,variant?:string){
 if(page===scene.page&&ready&&!params&&!force)return;
 if(!pages().some(p=>p.key===page))return;
 const roles=pages().find(p=>p.key===page)?.page.roles??[];
 if(push&&scene.page&&page!==scene.page&&!history.push(scene.page,currentVariant(),roles.includes('settings')?'SETTINGS':roles.includes('onboarding')?'ONBOARDING':'APP',page,variant??'main')){$<HTMLSelectElement>('page').value=scene.page;return;}
 clearTimeout(backTimer);
 const old=scene.page.split('.')[0];if(ready&&scene.page!==page){send({type:'event',id:old,event:'page.leave',args:[scene.page.split('.')[1]]});intercept.delete(old);}
 scene.page=page;scene.variant=variant;$<HTMLSelectElement>('page').value=page;
 const id=page.split('.')[0];selected=id;send({type:'event',id,event:'page.enter',args:[page.split('.')[1],scene.variant??'main',params??null]});
 render();inspect();
}
function back(force=false){if(!force)lastLocal=performance.now();const id=scene.page.split('.')[0];if(!force&&intercept.get(id)){send({type:'event',id,event:'back',args:[]});backTimer=setTimeout(()=>back(true),2000);return;}if(history.topKind==='ONBOARDING'){home();return;}if(!history.depth)return;showNavigation(applyNavigationChange(navigationContext(),history.pop()??''));}
function pages(){return Object.entries(scene.plugins).flatMap(([id,p])=>(p.manifest.pages??[]).map((x:Obj)=>({key:id+'.'+x.id,page:x,plugin:p})));}
function rendererEvent(event:{pluginId:string,event:string,args:unknown[]}){
 if(event.event!=='form.layout')lastLocal=performance.now();
 const [a,b]=event.args;
 const practiced=({'@deck':'swipe','@variant':'variant','@control':'panel','@back':'back','@home':'home','@settings':'long-press'} as Obj)[event.event];
 if(practice&&practice.name===practiced){const id=practice.id;practice=null;send({type:'event',id,event:'practice',args:[practiced]});if(practiced!=='home')return;}
 switch(event.event){
  case '@navigate':if(pages().find(p=>p.key===String(a))?.page.roles?.includes('launcher'))launcher(String(a));else showNavigation(openPage(history,navigationContext(),String(a)));break;case '@back':back();break;
  case '@home':home();break;
  case '@control':changePage('panel.main');break;
  case '@settings':openSettings(String(a??event.pluginId));break;
  case '@deck':{if(history.depth)break;const deck=pages().filter(p=>!['settings','onboarding','panel','launcher'].some(r=>p.page.roles?.includes(r))&&!p.page.live&&!p.plugin.manifest.apps?.some((app:Obj)=>app.page===p.page.id));const index=deck.findIndex(p=>p.key===scene.page);if(index>=0&&deck.length)changePage(deck[(index+Number(a)+deck.length)%deck.length].key,false);break;}
  case '@variant':{if(history.has('SETTINGS')||history.has('ONBOARDING'))break;const current=pages().find(p=>p.key===scene.page);if(!current)break;const variants=current.page.variants??current.plugin.views[current.page.view]?.variants??[];if(!variants.length)break;const index=variants.findIndex((v:any)=>(typeof v==='string'?v:v.id)===(scene.variant??current.plugin.state.variant));const v=variants[(index+Number(a)+variants.length)%variants.length];scene.variant=typeof v==='string'?v:v.id;history.variantChanged(scene.variant!);send({type:'event',id:event.pluginId,event:'variant',args:[scene.variant]});render();break;}
  case '@media':{const pkg=activePackages.get(event.pluginId);if(pkg)void media.call({pluginId:event.pluginId,digest:pkg.digest,generation:activeStates[event.pluginId]?.generation,permissions:activeStates[event.pluginId]?.granted??[],method:'media.'+a,args:[b]});break;}
  case '@level':if(a==='volume')media.video.volume=Math.max(0,Math.min(1,Number(b)));void environment(String(a),Math.round(Number(b)*100));break;
  default:send({type:'event',id:event.pluginId,event:event.event,args:event.args,trusted:true});
 }
}
function openSettings(scope:string,section?:string){if(history.has('ONBOARDING'))return;const target=pages().find(p=>p.page.roles?.includes('settings'));if(target)changePage(target.key,!history.has('SETTINGS'),{scope,...(section?{section}:{})});}
async function packageViews(packages:any[]){
 for(const pkg of packages){
  if(prepared.has(pkg.digest))continue;await verifySnapshot(pkg);
  const views:Obj={},assets:Record<string,string>={},i18n:Obj={},owned:string[]=[];
  for(const [name,base64] of Object.entries(pkg.files) as [string,string][]){
   if(name.startsWith('views/')&&name.endsWith('.json'))views[name]=JSON.parse(base64Text(base64));
   if(name.startsWith('i18n/'))i18n[name.slice(5,-5)]=JSON.parse(base64Text(base64));
   if(name.startsWith('assets/')){const type=name.endsWith('.png')?'image/png':name.endsWith('.webp')?'image/webp':name.match(/\.jpe?g$/)?'image/jpeg':'application/octet-stream';const url=URL.createObjectURL(new Blob([Uint8Array.from(atob(base64),c=>c.charCodeAt(0))],{type}));urls.add(url);owned.push(url);assets[name]=url;}
  }
  prepared.set(pkg.digest,{pkg,urls:owned,view:{manifest:pkg.manifest,views,assets,state:{},config:{},i18n}});
 }
}
function commitViews(){
 const next:Record<string,RendererPlugin>={};activePackages.clear();
 for(const [id,state] of Object.entries(activeStates) as [string,any][]){const entry=prepared.get(state.digest);if(!entry)continue;activePackages.set(id,entry.pkg);next[id]={...entry.view,state:{...state.state,uses:state.uses??{}},config:state.config};}
 scene.plugins=next;
 const wanted=new Set([...session.plugins.map(p=>p.digest),...Object.values(activeStates).map(s=>s.digest)]);
 for(const [digest,entry] of prepared)if(!wanted.has(digest)){for(const url of entry.urls){URL.revokeObjectURL(url);urls.delete(url);}prepared.delete(digest);}
 const picker=$<HTMLSelectElement>('page');picker.replaceChildren();for(const p of pages()){const option=document.createElement('option');option.value=p.key;option.textContent=p.key;picker.append(option);}picker.value=scene.page;
 $('plugins').replaceChildren();const system=$<HTMLSelectElement>('systemSimulation');system.replaceChildren(new Option('关闭',''));
 for(const pkg of activePackages.values()){const button=document.createElement('button');button.textContent=pkg.id;button.className='plugin-item';button.onclick=()=>{selected=pkg.id;inspect();};$('plugins').append(button);if(session.factoryPlugins?.includes(pkg.id)&&pkg.manifest.permissions?.some((p:string)=>p.startsWith('system.')))system.add(new Option(pkg.id,pkg.id));}
 system.value=session.environment.systemSimulation?.[0]??'';
 if(ready){showNavigation(applyNavigationChange(navigationContext(),history.prune(pages().map(p=>p.key).join('\n'))));if(!pages().some(p=>p.key===scene.page)){const target=homePage(navigationContext().pages);if(target)changePage(target.key,false);}}
 render();
}
function receive(message:Obj){
 switch(message.type){
  case 'heartbeat':heartbeat=performance.now();break;
  case 'state':{
   activeStates=message.plugins;session.environment=message.environment;
   for(const key of ['orientation','theme','locale','fontScale','timezone','hour24'])(scene as any)[key]=session.environment[key]??(scene as any)[key];
   scene.location=session.environment.location??locationFromSettings(message.settings,session.environment.timezone??'Asia/Hong_Kong');
   scene.settings={...message.settings,brightness:(session.environment.brightness??message.settings['display.brightness_max']??85)/100,volume:(session.environment.volume??0)/100};
   if(mediaMode!==session.environment.mode){mediaMode=session.environment.mode??'day';media.setMode(mediaMode);}
   commitViews();
   render();inspect();break;
  }
  case 'ready':{ready=true;$('connection').textContent='运行中';const page=pages().some(p=>p.key===scene.page)?scene.page:homePage(navigationContext().pages)?.key;if(page)changePage(page,false,undefined,true);break;}
  case 'committed':if(message.id===scene.page.split('.')[0])changePage(scene.page,false,undefined,true);break;
  case 'fatal':note(message.message);$('connection').textContent='运行错误';break;
  case 'log':logs.push(message);if(logs.length>(session.developmentSettings?.log_entries??500))logs.shift();inspect();break;
  case 'trace':traces.push(message);if(traces.length>(session.developmentSettings?.log_entries??500))traces.shift();if(tab==='trace')inspect();break;
  case 'rejected':note(`${message.id} 未替换：${message.message}`);break;
  case 'store':void api('storage',{pluginId:message.id,digest:message.digest,value:message.value}).catch(e=>note(String(e)));break;
  case 'settings':{const patch=message.scope==='system'?{system:message.patch}:{plugins:{[message.scope]:message.patch}};void api('profile',{settings:patch}).catch(e=>note(String(e)));break;}
  case 'settingsReset':void api('profile',{reset:[{scope:message.scope,...(message.key?{key:message.key}:{})}]}).catch(e=>note(String(e)));break;
  case 'environmentPatch':void api('profile',{environment:message.value}).catch(e=>note(String(e)));break;
  case 'updateOpen':updateDialog(message);break;
  case 'navigate':{
   if(message.action==='back')back(true);
   else if(message.action==='settings')openSettings(message.scope,message.section);
   else if(message.origin==='onboarding'){if(!session.environment.safeMode)showNavigation(openOnboarding(history,navigationContext(),message.page),{mode:message.mode??'resume'});}
   else if(message.origin==='apps'){lastLocal=performance.now();if(message.launcher)launcher(message.page);else showNavigation(openPage(history,navigationContext(),message.page));}
   else showNavigation(requestPageFocus(history,navigationContext(),{page:message.page,release:message.action==='release',live:message.live,answersTap:message.answersTap}));
   break;
  }
  case 'backIntercept':intercept.set(message.id,message.on);if(!message.on&&message.id===scene.page.split('.')[0])clearTimeout(backTimer);break;
  case 'backAnswered':if(message.id===scene.page.split('.')[0])clearTimeout(backTimer);break;
  case 'notice':note(message.message);break;
  case 'practice':practice=message.name?{id:message.id,name:message.name}:null;note(message.text??'');break;
  case 'tile':renderer.playTileAnimation(message.id,message.name);break;
  case 'release':media.release(message.id,message.generation);if(message.committed)void api('runtime',{pluginId:message.id,digest:message.digest,action:'release'}).catch(e=>note(String(e)));break;
  case 'rpc':{
   const request=message.name==='media'?media.call(message.args):api(message.name,message.args);
   void request.then(value=>send({type:'reply',id:message.id,value})).catch(e=>send({type:'reply',id:message.id,error:String(e)}));break;
  }
 }
}
async function environment(key:string,value:any){
 try{await applyEnvironment({[key]:value});}catch(e){note(String(e));}
}
async function applyEnvironment(patch:Obj,replace=false){
 const result=await api('profile',{environment:patch,...(replace?{replaceEnvironment:true}:{})});
 if(replace)session.environment=result.environment;else Object.assign(session.environment,patch);
 if(replace||Object.hasOwn(patch,'now'))clockAnchor=performance.now();
 for(const key of ['orientation','theme','fontScale','locale','networkMode']){
  const value=session.environment[key];if(value===undefined)continue;
  $<HTMLSelectElement>(key).value=String(value);
  if(key!=='networkMode')(scene as any)[key]=value;
 }
 $<HTMLSelectElement>('systemSimulation').value=session.environment.systemSimulation?.[0]??'';
 send({type:'environment',value:replace?session.environment:patch,replace});render();
}
async function watch(){
 while(true){try{
  const response=await fetch('/api/events',{headers:{'X-ThinkOS-Session':session.token}});if(!response.ok||!response.body)throw Error('watch disconnected');
  const reader=response.body.pipeThrough(new TextDecoderStream()).getReader();let buffer='';
  while(true){const {done,value}=await reader.read();if(done)break;buffer+=value;if(buffer.length>1048576)throw Error('watch event exceeds limit');let edge;while((edge=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,edge);buffer=buffer.slice(edge+2);const text=block.split('\n').filter(s=>s.startsWith('data:')).map(s=>s.slice(5).trim()).join('\n');if(!text)continue;const event=JSON.parse(text);if(event.revision&&event.revision!==session.revision){const response=await fetch('/api/snapshot',{headers:{'X-ThinkOS-Session':session.token}});if(!response.ok)throw Error('snapshot unavailable');const update=await response.json();await packageViews(update.plugins);Object.assign(session,{revision:update.revision,plugins:update.plugins});send({type:'reload',plugins:update.plugins});}if(event.diagnostics?.length)note(json(event.diagnostics));}}
 }catch(e){note(String(e));}await new Promise(r=>setTimeout(r,session.developmentSettings?.watch_retry_ms??1000));}
}
async function start(){
 session=await fetch('/api/session').then(async r=>{if(!r.ok)throw Error('无法连接本地服务');return parseJson(await r.text(),64*1024*1024);});
 if(session.diagnostics?.length)note(json(session.diagnostics));
 Object.assign(scene,{...session.environment,settings:session.profileSettings?.system??{}});
 for(const locale of ['zh-Hans','zh-Hant','en','es','fr','pt','ru','ar','hi','bn','ur'])$<HTMLSelectElement>('locale').add(new Option(locale,locale));
 for(const key of ['orientation','theme','locale','networkMode','fontScale']){
  const input=$<HTMLSelectElement>(key);input.value=String(session.environment[key]??(scene as any)[key]??'fixture');input.onchange=()=>void environment(key,key==='fontScale'?Number(input.value):input.value);
 }
 media=new Media(api,state=>{scene.media=state;send({type:'broadcast',event:'media',args:[state]});render();},(owner,event,payload)=>{if(activeStates[owner.pluginId]?.generation===owner.generation)send({type:'event',id:owner.pluginId,event,args:[payload]});});
 renderer=await Renderer.create($<HTMLCanvasElement>('screen'),rendererEvent,{fonts:(session.fonts??[]).map((f:Obj)=>({family:f.family,data:Uint8Array.from(atob(f.data),c=>c.charCodeAt(0))})),glyphs:session.glyphs??{},systemStrings:session.systemStrings,video:media.video,
  searchPlaces:async(query,locale)=>{const value=await api('place-search',{query,locale});if(value.offline)throw Error('offline');return value.items;},
  loadImage:async(id,src)=>{const pkg=activePackages.get(id);if(!pkg)return null;try{const r=await api('resource',{pluginId:id,digest:pkg.digest,url:src,kind:'image'},true) as Response;return new Uint8Array(await r.arrayBuffer());}catch(e){note(String(e));return null;}}});
 await packageViews(session.plugins);
 worker=new Worker(new URL('./worker.ts',import.meta.url),{type:'module'});worker.onmessage=({data})=>receive(data);worker.onerror=e=>note(e.message);const {fonts,glyphs,...workerSession}=session;send({type:'init',session:workerSession});
 $('page').onchange=()=>chooseWorkbenchPage($<HTMLSelectElement>('page').value);
 $('systemSimulation').onchange=()=>void environment('systemSimulation',$<HTMLSelectElement>('systemSimulation').value?[$<HTMLSelectElement>('systemSimulation').value]:[]);
 $('back').onclick=()=>back();$('home').onclick=home;$('panel').onclick=()=>{lastLocal=performance.now();const panel=pages().find(p=>p.page.roles?.includes('panel'));if(panel)changePage(panel.key);};$('sound').onclick=()=>{media.unlock();$('sound').textContent='声音已启用';};
 $('capture').onclick=()=>{try{download(new Blob([renderer.capturePng() as BlobPart],{type:'image/png'}),'thinkos.png');}catch(e){note(String(e));}};
 $('export').onclick=()=>download(new Blob([json({version:1,environment:session.environment,page:scene.page})],{type:'application/json'}),'thinkos-scenario.json');
 $('import').onclick=()=>$<HTMLInputElement>('scenario').click();
 $('scenario').onchange=async()=>{try{const file=$<HTMLInputElement>('scenario').files?.[0];if(!file||file.size>1048576)throw Error('场景文件过大');const value=parseJson(await file.text());if(value?.version!==1)throw Error('不支持的场景版本');if(!value.environment||typeof value.environment!=='object'||Array.isArray(value.environment))throw Error('场景缺少有效环境');if(value.page!==undefined&&(typeof value.page!=='string'||!pages().some(p=>p.key===value.page)))throw Error('场景页面不可用');await applyEnvironment(value.environment,true);if(value.page)changePage(value.page);render();}catch(e){note(String(e));}finally{$<HTMLInputElement>('scenario').value='';}};
 document.querySelectorAll<HTMLElement>('[data-tab]').forEach(button=>button.onclick=()=>{tab=button.dataset.tab!;inspect();});
 setInterval(()=>{if(ready&&performance.now()-heartbeat>(session.developmentSettings?.worker_watchdog_ms??10000)){worker.terminate();ready=false;$('connection').textContent='运行时已停止';note('Worker 无响应，已终止。刷新页面恢复持久化状态。');}},session.developmentSettings?.worker_heartbeat_ms??1000);
 void watch();
}
function download(blob:Blob,name:string){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
function updateDialog(message:Obj){
 let dialog=document.querySelector<HTMLDialogElement>('#system-update');if(!dialog){dialog=document.createElement('dialog');dialog.id='system-update';document.body.append(dialog);}dialog.replaceChildren();
 const title=document.createElement('h2');title.textContent='系统更新';const status=document.createElement('p');status.textContent=(message.status.version??message.status.current_version)+' · '+message.status.phase;
 dialog.append(title,status);
 for(const [method,label,enabled] of [['check','检查',message.status.can_check],['download','下载',message.status.can_download],['install','安装',message.status.can_install]] as const){const button=document.createElement('button');button.textContent=label;button.disabled=!enabled;button.onclick=()=>send({type:'systemUpdate',id:message.id,method:'update.'+method});dialog.append(button);}
 if(message.error){const error=document.createElement('p');error.textContent=String(message.error);dialog.append(error);}
 const close=document.createElement('button');close.textContent='完成';close.onclick=()=>dialog?.close();dialog.append(close);if(!dialog.open)dialog.showModal();
}
void start().catch(error=>{$('connection').textContent='启动失败';note(String(error));});
