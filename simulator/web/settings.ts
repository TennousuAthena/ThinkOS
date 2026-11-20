import {type Obj} from './values.ts';
import {pluralCategory,cityName,countryName,offsetLabel,zoneType} from './locale-time.ts';
export interface Scope {id:string;name:string;fields:Obj;values:Obj;overrides:Obj;related?:string[]}
/** SettingsDescriber contract; field declarations are the original shared/plugin schema. */
export function describeSettings(scope:Scope,system:Scope,sections:Obj[],locale:string,label:(scope:string,value:string)=>string,place:Obj,now=Date.now()):Obj{
 const declarations:(readonly [Scope,string,Obj])[]=[...(scope.related??[]).filter(key=>system.fields[key]&&!system.fields[key].secret).map(key=>[system,key,system.fields[key]] as const),...Object.entries(scope.fields).map(([key,f])=>[scope,key,f] as const)];
 function equal(a:unknown,b:unknown):boolean{const numeric=(value:unknown)=>typeof value==='number'||typeof value==='bigint'||value instanceof Number;return numeric(a)&&numeric(b)?Number(a)===Number(b):a===b;}
 function enabled(owner:Scope,f:Obj,visited=new Set<string>()):boolean{
  const d=f.depends;if(!d)return true;if(visited.has(d.key))return false;visited.add(d.key);
  const target=owner.fields[d.key];if(!target||!enabled(owner,target,visited))return false;
  const value=owner.values[d.key];return Object.hasOwn(d,'not')?!equal(value,d.not):Array.isArray(d.in)?d.in.some((candidate:unknown)=>equal(value,candidate)):equal(value,d.value);
 }
 const number=new Intl.NumberFormat(locale,{maximumFractionDigits:3});
 function display(owner:Scope,f:Obj,v:any):string{
  if(f.type==='bool')return '';if(f.secret)return v?'••••':'';
  if(f.type==='number'){
   if(f.format==='duration'){if(Number(v)===0&&f.zero)return label(owner.id,f.zero);const unit=Number(v)!==0&&Number(v)%3600===0?'hour':Number(v)!==0&&Number(v)%60===0?'minute':'second',count=Number(v)/(unit==='hour'?3600:unit==='minute'?60:1),plural=pluralCategory(locale,count);return label('system','@setting.duration.'+unit+'.'+plural).replaceAll('{1}',number.format(count)).replaceAll('%1$s',number.format(count));}
   return number.format(Number(v))+(f.unit?' '+f.unit:'');
  }
  if(f.source==='place')return place.name??'';
  if(f.source==='region')return v?countryName(String(v),locale):label('system','@form.region.all');
  if(f.source==='timezone')return v?cityName(String(v))+'  '+offsetLabel(zoneType(String(v),now).offset):'';
  const choice=f.choices?.find((c:Obj)=>c.value===v);return choice?label(owner.id,choice.label):String(v??'');
 }
 const fields=declarations.filter(([owner,,f])=>!f.hidden&&f.level!=='hidden'&&enabled(owner,f)).map(([owner,key,f])=>{
  const v=owner.values[key],entry:Obj={key,scope:owner.id,type:f.type,level:f.level??'normal',label:label(owner.id,f.label??key),value:f.secret?'':v,default:f.secret?'':f.default,overridden:Object.hasOwn(owner.overrides,key),display:display(owner,f,v)};
  for(const k of ['source','section','allowEmpty','min','max','step','unit','format','secret','maxLength'])if(f[k]!==undefined)entry[k]=f[k];
  for(const k of ['help','placeholder','group'])if(f[k])entry[k]=label(owner.id,f[k]);
  if(f.warn&&v===true)entry.warn=label(owner.id,f.warn);
  if(f.choices)entry.choices=f.choices.map((c:Obj)=>({value:c.value,label:label(owner.id,c.label)}));
  if(f.presets)entry.presets=f.presets.map((value:any)=>({value,label:display(owner,f,value)}));
  if(f.secret)entry.hasValue=!!v;
  if(f.type==='text')entry.maxLength=f.maxLength??1024;
  if(f.source==='place'){
   entry.place={...place,timezone:place.source==='timezone'?'':system.values['location.timezone']??''};entry.searchUrl=system.values['location.search_url'];
   const prefix=key.endsWith('name')?key.slice(0,-4):key;
   entry.overridden=['mode','name','latitude','longitude','elevation','timezone'].some(part=>Object.hasOwn(owner.fields,prefix+part)&&Object.hasOwn(owner.overrides,prefix+part));
  }
  if(owner.id==='system'&&key==='display.brightness_max'&&f.type==='number'&&!f.presets?.length)entry.preview='brightness';
  return entry;
 });
 return {scope:scope.id,name:scope.name,fields,sections:sections.map(s=>({...s,label:label('system',s.label)}))};
}
