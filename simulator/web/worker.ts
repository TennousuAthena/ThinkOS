import {Runtime,type Session} from './runtime.ts';
import type {Obj} from './values.ts';
const scope=self as unknown as {postMessage:(data:unknown)=>void;onmessage:((event:MessageEvent)=>void)|null};
let runtime:Runtime|null=null,next=0;
let heartbeat:ReturnType<typeof setInterval>|undefined;
const pending=new Map<number,{resolve:(value:any)=>void,reject:(e:Error)=>void}>();
function rpc(name:string,args:Obj):Promise<any>{return new Promise((resolve,reject)=>{const id=++next;pending.set(id,{resolve,reject});scope.postMessage({type:'rpc',id,name,args});});}
scope.onmessage=async({data})=>{
 try{
  switch(data.type){
   case 'init':runtime?.shutdown();clearInterval(heartbeat);heartbeat=setInterval(()=>scope.postMessage({type:'heartbeat'}),data.session.developmentSettings?.worker_heartbeat_ms??1000);runtime=new Runtime(data.session as Session,value=>scope.postMessage(value),rpc);await runtime.loadAll(data.session.plugins);scope.postMessage({type:'ready'});break;
   case 'reload':if(runtime)await runtime.loadAll(data.plugins);break;
   case 'event':runtime?.input(data.id,data.event,data.args??[],data.trusted===true);break;
   case 'broadcast':runtime?.event(null,data.event,...(data.args??[]));break;
    case 'environment':runtime?.configure(data.value,false,data.replace===true);break;
   case 'systemUpdate':await runtime?.systemUpdate(data.id,data.method);break;
   case 'reply':{const call=pending.get(data.id);pending.delete(data.id);if(call)data.error?call.reject(Error(data.error)):call.resolve(data.value);break;}
   case 'shutdown':runtime?.shutdown();runtime=null;break;
  }
 }catch(error){scope.postMessage({type:'fatal',message:String(error)});}
};
