import shaka from 'shaka-player/dist/shaka-player.compiled.js';
import type {Obj} from './values.ts';
type Api=(name:string,args:Obj,binary?:boolean,signal?:AbortSignal)=>Promise<any>;
/** The simulator has exactly one playback session; every URL is resolved by the gateway. */
export class Media {
  readonly video=document.createElement('video');
  private player:any; private owner:Obj|null=null; private queue:Obj[]=[]; private index=0;
  private operation=0;private error:string|null=null;private repeat='off';private shuffle=false;
  private order:number[]=[];private requests=new Set<AbortController>();
  private loading:{request:Obj;transaction:number}|null=null;
  private sleeping=false;
  constructor(private api:Api,private changed:(state:Obj)=>void,private event?:(owner:Obj,name:string,payload:Obj)=>void){
    this.video.playsInline=true;this.video.muted=true;this.video.preload='metadata';
    for(const event of ['play','pause','timeupdate','loadedmetadata','waiting','ended','error'])this.video.addEventListener(event,()=>{
      if(event==='error'){this.error=this.video.error?.message||'Browser cannot decode this media';if(this.owner)this.event?.(this.owner,'media.error',this.errorState()!);}
      this.changed(this.snapshot());
      if(event==='ended')void this.advance(false);
    });
    shaka.polyfill.installAll();
    shaka.net.NetworkingEngine.registerScheme('http',this.network.bind(this),shaka.net.NetworkingEngine.PluginPriority.APPLICATION);
    shaka.net.NetworkingEngine.registerScheme('https',this.network.bind(this),shaka.net.NetworkingEngine.PluginPriority.APPLICATION);
    this.player=new shaka.Player();
    this.player.addEventListener('error',(event:any)=>{this.error=String(event.detail?.message??event.detail?.code).slice(0,2048);if(this.owner)this.event?.(this.owner,'media.error',{...this.errorState(),category:event.detail?.category===shaka.util.Error.Category.NETWORK?'network':'decoder'});this.changed(this.snapshot());});
  }
  private network(uri:string,request:any,_type:any,progress?:any){
    const controller=new AbortController();this.requests.add(controller);const owner=this.owner?{...this.owner}:null,item=this.queue[this.index];
    const promise=(async()=>{
      if(!owner)throw Error('media session missing');
      const headers={...item?.headers,...request.headers},rangeKey=Object.keys(headers).find(k=>k.toLowerCase()==='range'),range=rangeKey?headers[rangeKey]:undefined;if(rangeKey)delete headers[rangeKey];
      const response=await this.api('resource',{pluginId:owner.pluginId,digest:owner.digest,url:uri,originUrl:item.url,kind:'media',headers,...(range?{range}:{}),publicOnly:item.public_only===true},true,controller.signal) as Response;
      if(controller.signal.aborted)throw Error('media request cancelled');const data=await response.arrayBuffer();
      progress?.(0,data.byteLength,0);
      return {uri:response.headers.get('X-ThinkOS-Source-URL')??uri,originalUri:uri,data,headers:Object.fromEntries(response.headers.entries()),timeMs:0};
    })().finally(()=>this.requests.delete(controller));
    return new shaka.util.AbortableOperation(promise,()=>{controller.abort();return Promise.resolve();});
  }
  snapshot():Obj{
    const item=this.queue[this.index]??{};const duration=Number.isFinite(this.video.duration)?this.video.duration*1000:0;
    const position=Number.isFinite(this.video.currentTime)?this.video.currentTime*1000:0;
    return {status:!this.owner?'stopped':this.error?'error':this.video.ended?'ended':this.video.readyState<2?'buffering':this.video.paused?'paused':'playing',owner:this.owner?.pluginId??'',kind:item.kind??'audio',index:this.owner?this.index+1:0,count:this.queue.length,
      title:item.title??'',artist:item.artist??'',album:item.album??'',art:item.art??'',queue:this.queue.map(({headers,subtitles,...entry})=>entry),position,duration,progress:duration>0?position/duration:0,
      volume:this.video.volume,shuffle:this.shuffle,repeat:this.repeat,error:this.errorState(),warning:null,retry:0,videoWidth:this.video.videoWidth,videoHeight:this.video.videoHeight,pixelRatio:1,source:item.type==='auto'||!item.type?(/\.m3u8(?:\?|$)/i.test(item.url??'')?'hls':/\.mpd(?:\?|$)/i.test(item.url??'')?'dash':'progressive'):item.type,
      live:this.player?.isLive?.()??false,mirror:false,seekable:duration>0,buffered:this.video.buffered.length?this.video.buffered.end(this.video.buffered.length-1)*1000:0,liveOffset:0,audioTracks:this.tracks('audio'),textTracks:this.tracks('text')};
  }
  private errorState(){return this.error?{code:0,name:'browser',category:'decoder',message:this.error,codec:'',index:this.index+1}:null;}
  setMode(mode:string){const sleeping=mode==='sleep';if(sleeping&&!this.sleeping){++this.operation;this.loading=null;this.video.pause();}this.sleeping=sleeping;this.changed(this.snapshot());}
  private tracks(kind:string){return ((kind==='audio'?this.player?.getAudioTracks?.():this.player?.getTextTracks?.())??[]).map((t:Obj,index:number)=>({index:index+1,language:t.language??'',label:t.label??t.language??'',selected:t.active===true}));}
  private reorder(){this.order=this.queue.map((_,index)=>index);if(this.shuffle){const rest=this.order.filter(index=>index!==this.index);for(let n=rest.length-1;n>0;n--){const j=Math.floor(Math.random()*(n+1));[rest[n],rest[j]]=[rest[j],rest[n]];}this.order=[this.index,...rest];}}
  private async advance(manual=true){if(this.sleeping)return;let next=this.order.indexOf(this.index)+(manual||this.repeat!=='one'?1:0);if(next>=this.order.length){if(this.repeat!=='all'){if(!manual&&this.owner)this.event?.(this.owner,'queue.end',{index:this.index+1,count:this.queue.length});return;}next=0;}await this.jump(this.order[next],undefined,undefined,manual?2:this.repeat==='one'?0:1);if(!this.sleeping)await this.video.play();}
  async call(request:Obj):Promise<any[]>{
    const action=request.method.replace('media.',''),args=request.args??[];
    if(this.sleeping&&['load','play','toggle','next','prev','jump','live'].includes(action))return [null,'asleep'];
    const previous=action==='load'?{owner:this.owner,queue:this.queue,index:this.index,order:this.order,shuffle:this.shuffle,repeat:this.repeat,error:this.error,position:this.video.currentTime,volume:this.video.volume,paused:this.video.paused}:null;
    let transaction:number|undefined,changed=false;
    try{
      if(action==='probe'){
        const began=performance.now();const response=await this.api('resource',{pluginId:request.pluginId,digest:request.digest,url:args[0],kind:'media',range:'bytes=0-16383',headers:args[1]?.headers??{},publicOnly:args[1]?.public_only===true},true) as Response;
        const head=new Uint8Array(await response.arrayBuffer()),start=new TextDecoder().decode(head).trimStart(),type=response.headers.get('Content-Type')??'';
        const kind=!head.length?'empty':start.startsWith('#EXTM3U')?(start.includes('#EXT-X-')?'hls':'list'):/html/i.test(type)||/^<!doctype html|^<html/i.test(start)?'html':'stream';
        return [{ok:response.ok&&!['html','list','empty'].includes(kind),status:response.status,kind,millis:Math.round(performance.now()-began),...(['html','list','empty'].includes(kind)?{error:'format'}:{})}];
      }
      if(action==='load'){
        if(!Array.isArray(args[0])||args[0].length<1||args[0].length>512)throw Error('invalid media queue');
        const options=args[1]??{},index=Number(options.index??1);if(!Number.isInteger(index)||index<1||index>args[0].length)throw Error('queue index out of range');
        if(options.volume!==undefined&&(!Number.isFinite(Number(options.volume))||options.volume<0||options.volume>1))throw Error('volume must be 0..1');
        if(options.start!==undefined&&(!Number.isFinite(Number(options.start))||options.start<0))throw Error('start must be nonnegative');
        if(options.repeat!==undefined&&!['off','one','all'].includes(options.repeat))throw Error('invalid repeat');
        if(args[0].filter((x:Obj)=>x.type==='hls'||x.type==='dash'||/\.(m3u8|mpd)(\?|$)/i.test(x.url)).length>16)throw Error('too many adaptive queue entries');
        for(const item of args[0]){
          if(typeof item.url!=='string'||!item.url||item.url.length>8192)throw Error('invalid media URL');
          if(/^https?:/.test(item.url)&&!request.permissions.includes('network'))throw Error('network permission required');
          if(/^(file|content):|^\//.test(item.url)&&!request.permissions.includes('library'))throw Error('library permission required');
          if(/^rtsp:/i.test(item.url))throw Error('RTSP unavailable in browser; use a media fixture');
          const url=new URL(item.url.startsWith('/')?'file://'+item.url:item.url);if(url.username||url.password||url.hash)throw Error('invalid media URL');
          if(!['file:','content:','http:','https:'].includes(url.protocol))throw Error('invalid media scheme');
          if(item.kind&&!['audio','video'].includes(item.kind))throw Error('invalid media kind');
        }
        transaction=++this.operation;
        this.loading={request,transaction};
        const first=args[0][index-1],adaptive=first.type==='hls'||first.type==='dash'||/\.(m3u8|mpd)(\?|$)/i.test(first.url);
        const prepared=adaptive?undefined:await this.api('resource-ticket',{pluginId:request.pluginId,digest:request.digest,url:first.url,kind:'media',headers:first.headers??{},publicOnly:first.public_only===true});
        if(transaction!==this.operation)return [null,'cancelled'];
        changed=true;
        this.owner=request;this.queue=args[0];this.shuffle=args[1]?.shuffle??false;this.repeat=args[1]?.repeat??'off';
        this.index=index-1;this.reorder();if(options.volume!==undefined)this.video.volume=Number(options.volume);await this.jump(this.index,transaction,prepared,3);
        if(transaction!==this.operation)return [null,'cancelled'];
        if(args[1]?.start)this.video.currentTime=Number(args[1].start)/1000;
        if(args[1]?.autoplay!==false)await this.video.play();
        if(transaction!==this.operation)return [null,'cancelled'];
        if(previous?.owner&&(previous.owner.pluginId!==request.pluginId||previous.owner.generation!==request.generation))this.event?.(previous.owner,'media.session',{reason:'replaced',owner:request.pluginId});
      }else{
        if(action==='stop'){await this.stop();return [this.snapshot()];}
        if(!this.owner)throw Error('no media session');
        switch(action){
          case 'play':await this.video.play();break;case 'pause':this.video.pause();break;
          case 'toggle':if(this.video.paused)await this.video.play();else this.video.pause();break;
          case 'stop':await this.stop();break;
          case 'next':await this.advance();break;
          case 'prev':{const pos=this.order.indexOf(this.index);await this.jump(this.order[pos>0?pos-1:this.repeat==='all'?this.order.length-1:0]);await this.video.play();break;}
          case 'jump':await this.jump(Number(args[0])-1);break;
          case 'seek':this.video.currentTime=Math.max(0,Number(args[0])/1000);break;
          case 'volume':if(!Number.isFinite(Number(args[0]))||args[0]<0||args[0]>1)throw Error('volume must be 0..1');this.video.volume=Number(args[0]);break;
          case 'shuffle':this.shuffle=args[0]===true;this.reorder();break;
          case 'repeat':if(!['off','one','all'].includes(args[0]))throw Error('invalid repeat');this.repeat=args[0];break;
          case 'live':this.player.goToLive();break;
          case 'audioTrack':{const track=this.player.getAudioTracks()[Number(args[0])-1];if(!track)throw Error('audio track out of range');this.player.selectAudioTrack(track);break;}
          case 'textTrack':{if(Number(args[0])===0){this.player.setTextTrackVisibility(false);break;}const track=this.player.getTextTracks()[Number(args[0])-1];if(!track)throw Error('text track out of range');this.player.selectTextTrack(track);this.player.setTextTrackVisibility(true);break;}
          default:throw Error('unknown media operation');
        }
      }
      this.changed(this.snapshot());return [this.snapshot()];
    }catch(error){
      if(action==='probe')return [{ok:false,status:0,kind:'',millis:0,error:String(error)}];
      if(previous){
        if(changed&&transaction===this.operation){
          Object.assign(this,{owner:previous.owner,queue:previous.queue,index:previous.index,order:previous.order,shuffle:previous.shuffle,repeat:previous.repeat,error:previous.error});
          this.video.volume=previous.volume;
          try{if(previous.owner){await this.jump(previous.index,transaction);if(transaction===this.operation){this.video.currentTime=previous.position;if(!previous.paused)await this.video.play();}}else await this.stop();}catch{/* Restore the old identity even if its source is no longer reachable. */}
          if(transaction===this.operation)this.error=previous.error;
          this.changed(this.snapshot());
        }
        return [null,String(error)];
      }
      this.error=String(error);this.changed(this.snapshot());return [null,this.error];
    }finally{if(this.loading?.transaction===transaction)this.loading=null;}
  }
  private async jump(index:number,operation?:number,prepared?:Obj,reason=2){
    if(index<0||index>=this.queue.length)throw Error('queue index out of range');
    const ticket=operation??++this.operation;this.index=index;this.error=null;const item=this.queue[index],owner=this.owner!;
    this.video.pause();await this.player.unload();if(ticket!==this.operation)return;
    if(!this.player.getMediaElement())await this.player.attach(this.video);
    if(item.type==='hls'||item.type==='dash'||/\.(m3u8|mpd)(\?|$)/i.test(item.url)){
      await this.player.load(item.url);
    }else{
      const source=prepared??await this.api('resource-ticket',{pluginId:owner.pluginId,digest:owner.digest,url:item.url,kind:'media',headers:item.headers??{},publicOnly:item.public_only===true});
      if(ticket!==this.operation)return;this.video.src=source.url;this.video.load();
    }
    if(ticket===this.operation&&this.owner)this.event?.(this.owner,'media.track',{index:index+1,reason});
  }
  async stop(){++this.operation;const owner=this.owner;for(const controller of this.requests)controller.abort();this.video.pause();this.owner=null;this.queue=[];this.order=[];this.video.removeAttribute('src');this.video.load();await this.player.unload();if(owner)this.event?.(owner,'media.session',{reason:'stopped',owner:''});this.changed(this.snapshot());}
  release(id:string,generation:number){if(this.loading?.request.pluginId===id&&this.loading?.request.generation===generation){++this.operation;this.loading=null;}if(this.owner?.pluginId===id&&this.owner?.generation===generation)void this.stop();}
  unlock(){this.video.muted=false;}
}
