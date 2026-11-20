import {LuaVm} from './lua-vm.ts';
import {base64Text,bounded,clone,defaults,eventAllowed,Health,json,parseJson,merge,path,record,translate,validateSetting,type Obj} from './values.ts';
import {sun,moon,seasons} from './astronomy.ts';
import {describeSettings,type Scope} from './settings.ts';
import {localeInfo,localeCandidates,resolvedUnits,temperature,wind,canonicalZone,formatTime,formatString,zoneInfo,zonesForRegion,homeZone} from './locale-time.ts';
import {normalizeSettingsPatch,SettingValidationError,locationFromSettings} from './settings-normalization.ts';
import type {Session as WireSession} from '../generated/protocol.ts';

export interface Package {id:string;digest:string;factory:boolean;manifest:Obj;files:Record<string,string>}
export interface Session extends Pick<WireSession,'token'|'revision'|'developmentSettings'|'buildInfo'> {plugins:Package[];bootstrap:string;settings:Obj;storage?:Obj;environment:Obj;systemStrings?:Obj;[key:string]:any}
type Step={status:number;taskId:number;message?:string|null};
type Emit=(value:Obj)=>void;
type Rpc=(name:string,args:Obj)=>Promise<any>;
interface Instance {
  pkg:Package;vm:LuaVm|null;state:Obj;store:Obj;config:Obj;strings:Obj;grants:Set<string>;
  live:boolean;committed:boolean;startTask:number;tap:number;updateTap:number|null;onboardingTap:number|null;timers:Map<number,ReturnType<typeof setTimeout>>;
  pending:Set<number>;health:Health;commandTimes:Map<string,number>;intercept:boolean;generation:number;
  backSlice:boolean;backTask:number;
}
export class Runtime {
  active=new Map<string,Instance>(); candidates=new Map<string,Instance>();
  session:Session; environment:Obj; settings:Obj; media:Obj={state:'idle',queue:[],position:0,duration:0};
  private httpTags = new WeakMap<Instance, Map<string, () => void>>();
  private generation=0; private clockAnchor=performance.now(); private alive=true;
  private stopping=new Set<Instance>();
  private dataSeen=new Map<string,string>();private dataTimers=new Map<string,ReturnType<typeof setTimeout>>();
  private emit:Emit;private rpc:Rpc;
  constructor(session:Session,emit:Emit,rpc:Rpc){
    this.emit=emit;this.rpc=rpc;
    this.session=session; this.environment=clone(session.environment??{});
    this.settings={...defaults(session.settings.fields??{}),...(session.profileSettings?.system??{})};
  }
  now(){return typeof this.environment.now==='number' ? this.environment.now+performance.now()-this.clockAnchor : Date.now();}
  private own(i:Instance){return this.alive&&i.live&&(this.active.get(i.pkg.id)===i||this.candidates.get(i.pkg.id)===i);}
  log(id:string,level:string,message:string){this.emit({type:'log',id,level,message:message.slice(0,4096),at:this.now()});}
  private source(pkg:Package,file:string){const raw=pkg.files[file];if(raw===undefined)throw Error('missing plugin file: '+file);return base64Text(raw);}
  private strings(pkg:Package){
    const locale=this.environment.locale??'zh-Hans'; const result:Obj={};
    for(const tag of localeCandidates(locale).reverse()){
      const file=`i18n/${tag}.json`;if(pkg.files[file])Object.assign(result,JSON.parse(this.source(pkg,file)));
    } return result;
  }
  async loadAll(packages:Package[]){
    const wanted=new Set(packages.map(p=>p.id));
    for(const [id,i] of this.candidates)if(!wanted.has(id)){this.terminate(i);this.candidates.delete(id);}
    for(const [id,i] of this.active)if(!wanted.has(id)){this.terminate(i);this.active.delete(id);}
    this.session.plugins=packages;
    for(const pkg of packages)if(this.active.get(pkg.id)?.pkg.digest!==pkg.digest)await this.load(pkg);
    this.publish();
  }
  async load(pkg:Package,previous?:Instance){
    const obsolete=this.candidates.get(pkg.id);if(obsolete)this.terminate(obsolete);
    const before=previous??this.active.get(pkg.id);
    const simulated=(this.environment.systemSimulation??[]).includes(pkg.id)&&(this.session.factoryPlugins??[]).includes(pkg.id);
    const grants=new Set<string>((pkg.manifest.permissions??[]).filter((p:string)=>!p.startsWith('system.')||pkg.factory||simulated));
    const i:Instance={pkg,vm:null,state:clone(previous?.state??{}),store:clone(before?.store??this.session.storage?.[pkg.id]??{}),
      config:{...defaults(pkg.manifest.settings??{}),...(this.session.profileSettings?.plugins?.[pkg.id]??{})},strings:this.strings(pkg),grants,
      live:true,committed:false,startTask:0,tap:-Infinity,updateTap:null,onboardingTap:null,timers:new Map(),pending:new Set(),health:before?.health??new Health(),commandTimes:before?.commandTimes??new Map(),intercept:false,generation:++this.generation,backSlice:false,backTask:0};
    this.candidates.set(pkg.id,i);
    try{
      for(const page of pkg.manifest.pages??[]){
        for(const role of page.roles??[]){const grant=({panel:'system.device',settings:'system.settings',onboarding:'system.settings',launcher:'system.apps'} as Obj)[role];if(grant&&!grants.has(grant))throw Error(`${role} requires ${grant}`);}
      }
      const first=pkg.manifest.pages?.[0];if(first){const view=JSON.parse(this.source(pkg,first.view));const variant=(first.variants??view.variants)?.[0];if(variant)i.state.variant=typeof variant==='string'?variant:variant.id;}
      if(!pkg.manifest.main){await this.commit(i);return;}
      i.vm=await LuaVm.create((method,args,task)=>this.host(i,method,args,task));
      if(!this.own(i)){i.vm.destroy();return;}
      const bootstrap=i.vm.start(this.session.bootstrap,'@runtime/bootstrap');if(bootstrap.status!==0)throw Error(bootstrap.message??'bootstrap failed');
      i.vm.bind();
      const main=i.vm.start(this.source(pkg,pkg.manifest.main),`@${pkg.id}/${pkg.manifest.main}`);if(main.status!==0)throw Error(main.message??'module failed');
      const start=i.vm.start('',`@${pkg.id}/start`,'dispatch',['start']);i.startTask=start.taskId;this.handle(i,start,true);
    }catch(error){this.fail(i,error);}
  }
  private async commit(i:Instance){
    if(!this.own(i))return;
    try{await this.rpc('runtime',{pluginId:i.pkg.id,digest:i.pkg.digest,action:'commit'});}catch(e){this.fail(i,e);return;}
    if(!this.own(i))return;
    const old=this.active.get(i.pkg.id);this.active.set(i.pkg.id,i);this.candidates.delete(i.pkg.id);i.committed=true;
    if(old&&old!==i)this.terminate(old);this.publish();
    this.emit({type:'store',id:i.pkg.id,digest:i.pkg.digest,value:i.store});
    this.event(i.pkg.id,'attention',this.environment.attention??'AWAKE');
    this.emit({type:'committed',id:i.pkg.id,digest:i.pkg.digest});
  }
  private fail(i:Instance,error:unknown){
    this.log(i.pkg.id,'error',String(error));this.terminate(i);if(this.candidates.get(i.pkg.id)===i)this.candidates.delete(i.pkg.id);
    this.emit({type:'rejected',id:i.pkg.id,digest:i.pkg.digest,message:String(error)});
  }
  private handle(i:Instance,step:Step,startup=false){
    if(step.status!==1)i.pending.delete(step.taskId);
    if(step.status!==1&&i.backTask===step.taskId)i.backTask=0;
    if(step.status>=2){
      if(startup){this.fail(i,step.message);return;}
      this.log(i.pkg.id,'error',step.message??'Lua error');const delay=i.health.failure(performance.now(),step.status>=3&&step.status<=5);
      if(delay!==null){this.terminate(i);setTimeout(()=>{if(this.alive&&this.active.get(i.pkg.id)===i)void this.load(i.pkg,i);},delay);}
    }else if(startup&&step.status===0)void this.commit(i);
  }
  private terminate(i:Instance){
    if(!i.live)return;i.live=false;i.tap=-Infinity;i.updateTap=null;i.onboardingTap=null;i.backSlice=false;i.backTask=0;
    // Native stop runs before timer/await cancellation and store.flush(), after
    // ownership/tap revocation. Keep its synchronous cleanup available.
    this.stopping.add(i);
    try{if(i.committed)i.vm?.start('','@stop','dispatch',['stop']);}catch{/* stop must not prevent release */}
    finally{
      this.stopping.delete(i);
      for(const timer of i.timers.values())clearTimeout(timer);i.timers.clear();i.pending.clear();
      i.vm?.destroy();i.vm=null;
    }
    // A replacement shares the committed store during old.stop; commit() emits
    // that final value with the new digest accepted by the gateway.
    if(i.committed&&(!this.active.get(i.pkg.id)||this.active.get(i.pkg.id)===i))this.emit({type:'store',id:i.pkg.id,digest:i.pkg.digest,value:i.store});
    this.emit({type:'release',id:i.pkg.id,digest:i.pkg.digest,generation:i.generation,committed:i.committed});
  }
  private storeOwner(i:Instance):Instance{
    const replacement=this.active.get(i.pkg.id);
    return this.stopping.has(i)&&replacement!==i&&replacement?.live&&replacement.committed?replacement:i;
  }
  shutdown(){this.alive=false;for(const timer of this.dataTimers.values())clearTimeout(timer);this.dataTimers.clear();for(const i of [...this.active.values(),...this.candidates.values()])this.terminate(i);}
  event(id:string|null,name:string,...args:any[]){
    if(name==='media')this.media=clone(args[0]??{status:'stopped'});
    if(id&&(name==='page.enter'||name==='page.leave')&&this.active.get(id)?.pkg.manifest.pages?.some((p:Obj)=>p.roles?.includes('onboarding')))this.environment.onboarding={...this.environment.onboarding,active:name==='page.enter'};
    for(const i of id?[this.active.get(id)].filter(Boolean) as Instance[]:[...this.active.values()]){
      if(!this.own(i)||!i.vm||!eventAllowed(name,i.pkg.manifest.api,i.grants))continue;
      i.backSlice=name==='back';
      try{const step=i.vm.start('',`@event/${name}`,'dispatch',[name,...args]);if(name==='back')i.backTask=step.status===1?step.taskId:0;this.handle(i,step);}catch(e){this.log(i.pkg.id,'error',String(e));}finally{i.backSlice=false;}
    }this.publish();
  }
  input(id:string,name:string,args:any[],trusted:boolean){
    const i=this.active.get(id);if(!i)return;
    if(trusted){
      const at=performance.now();
      if(name==='tap')i.tap=at;
      if(name==='tap'||name==='form.change'&&args.length===2&&typeof args[1]==='boolean')i.updateTap=at;
      if(name==='tap'||name==='form.change')i.onboardingTap=at;
    }
    this.event(id,name,...args);
  }
  configure(environment:Obj,persist=true,replace=false){
    const previous=this.environment,previousPlace=this.place();
    this.environment=replace?clone(environment):{...this.environment,...environment};
    const changed=(key:string)=>json(previous[key])!==json(this.environment[key]);
    if(Object.hasOwn(environment,'now')||replace&&changed('now'))this.clockAnchor=performance.now();
    for(const i of this.active.values())i.strings=this.strings(i.pkg);
    if(['locale','hour24','timezone'].some(changed))this.event(null,'locale');
    if(json(previousPlace)!==json(this.place()))this.event(null,'location');
    if(changed('presence'))this.event(null,'presence',this.environment.presence??false);
    if(changed('mode'))this.event(null,'mode',this.environment.mode??'day');
    if(changed('attention'))this.event(null,'attention',this.environment.attention??'AWAKE');
    if(changed('systemSimulation')){
      for(const i of this.active.values())void this.load(i.pkg,i);
    }if(persist)this.emit({type:'environmentPatch',value:environment,...(replace?{replace:true}:{})});this.publish();
  }
  async systemUpdate(id:string,method:string){
    const i=this.active.get(id);if(!i||!['update.check','update.download','update.install'].includes(method))return;
    this.require(i,'system.update',10);
    const result=await this.simulatedOperation(i,method,()=>{this.environment.update={...this.environment.update,phase:method==='update.check'?'available':method==='update.download'?'ready':'up_to_date'};});
    this.event(null,'update');this.emit({type:'updateOpen',id,status:this.host(i,'update.status',[],0),error:result[0]?null:result[1]});
  }
  private publish(){
    this.emit({type:'state',plugins:Object.fromEntries([...this.active].filter(([,i])=>i.live).map(([id,i])=>{
      const uses:Obj={};for(const alias of Object.keys(i.pkg.manifest.uses??{})){const result=this.resolveUse(i,alias);if(!result.error)uses[alias]=result.value;
        const key=id+':'+alias,signature=json(result);if(this.dataSeen.get(key)!==signature){this.dataSeen.set(key,signature);if(!this.dataTimers.has(key))this.dataTimers.set(key,setTimeout(()=>{this.dataTimers.delete(key);if(this.own(i))this.event(id,'data',alias);},1000));}}
      return [id,{state:clone(i.state),uses,config:clone(i.config),granted:[...i.grants],generation:i.generation,digest:i.pkg.digest}];})),settings:clone(this.settings),environment:clone(this.environment)});
  }
  private resolveUse(i:Instance,alias:string):Obj{
    const use=i.pkg.manifest.uses?.[alias];if(!use)return {error:'undeclared'};
    const [owner,key]=use.export.split('.'),provider=this.active.get(owner),declaration=provider?.pkg.manifest.exports?.[key];
    if(!provider?.live||!declaration)return {error:'missing'};if(declaration.shape!==use.shape)return {error:'shape_mismatch'};
    if((declaration.requires??[]).some((p:string)=>!i.grants.has(p)))return {error:'permission_required'};
    const value=path(provider.state,declaration.path);if(value===null||value===undefined)return {error:'empty'};
    const encoded=json(value);if(new TextEncoder().encode(encoded).length>65536||(encoded.match(/[\[{]/g)?.length??0)>4096)return {error:'too_large'};
    return {value:clone(value),text:encoded};
  }
  private require(i:Instance,permission:string,api=1){if(!i.grants.has(permission)||i.pkg.manifest.api<api)throw Error(`${permission} permission required (API ${api})`);}
  private tap(i:Instance){if(performance.now()-i.tap>=5000)throw Error('operation answers a tap');}
  private fresh(tap:number|null,grace=5000){const now=performance.now();return grace>=1&&grace<=5000&&tap!==null&&tap>=0&&now>=tap&&now-tap<=grace;}
  private updateTap(i:Instance){if(!this.fresh(i.updateTap))throw Error('plugins operation answers a tap');}
  private async simulatedOperation(i:Instance,method:string,apply:()=>void):Promise<any[]>{
    const fixture=this.environment.operationResults?.[method];if(!fixture)return [false,'unavailable'];
    if(fixture.delayMs)await new Promise(resolve=>setTimeout(resolve,Number(fixture.delayMs)));
    if(!this.own(i))return [false,'cancelled'];if(!fixture.ok)return [false,fixture.error??'unavailable'];apply();return [true];
  }
  private schedule(i:Instance,task:number,action:()=>Promise<any[]>){
    if(!task||i.pending.size>=8||[...this.active.values(),...this.candidates.values()].reduce((n,x)=>n+x.pending.size,0)>=32)throw Error('async task limit');
    i.pending.add(task);
    void Promise.resolve().then(()=>this.own(i)?action():[null,'cancelled']).catch(e=>[null,String(e)]).then(values=>{
      if(!this.own(i)||!i.pending.delete(task)||!i.vm)return;
      const startup=!i.committed&&i.startTask===task;
      try{this.handle(i,i.vm.resume(task,values),startup);}catch(e){if(startup)this.fail(i,e);else this.log(i.pkg.id,'error',String(e));}
      this.publish();
    });return null;
  }
  private format(ms:number,pattern:string,zone?:string){
    return formatTime(ms,pattern,zone,this.environment.locale??'zh-Hans',this.environment.hour24!==false,this.home());
  }
  private home(){return homeZone(this.environment.timezone??'Asia/Hong_Kong',this.place().timezone);}
  private label(i:Instance,text:string){return text?.startsWith('@@')?text.slice(1):text?.startsWith('@')?formatString(i.strings,text.slice(1),{},this.environment.locale??'zh-Hans'):text;}
  private place(){return clone(this.environment.location??locationFromSettings(this.settings,this.environment.timezone??'Asia/Hong_Kong'));}
  private describe(scope:string){
    const i=this.active.get(scope)??this.candidates.get(scope);const system=scope==='system';if(!system&&!i)return null;
    const label=(owner:string,value:string)=>{
      if(!value?.startsWith('@'))return value;
      const table=this.session.systemStrings?.[this.environment.locale??'zh-Hans']??this.session.systemStrings??{};
      return owner==='system'?translate(table,value.slice(1).replaceAll('.','_')):this.label(i!,value);
    };
    const systemScope:Scope={id:'system',name:'ThinkOS',fields:this.session.settings.fields,values:this.settings,overrides:this.session.profileSettings?.system??{}};
    const target:Scope=system?systemScope:{id:scope,name:this.label(i!,i!.pkg.manifest.name??scope),fields:i!.pkg.manifest.settings??{},values:i!.config,overrides:this.session.profileSettings?.plugins?.[scope]??{},related:i!.pkg.manifest.related};
    return describeSettings(target,systemScope,this.session.settings.sections??[],this.environment.locale??'zh-Hans',label,this.place());
  }
  private setSettings(scope:string,patch:Obj,persist=true){
    const target=this.active.get(scope)??this.candidates.get(scope);const system=scope==='system';const fields=system?this.session.settings.fields:target?.pkg.manifest.settings;
    if(!fields)return {error:{code:'unknown',message:'Unknown setting scope',args:{}}};
    try{patch=normalizeSettingsPatch(fields,patch,system?this.settings:target!.config,system?this.session.settings.constraints??[]:[]);}
    catch(e){return {error:{code:e instanceof SettingValidationError?e.code:'invalid',message:String(e),args:e instanceof SettingValidationError?e.args:{}}};}
    if(system)Object.assign(this.settings,patch);else{Object.assign(target!.config,patch);queueMicrotask(()=>this.event(scope,'config',target!.config));}
    this.session.profileSettings??={system:{},plugins:{}};
    if(system)Object.assign(this.session.profileSettings.system,patch);else Object.assign(this.session.profileSettings.plugins[scope]??=(Object.create(null)),patch);
    if(system)queueMicrotask(()=>{if(Object.keys(patch).some(k=>k.startsWith('units.')))this.event(null,'units',resolvedUnits(this.settings));if(Object.keys(patch).some(k=>k.startsWith('location.')))this.event(null,'location',this.place());if(Object.keys(patch).some(k=>k.startsWith('apps.')))this.event(null,'apps');});
    if(persist)this.emit({type:'settings',scope,patch});this.publish();return {value:clone(patch)};
  }
  private host(i:Instance,method:string,args:any[],task:number):any{
    const stopping=this.stopping.has(i);
    if(stopping){
      if(!['config','state.get','state.set','store.get','store.set','log','module','i18n.t','locale','time.now','time.format','tz.home','tz.info','tz.zones','units.get','units.temperature','units.wind','location.get','astro.seasons','timer.cancel','onboarding.stop','back.intercept'].includes(method))throw Error('instance stopping: '+method);
    }else if(!this.own(i))throw Error('instance stopped');
    // Secret settings, storage and request headers never enter developer trace records.
    const privateArgs=/^(?:log|store\.|state\.set|config|settings\.(?:set|setMany)|http\.|media\.load)/.test(method);
    this.emit({type:'trace',id:i.pkg.id,method,args:privateArgs?[]:args.slice(0,1),at:this.now()});
    if((i.backSlice||task!==0&&i.backTask===task)&&!['log','i18n.t','state.get','config','locale','module','units.get','units.temperature','units.wind','update.status','onboarding.status','device.mode','device.canSetLocale','device.canSetTimeZone','tz.zones','plugins.installed','plugins.catalog'].includes(method))this.emit({type:'backAnswered',id:i.pkg.id});
    const [a,b,c]=args;
    const number=(value:any)=>{if(typeof value!=='number'&&typeof value!=='bigint'&&!(value instanceof Number))throw Error('expected number');const result=Number(value);if(!Number.isFinite(result))throw Error('expected finite number');return result;};
    switch(method){
      case 'config':return clone(i.config);
      case 'state.get':{const key=String(a??'');if(key==='media'||key.startsWith('media.')){this.require(i,'media');return clone(path(this.media,key==='media'?'':key.slice(6)));}return clone(path(i.state,key));}
      case 'state.set':{const patch=typeof a==='string'?parseJson(a,262144):a;if(!record(patch)||Object.hasOwn(patch,'config')||Object.hasOwn(patch,'media')||(i.pkg.manifest.api>=6&&Object.hasOwn(patch,'uses')))throw Error('reserved or invalid state patch');i.state=merge(i.state,patch);if(i.committed&&!stopping)this.publish();return null;}
      case 'store.get':return clone(this.storeOwner(i).store[String(a)]??null);
      case 'store.set':{
        if(typeof a!=='string'||!a.length||a.length>128)throw Error('invalid store key');const owner=this.storeOwner(i);owner.store=merge(owner.store,{[a]:b});i.store=owner.store;
        if(owner.committed&&!stopping)this.emit({type:'store',id:owner.pkg.id,digest:owner.pkg.digest,value:owner.store});return null;
      }
      case 'module':{
        if(typeof a!=='string'||a.length>128||!/^[-\w.]+$/.test(a)||a.includes('..'))throw Error('invalid module name');const source=this.source(i.pkg,a.replaceAll('.','/')+'.lua');if(new TextEncoder().encode(source).length>262144)throw Error('module exceeds 256 KiB');return source;
      }
      case 'log':this.log(i.pkg.id,String(a),String(b));return null;
      case 'locale':return localeInfo(this.environment.locale??'zh-Hans',this.environment.hour24!==false);
      case 'i18n.t':return formatString(i.strings,a,record(b)?b:{},this.environment.locale??'zh-Hans');
      case 'time.now':return this.now()>=1577836800000?Math.trunc(this.now()):null;
      case 'time.format':return this.format(number(a),b??'@time',c);
      case 'tz.home':return this.home();
      case 'tz.info':return zoneInfo(a,this.now(),this.environment.locale??'zh-Hans');
      case 'tz.zones':if(i.pkg.manifest.api<10)throw Error('tz.zones requires API 10');return zonesForRegion(a,this.now(),this.environment.locale??'zh-Hans',this.environment.hour24!==false);
      case 'units.get':return resolvedUnits(this.settings);
      case 'units.temperature':return new Number(temperature(a,this.settings));
      case 'units.wind':return new Number(wind(a,this.settings));
      case 'location.get':this.require(i,'location');return this.place();
      case 'astro.sun':case 'astro.moon':this.require(i,'location');return this.schedule(i,task,async()=>[this.environment[method]??(a==null?null:(method==='astro.sun'?sun:moon)(number(a),b??this.place(),b?.timezone||this.home()))]);
      case 'astro.seasons':return seasons(number(a));
      case 'timer.add':{
        if(i.timers.size>=64)throw Error('timer limit');const seconds=Number(b);if(!Number.isFinite(seconds)||seconds<0.05||seconds>2592000)throw Error('invalid timer interval');
        const id=Number(a),repeat=Boolean(c),delay=Math.trunc(seconds*1000);clearTimeout(i.timers.get(id));
        const schedule=(deferred?:number)=>{
          const duration=deferred??Math.max(1,args[3]?delay-this.now()%delay:delay);
          const timer=setTimeout(()=>{
            if(!this.own(i)||!i.vm||i.timers.get(id)!==timer)return;
            if(!i.committed){schedule(50);return;}
            if(!repeat)i.timers.delete(id);
            this.handle(i,i.vm.start('','@timer','timer',[id,repeat]));
            if(repeat&&this.own(i)&&i.timers.get(id)===timer)schedule();this.publish();
          },duration);i.timers.set(id,timer);
        };schedule();return null;
      }
      case 'timer.cancel':clearTimeout(i.timers.get(Number(a)));i.timers.delete(Number(a));return null;
      case 'sleep':if(!Number.isFinite(Number(a))||a<0.001||a>3600)throw Error('invalid sleep interval');return this.schedule(i,task,()=>new Promise(resolve=>setTimeout(()=>resolve([]),Math.trunc(Number(a)*1000))));
      case 'http.cancel': {
        this.require(i, 'network', 10);
        const cancel = this.httpTags.get(i)?.get(a);
        if (cancel) cancel();
        return Boolean(cancel);
      }
      case 'http.get':case 'http.post': {
        this.require(i, 'network');
        const options = (method === 'http.get' ? b : c) ?? {};
        const {tag, timeout_ms:timeoutMs, max_bytes:maxBytes, same_origin:sameOrigin} = options;
        if (timeoutMs !== undefined && (!Number.isInteger(timeoutMs) || timeoutMs < 500 || timeoutMs > 10000)) throw Error('Invalid HTTP timeout');
        if (maxBytes !== undefined && (!Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > 1048576)) throw Error('Invalid HTTP byte limit');
        let tags = this.httpTags.get(i);
        if (!tags) this.httpTags.set(i, tags = new Map());
        if (tag !== undefined && (i.pkg.manifest.api < 10 || typeof tag !== 'string' || !/^[a-zA-Z0-9_-]{1,48}$/.test(tag) || tags.has(tag))) throw Error('Invalid or active HTTP tag');
        let cancel: () => void;
        const cancelled = new Promise<any[]>(resolve => { cancel = () => resolve([null,'HTTP cancelled']); });
        const result = this.schedule(i, task, async () => {
          const request = async () => {
            if (this.environment.networkMode === 'fixture') return this.environment.http?.[a] ? [this.environment.http[a]] : [null,'no HTTP fixture'];
            const reply = await this.rpc('http', {pluginId:i.pkg.id, digest:i.pkg.digest, url:a, method:method==='http.get'?'GET':'POST',
              ...(method==='http.post'?{body:String(b)}:{}), headers:options.headers??{}, timeoutMs,maxBytes,sameOrigin});
            return [reply];
          };
          try { return await Promise.race([request(),cancelled]); }
          finally { if (tag !== undefined && tags.get(tag) === cancel) tags.delete(tag); }
        });
        if (tag !== undefined) tags.set(tag, cancel!);
        return result;
      }
      case 'net.resolve':this.require(i,'network',10);return this.schedule(i,task,async()=>[(await this.rpc('dns',{pluginId:i.pkg.id,digest:i.pkg.digest,host:a})).entries]);
      case 'settings.list':this.require(i,'system.settings');return [{scope:'system',name:'ThinkOS',count:Object.values(this.session.settings.fields).filter((f:any)=>!f.hidden&&f.level!=='hidden').length},...[...this.active.values()].filter(x=>x.pkg.manifest.settings||x.pkg.manifest.related).map(x=>({scope:x.pkg.id,name:this.label(x,x.pkg.manifest.name),count:Object.values(x.pkg.manifest.settings??{}).filter((f:any)=>!f.hidden&&f.level!=='hidden').length+(x.pkg.manifest.related?.length??0)}))];
      case 'settings.get':this.require(i,'system.settings');return this.describe(a);
      case 'settings.set':{this.require(i,'system.settings');const result=this.setSettings(a,{[b]:c});return result.error?result:{value:result.value[b]};}
      case 'settings.setMany':this.require(i,'system.settings');return this.setSettings(a,b);
      case 'settings.reset':{
        this.require(i,'system.settings');const fields=a==='system'?this.session.settings.fields:this.active.get(a)?.pkg.manifest.settings??{},patch=b?{[b]:fields[b]?.default}:defaults(fields),result=this.setSettings(a,patch,false);if(result.error)return result;
        const overrides=a==='system'?this.session.profileSettings.system:this.session.profileSettings.plugins[a];for(const key of Object.keys(patch))delete overrides[key];
        this.emit({type:'settingsReset',scope:a,key:b??null});return null;
      }
      case 'settings.open':this.tap(i);if(typeof a!=='string'||a!=='system'&&!this.active.has(a)||b!=null&&(typeof b!=='string'||!/^[-a-z0-9_]{1,64}$/.test(b)))throw Error('unknown settings scope or section');this.emit({type:'navigate',action:'settings',scope:a,section:b});return null;
      case 'settings.about':this.require(i,'system.settings',9);this.emit({type:'notice',message:'ThinkOS Simulator · MIT'});return null;
      case 'page.focus':case 'page.release':{
        const page=String(a).replace(new RegExp('^'+i.pkg.id+'\\.'),'');const declared=i.pkg.manifest.pages?.find((p:Obj)=>p.id===page);
        if(!declared||(b&&!declared.live))throw Error('page does not belong to plugin');this.emit({type:'navigate',origin:'page',action:method==='page.focus'?'focus':'release',page:i.pkg.id+'.'+page,live:b===true,answersTap:performance.now()-i.tap<5000});return null;
      }
      case 'back.intercept':i.intercept=!stopping&&a===true;if(!stopping)this.emit({type:'backIntercept',id:i.pkg.id,on:i.intercept});return null;
      case 'tile.animate':{
        if(!['pulse','flip','nudge'].includes(a))return {error:'unknown'};
        const key='tile:'+a,previous=i.commandTimes.get(key);if(previous!==undefined&&performance.now()-previous<1000)return {error:'cooldown'};
        i.commandTimes.set(key,performance.now());this.emit({type:'tile',id:i.pkg.id,name:a});return {ok:true};
      }
      case 'data.get':{
        const result=this.resolveUse(i,a);return result.error?{error:result.error}:{text:result.text};
      }
      case 'command.call':{
        if(!i.pkg.manifest.calls?.includes(a))return {error:'undeclared'};
        try{bounded(b??null,4096);}catch{return {error:'too_large'};}
        const [owner,key]=String(a).split('.'),target=this.active.get(owner),command=target?.pkg.manifest.commands?.find((x:Obj)=>x.id===key&&x.public);
        if(!target?.live||!target.vm)return {error:'unavailable'};if(!command)return {error:'not_public'};const previous=target.commandTimes.get(key);
        if(previous!==undefined&&performance.now()-previous<(command.cooldown??1)*1000)return {error:'cooldown'};
        target.commandTimes.set(key,performance.now());queueMicrotask(()=>this.event(owner,'command',key,b??null,'plugin',i.pkg.id));return {ok:true};
      }
      case 'device.mode':if(i.pkg.manifest.api<10)throw Error('device.mode requires API 10');return this.environment.mode??'day';
      case 'device.get':this.require(i,'system.device');return {wifi:{enabled:this.environment.wifi!==false,connected:this.environment.wifi!==false,...(this.environment.wifi!==false?{ssid:'Simulator',level:4,ip:'127.0.0.1'}:{})},online:this.environment.wifi!==false,muted:this.environment.muted!==false,brightness:Math.round(this.environment.brightness??this.settings['display.brightness_max']??85),volume:Math.round(this.environment.volume??0)};
      case 'device.wifi':case 'device.mute':this.require(i,'system.device');this.environment[method==='device.wifi'?'wifi':'muted']=a===true;queueMicrotask(()=>this.event(null,'device'));return null;
      case 'device.setMode':{if(i.pkg.manifest.api<10)throw Error('device mode requires API 10');const tap=i.onboardingTap;return this.schedule(i,task,async()=>{this.require(i,'system.device',10);if(!['day','redshift','sleep'].includes(a))return [null,'unknown device mode'];if(!this.fresh(tap))return [null,'device mode needs a recent touch'];this.configure({mode:a});return [true];});}
      case 'device.openAndroid':this.require(i,'system.device');if(!['wifi','settings','locale','date','accessibility'].includes(a)||!['wifi','settings'].includes(a)&&i.pkg.manifest.api<10)throw Error('unknown or unavailable Android screen');this.emit({type:'notice',message:'模拟 Android 页面：'+a});return null;
      case 'device.canSetLocale':case 'device.canSetTimeZone':case 'device.canPowerOff':this.require(i,'system.device',10);return this.schedule(i,task,async()=>[this.environment.capabilities?.[method==='device.canSetLocale'?'locale':method==='device.canSetTimeZone'?'timeZone':'powerOff']!==false]);
      case 'device.setLocale':case 'device.setTimeZone':case 'device.powerOff':{this.require(i,'system.device',10);const tap=i.onboardingTap;return this.schedule(i,task,async()=>{const capability=method==='device.setLocale'?'locale':method==='device.setTimeZone'?'timeZone':'powerOff';if(!this.fresh(tap)||this.environment.capabilities?.[capability]===false)return [false];if(method==='device.powerOff')this.emit({type:'notice',message:'虚拟设备已关机'});else{if(method==='device.setTimeZone'&&!canonicalZone(a))return [false];this.configure({[method==='device.setLocale'?'locale':'timezone']:a});}return [true];});}
      case 'panel.close':this.require(i,'system.device');this.emit({type:'navigate',action:'back'});return null;
      case 'cast.status':this.require(i,'media');return {name:'ThinkOS Simulator',dlna:false,airplay:false,mirror:false,miracast:false,miracastModule:false,...this.environment.cast};
      case 'onboarding.status':this.require(i,'system.settings',10);return {version:1,active:false,updater:false,greeting_seconds:this.settings['onboarding.greeting_s'],...this.environment.onboarding};
      case 'onboarding.open':this.require(i,'system.settings',10);if(!this.fresh(i.onboardingTap))throw Error('onboarding.open answers a tap');if(!['restart','resume'].includes(a))throw Error('unknown onboarding mode');this.emit({type:'navigate',origin:'onboarding',mode:a,action:'focus',page:'welcome.hello'});return true;
      case 'onboarding.practice':case 'onboarding.stop':this.require(i,'system.settings',10);if(!i.pkg.manifest.pages?.some((p:Obj)=>p.roles?.includes('onboarding'))||method==='onboarding.practice'&&(!['swipe','variant','panel','back','home','long-press'].includes(a)||typeof b==='string'&&b.length>200))throw Error('invalid onboarding practice');if(!stopping)this.emit({type:'practice',id:i.pkg.id,name:method==='onboarding.stop'?null:a,text:b});return true;
      case 'update.status':this.require(i,'system.update',10);return {schema:1,phase:'unavailable',channel:'stable',current_version:this.session.buildInfo?.version??'',current_code:this.session.buildInfo?.code??0,configured:false,can_check:false,can_download:false,can_install:false,downloaded:0,size:0,components:[],...this.environment.update};
      case 'update.open':{this.require(i,'system.update',10);const tap=i.updateTap;return this.schedule(i,task,async()=>{if(!this.fresh(tap,Number(this.settings['update.manual_grace_s']??5)*1000))return [null,'tap'];this.emit({type:'updateOpen',id:i.pkg.id,status:this.host(i,'update.status',[],0)});return [true];});}
      case 'update.check':case 'update.download':case 'update.install':{this.require(i,'system.update',10);const tap=i.updateTap;return this.schedule(i,task,async()=>{if(!this.fresh(tap,Number(this.settings['update.manual_grace_s']??5)*1000))return [null,'tap'];return this.simulatedOperation(i,method,()=>{this.environment.update={...this.environment.update,phase:method==='update.check'?'available':method==='update.download'?'ready':'up_to_date'};queueMicrotask(()=>this.event(null,'update'));});});}
      case 'plugins.installed':this.require(i,'system.plugins',10);return clone(this.environment.registryInstalled??[]);
      case 'plugins.catalog':this.require(i,'system.plugins',10);return this.schedule(i,task,async()=>{const catalog=this.environment.catalog??{},offset=Math.max(0,Number(a?.offset??0)),limit=Math.min(50,Math.max(1,Number(a?.limit??50))),items=catalog.items??[];return [{total:items.length,offset,configured:!!this.environment.catalog,refreshing:false,error:'',expired:false,...catalog,items:items.slice(offset,offset+limit)}];});
      case 'plugins.setAuto':{this.require(i,'system.plugins',10);this.updateTap(i);const entry=this.environment.registryInstalled?.find((x:Obj)=>x.id===a);if(!entry)return {error:'unavailable'};entry.auto=b===true;return {value:true};}
      case 'plugins.open':case 'plugins.install':case 'plugins.update':case 'plugins.uninstall':this.require(i,'system.plugins',10);this.updateTap(i);return this.schedule(i,task,()=>this.simulatedOperation(i,method,()=>{
        const records=this.environment.registryInstalled??=[],index=records.findIndex((x:Obj)=>x.id===a);
        if(method==='plugins.uninstall'){if(index>=0)records.splice(index,1);}
        else if(method!=='plugins.open'){
          const entry=this.environment.catalog?.items?.find((x:Obj)=>x.id===a);
          if(entry){
            // RegistryInstalledRecord is a separate closed contract, not a catalog item.
            const installed={id:entry.id,version:entry.version,digest:entry.digest,repo:entry.repo,commit:entry.commit,tier:entry.tier,name:entry.name,
              auto:index>=0?records[index].auto:true,enabled:true,revoked:false,update:false,error:'',available_version:entry.version,apps:[]};
            if(index>=0)records[index]=installed;else records.push(installed);
          }
        }
        queueMicrotask(()=>this.event(null,'plugins'));
      }));
      case 'apps.list':this.require(i,'system.apps',9);return this.schedule(i,task,async()=>[json([...this.active.values()].flatMap(x=>(x.pkg.manifest.apps??[]).filter((app:Obj)=>app.listed!==false).map((app:Obj)=>({id:x.pkg.id+'.'+app.page,title:this.label(x,app.title).slice(0,80),kind:'page',icon:app.icon??'',image:''}))).concat(this.environment.androidApps??[]).sort((a,b)=>a.title.localeCompare(b.title,this.environment.locale??'zh-Hans')))]);
      case 'apps.open':this.require(i,'system.apps',9);return this.schedule(i,task,async()=>{if(a==='launcher'){this.emit({type:'navigate',origin:'apps',launcher:true,answersTap:true,action:'focus',page:'apps.main'});return [true];}const [owner,key]=String(a).split('.');const target=this.active.get(owner),app=target?.pkg.manifest.apps?.find((p:Obj)=>p.page===key);if(!app){const virtual=this.environment.androidApps?.find((p:Obj)=>p.id===a);if(virtual){this.emit({type:'notice',message:'虚拟 Android 应用：'+virtual.title});return [true];}return [false,'unavailable'];}this.emit({type:'navigate',origin:'apps',answersTap:true,action:'focus',page:owner+'.'+app.page});return [true];});
      case 'library.list':return this.schedule(i,task,async()=>{
        try{this.require(i,'library');if(!record(a))throw Error('Library options must be a table');return [json(this.environment.library??await this.rpc('library',{pluginId:i.pkg.id,digest:i.pkg.digest,options:a}))];}
        catch(error){return [null,error instanceof Error?error.message:String(error)];}
      });
      default:
        if(method.startsWith('media.')){
          return this.schedule(i,task,async()=>{
            try{
              this.require(i,'media');
              const options=(value:any,label:string):Obj=>{if(value===null||value===undefined)return {};if(!record(value))throw Error(label+' must be a table');return value;};
              const publicOnly=(value:Obj,probe=false)=>{
                if(!Object.hasOwn(value,'public_only'))return;
                if(i.pkg.manifest.api<10)throw Error((probe?'media.probe.public_only':'media.public_only')+' requires a newer plugin API');
                if(typeof value.public_only!=='boolean')throw Error('media.public_only must be a boolean');
              };
              if(method==='media.probe'){
                this.require(i,'network');if(typeof a!=='string')throw Error('Probe address must be text');
                const fields=options(b,'Probe options');if(Object.keys(fields).some(key=>!['headers','public_only'].includes(key)))throw Error('Unknown probe option');publicOnly(fields,true);
              }else if(method==='media.load'){
                if(!Array.isArray(a)||a.length<1||a.length>512)throw Error('Media queue must contain 1..512 items');
                for(const item of a){
                  if(!record(item))throw Error('Each media queue item must be a table');publicOnly(item);
                  if(item.subtitles!==undefined&&item.subtitles!==null&&!Array.isArray(item.subtitles))throw Error('media.subtitles must be an array');
                  const subtitles=item.subtitles??[];if(subtitles.length>16)throw Error('At most 16 side-loaded subtitle tracks');
                  if(subtitles.some((subtitle:unknown)=>!record(subtitle)))throw Error('Each subtitle must be a table');
                  for(const url of [item.url,...subtitles.map((subtitle:Obj)=>subtitle.url)]){
                    if(typeof url!=='string')throw Error('Media URL must be text');
                    const scheme=url.startsWith('/')?'file':/^([a-z][a-z0-9+.-]*):/.exec(url)?.[1];
                    if(['http','https','rtsp'].includes(scheme??''))this.require(i,'network');
                    if(['file','content'].includes(scheme??''))this.require(i,'library');
                  }
                }
                const fields=options(b,'Media options');if(Object.keys(fields).some(key=>!['index','shuffle','repeat','autoplay','volume','start'].includes(key)))throw Error('Unknown media option');
                for(const key of ['shuffle','autoplay'])if(fields[key]!==undefined&&fields[key]!==null&&typeof fields[key]!=='boolean')throw Error(`Media ${key} must be a boolean`);
                if(fields.index!==undefined&&fields.index!==null){const index=number(fields.index);if(!Number.isInteger(index)||index<1||index>a.length)throw Error('Media index out of range');}
                if(fields.start!==undefined&&fields.start!==null){const start=number(fields.start);if(start<0||start>2147483647000)throw Error('Media start must be nonnegative milliseconds');}
                if(fields.volume!==undefined&&fields.volume!==null){const volume=number(fields.volume);if(volume<0||volume>1)throw Error('Media volume must be 0..1');}
                if(fields.repeat!==undefined&&fields.repeat!==null&&!['off','one','all'].includes(fields.repeat))throw Error('Media repeat must be off, one or all');
              }else{
                if(!['media.play','media.pause','media.toggle','media.next','media.prev','media.seek','media.volume','media.stop','media.shuffle','media.repeat','media.jump','media.live','media.audioTrack','media.textTrack'].includes(method))throw Error('Unavailable media API');
                if(method==='media.seek'){const seek=number(a);if(seek<0||seek>9223372036854775807)throw Error('Media position must be nonnegative milliseconds');}
                if(method==='media.volume'){const volume=number(a);if(volume<0||volume>1)throw Error('Media volume must be 0..1');}
                if(method==='media.shuffle'&&typeof a!=='boolean')throw Error('Media shuffle must be a boolean');
                if(method==='media.repeat'&&!['off','one','all'].includes(a))throw Error('Media repeat must be off, one or all');
                if(['media.jump','media.audioTrack','media.textTrack'].includes(method)){const index=number(a);if(!Number.isInteger(index)||index<0||index>512)throw Error('Media index must be a whole number');}
              }
              return await this.rpc('media',{pluginId:i.pkg.id,digest:i.pkg.digest,generation:i.generation,permissions:[...i.grants],method,args});
            }catch(error){return [null,(error instanceof Error?error.message:String(error)).replace(/(?<=:\/\/)[^/@\s]+@/g,'')];}
          });
        }throw Error('Unavailable API: '+method);
    }
  }
}
