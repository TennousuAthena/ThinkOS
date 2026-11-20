// MIT. SettingField.validate, TimezoneSource, RegionOptions, SettingsStore constraints
// and LocationProvider, translated from this repository without Android changes.
import {TZ_DATA} from '../generated/tz-data.ts';
import {canonicalZone,cityName,homeZone} from './locale-time.ts';
import {record,type Obj} from './values.ts';

export class SettingValidationError extends Error {
  readonly code:string;readonly args:Obj;
  constructor(code:string,message:string,args:Obj={}){super(message);this.code=code;this.args=args;this.name='SettingValidationError';}
}
const fail=(code:string,message:string,args:Obj={}):never=>{throw new SettingValidationError(code,message,args);};
const regionCodes=new Set(Object.values(TZ_DATA.zones).map(zone=>zone[0]).filter(code=>/^[A-Z]{2}$/.test(code)));
/** Kotlin Char.isWhitespace includes Unicode separators but excludes zero-width/BOM. */
const trim=(value:string)=>value.replace(/^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/g,'');
export function normalizeOption(source:string,value:string):string|null {
  switch(source){
    case'timezone':return canonicalZone(value);
    case'region':{const code=value.toUpperCase();return /^[A-Z]{2}$/.test(code)&&regionCodes.has(code)?code:null;}
    case'place':{const text=trim(value);return text.length>=1&&text.length<=160&&!/[\x00-\x1f]/.test(text)?text:null;}
    default:return null;
  }
}
export function normalizeSetting(field:Obj,value:unknown):unknown {
  switch(field.type){
    case'bool':return typeof value==='boolean'?value:fail('type','expected true or false');
    case'time':{
      if(typeof value!=='string')return fail('type','expected HH:mm');
      if(!/^(?:[01][0-9]|2[0-3]):[0-5][05]$/.test(value))return fail('time','expected HH:mm in five-minute steps');return value;
    }
    case'number':{
      if(typeof value!=='number'&&typeof value!=='bigint'&&!(value instanceof Number))return fail('type','expected a number');
      const n=Number(value);if(!Number.isFinite(n))return fail('type','expected a number');
      if(field.min!==undefined&&n<field.min)return fail('min',`must be at least ${field.min}`,{min:field.min});
      if(field.max!==undefined&&n>field.max)return fail('max',`must be at most ${field.max}`,{max:field.max});
      // step is an editor hint. The original validator deliberately does not round.
      return Number.isInteger(n)&&Math.abs(n)>=9e15?new Number(n):n===0?0:n;
    }
    case'text':{
      if(typeof value!=='string')return fail('type','expected text');
      const maximum=field.maxLength??1024;if(value.length>maximum)return fail('length',`must be at most ${maximum} characters`,{max:maximum});
      if(/[\x00-\x08\x0a-\x1f]/.test(value))return fail('chars','control characters are not allowed');return value;
    }
    case'choice':{
      if(typeof value!=='string')return fail('type','expected one of the listed options');
      if(!value)return field.allowEmpty?'':fail('required','a choice is required');
      if(field.source){const normalized=normalizeOption(field.source,value);return normalized??fail('option',field.source==='region'?'unknown region':`unknown option: ${value}`);}
      return field.choices?.some((choice:Obj)=>choice.value===value)?value:fail('option',`unknown option: ${value}`);
    }
    default:return fail('type','unknown setting type');
  }
}
/** Validate every value and compound rule before the caller mutates any layer. */
export function normalizeSettingsPatch(fields:Obj,patch:unknown,current:Obj,constraints:readonly (readonly [string,string])[]=[]):Obj {
  if(!record(patch))throw Error('settings values must be a table');
  const changed:Obj=Object.create(null);
  for(const [key,value] of Object.entries(patch)){
    if(!Object.hasOwn(fields,key))return fail('unknown',`unknown setting: ${key}`);
    changed[key]=normalizeSetting(fields[key],value);
  }
  for(const [low,high] of constraints){
    if(!Object.hasOwn(changed,low)&&!Object.hasOwn(changed,high))continue;
    const a=Object.hasOwn(changed,low)?changed[low]:current[low],b=Object.hasOwn(changed,high)?changed[high]:current[high],lowChanged=Object.hasOwn(changed,low);
    if(fields[low].type==='time'){if(a===b)return fail('time_window','start and end must differ',{key:lowChanged?high:low});}
    else if(Number(a)>=Number(b))return fail('order',lowChanged?`must be below ${high}`:`must be above ${low}`,{key:lowChanged?high:low,below:lowChanged});
  }
  return changed;
}
/** DeviceLocation's auto/manual/manual-fallback contract, independent of host Intl tzdata. */
export function locationFromSettings(settings:Obj,deviceZone:string):Obj {
  const zone=homeZone(deviceZone),automatic=(settings['location.mode']??'auto')==='auto';
  const metadata=canonicalZone(zone)?TZ_DATA.zones[zone]:null;
  if(automatic&&metadata)return {name:cityName(zone),latitude:new Number(metadata[1]),longitude:new Number(metadata[2]),elevation:new Number(0),timezone:zone,source:'timezone'};
  const latitude=Number(settings['location.latitude']??0),longitude=Number(settings['location.longitude']??0),elevation=Number(settings['location.elevation']??0);
  const rawName=String(settings['location.name']??''),name=trim(rawName)?rawName:`${latitude.toFixed(4)}°, ${longitude.toFixed(4)}°`;
  const rawZone=String(settings['location.timezone']??'');
  return {name,latitude:new Number(latitude),longitude:new Number(longitude),elevation:new Number(elevation),timezone:trim(rawZone)?rawZone:zone,source:automatic?'manual-fallback':'manual'};
}
