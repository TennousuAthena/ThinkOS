// MIT. Ports the repository's LocaleChain, Formats, Units, ZoneRules and ZoneFormat.
// Timezone offsets use the same bundled IANA data as Android. Text uses browser CLDR;
// Intl has no exemplar-city API, so cities use ZoneNames' documented id fallback.
import {TZ_DATA, type ZoneRecord} from '../generated/tz-data.ts';

export const TZ_VERSION = TZ_DATA.version;
export const SHIPPED_LOCALES = ['en','zh-Hans','zh-Hant','hi','es','fr','ar','bn','pt','ru','ur'];
const RTL = new Set(['ar','ur','he','fa','ps','sd','yi','dv','ku','ug']);
const DAY = 86400000;
const own = (value:object,key:string)=>Object.prototype.hasOwnProperty.call(value,key);
function localeTag(tag:string):Intl.Locale {
  try { return new Intl.Locale(tag.replaceAll('_','-')); } catch { return new Intl.Locale('en'); }
}
export function localeInfo(tag:string,hour24:boolean) {
  const locale=localeTag(tag),language=locale.language,region=locale.region??'';
  const script=locale.script??(language==='zh'?(['TW','HK','MO'].includes(region)?'Hant':'Hans'):'');
  return {tag:locale.toString(),language,region,script,rtl:RTL.has(language),hour24};
}
export function localeCandidates(tag:string):string[] {
  const {language,region,script}=localeInfo(tag,false);
  return [...new Set([script&&region?`${language}-${script}-${region}`:'',script?`${language}-${script}`:'',region?`${language}-${region}`:'',language,'en'].filter(Boolean))];
}
/** The device intentionally pins these plural categories instead of using ICU rules. */
export function pluralCategory(locale:string,count:number):string {
  if(!Number.isFinite(count))return 'other';const integer=Number.isInteger(count),n=Math.abs(count),i=Math.floor(n),language=localeTag(locale).language;
  if(['zh','ja','ko','vi','th','id'].includes(language))return 'other';
  if(language==='fr'||language==='pt')return i===0||i===1?'one':'other';
  if(language==='hi'||language==='bn')return i===0||n===1?'one':'other';
  if(language==='ru'){if(!integer)return 'other';const m10=i%10,m100=i%100;return m10===1&&m100!==11?'one':m10>=2&&m10<=4&&(m100<12||m100>14)?'few':'many';}
  if(language==='ar'){if(!integer)return 'other';const m100=i%100;return i===0?'zero':i===1?'one':i===2?'two':m100>=3&&m100<=10?'few':m100>=11&&m100<=99?'many':'other';}
  return n===1?'one':'other';
}
/** StringTable.format on an already LocaleChain-resolved flat table. */
export function formatString(table:Record<string,unknown>,key:string,params:Record<string,unknown>={},locale='en'):string {
  const count=params.count,isNumber=(value:unknown)=>typeof value==='number'||typeof value==='bigint'||value instanceof Number;
  const get=(name:string)=>own(table,name)&&typeof table[name]==='string'?table[name] as string:null;
  const template=(isNumber(count)?get(`${key}.${pluralCategory(locale,Number(count))}`)??get(`${key}.other`):null)??get(key)??key;
  const format=new Intl.NumberFormat(localeTag(locale).toString(),{maximumFractionDigits:3});
  return template.replace(/\{([^}]*)\}/g,(whole,name:string)=>{const value=own(params,name)?params[name]:null;if(value===null||value===undefined)return whole;return isNumber(value)?format.format(typeof value==='bigint'?value:Number(value)):String(value);});
}
type Settings = Record<string,unknown>;
export function resolvedUnits(settings:Settings) {
  const system=String(settings['units.system']??'metric'),choice=String(settings['units.wind']??'auto');
  return {system,temperature:system==='imperial'?'f':'c',wind:choice==='auto'?(system==='imperial'?'mph':'kmh'):choice};
}
function finite(value:unknown):number {
  if(typeof value!=='number'&&typeof value!=='bigint'&&!(value instanceof Number))throw Error('expected number');
  const n=Number(value);if(!Number.isFinite(n))throw Error('expected finite number');return n;
}
export function temperature(celsius:number,settings:Settings):number { const n=finite(celsius);return resolvedUnits(settings).temperature==='f'?n*1.8+32:n; }
export function wind(kmh:number,settings:Settings):number { const n=finite(kmh);switch(resolvedUnits(settings).wind){case'ms':return n/3.6;case'mph':return n*0.621371192;case'kn':return n*0.539956803;default:return n;} }

export type LocalType = {offset:number;dst:boolean;abbreviation:string};
const fromType = (type:[number,boolean,string]):LocalType=>({offset:type[0],dst:type[1],abbreviation:type[2]});
export function canonicalZone(id:string|null|undefined):string|null {
  if(typeof id!=='string')return null;
  if(own(TZ_DATA.zones,id))return id;
  const target=own(TZ_DATA.links,id)?TZ_DATA.links[id]:null;
  return target&&own(TZ_DATA.zones,target)?target:null;
}
const utc = (year:number,month=1,day=1):number=>{const d=new Date(0);d.setUTCFullYear(year,month-1,day);return d.getTime();};
const leap = (year:number)=>year%4===0&&(year%100!==0||year%400===0);
type RuleDate = {kind:'M';month:number;week:number;weekday:number}|{kind:'J';day:number}|{kind:'n';day:number};
type Rule = {standard:LocalType;daylight:LocalType|null;start?:RuleDate;end?:RuleDate;startTime:number;endTime:number};
/** Same POSIX-TZ parser and defaults as the device; accepts signed/>24h transitions. */
export function parsePosixRule(text:string):Rule {
  if(text.length>512)throw Error('TZ rule too long');
  let at=0;
  const peek=()=>text[at],expect=(c:string)=>{if(text[at++]!==c)throw Error('invalid TZ rule');};
  const number=()=>{const match=/^\d{1,3}/.exec(text.slice(at));if(!match)throw Error('invalid TZ number');at+=match[0].length;if(/\d/.test(peek()??''))throw Error('invalid TZ number');return +match[0];};
  const name=()=>{if(peek()==='<'){const close=text.indexOf('>',at);if(close<=at)throw Error('invalid TZ name');const result=text.slice(at+1,close);at=close+1;return result;}const match=/^[A-Za-z]{3,}/.exec(text.slice(at));if(!match)throw Error('invalid TZ name');at+=match[0].length;return match[0];};
  const offset=()=>{let sign=1;if(peek()==='+'||peek()==='-'){if(text[at++]==='-')sign=-1;}let seconds=number()*3600;if(peek()===':'){at++;seconds+=number()*60;if(peek()===':'){at++;seconds+=number();}}return sign*seconds;};
  const date=():[RuleDate,number]=>{let value:RuleDate;if(peek()==='M'){at++;const month=number();expect('.');const week=number();expect('.');const weekday=number();if(month<1||month>12||week<1||week>5||weekday>6)throw Error('invalid TZ date');value={kind:'M',month,week,weekday};}else{const kind=peek()==='J'?'J':'n';if(kind==='J')at++;const day=number();if(day<(kind==='J'?1:0)||day>365)throw Error('invalid TZ date');value={kind,day};}let time=7200;if(peek()==='/'){at++;time=offset();}return [value,time];};
  const abbreviation=name(),standard={offset:-offset()||0,dst:false,abbreviation};
  if(at===text.length)return {standard,daylight:null,startTime:0,endTime:0};
  const dstName=name(),dstOffset=at===text.length||peek()===','?standard.offset+3600:(-offset()||0);
  const daylight={offset:dstOffset,dst:true,abbreviation:dstName};
  if(at===text.length)return {standard,daylight,start:{kind:'M',month:3,week:2,weekday:0},end:{kind:'M',month:11,week:1,weekday:0},startTime:7200,endTime:7200};
  expect(',');const [start,startTime]=date();expect(',');const [end,endTime]=date();
  if(at!==text.length)throw Error('unexpected TZ rule suffix');return {standard,daylight,start,end,startTime,endTime};
}
function ruleDay(value:RuleDate,year:number):number {
  if(value.kind==='J')return utc(year)/DAY+value.day-1+(leap(year)&&value.day>=60?1:0);
  if(value.kind==='n')return utc(year)/DAY+value.day;
  const first=utc(year,value.month),dayOfWeek=new Date(first).getUTCDay();
  let day=1+(value.weekday-dayOfWeek+7)%7+(value.week-1)*7;
  const length=new Date(utc(year,value.month+1)-DAY).getUTCDate();while(day>length)day-=7;
  return first/DAY+day-1;
}
export function posixType(rule:Rule,epochSecond:number):LocalType {
  if(!rule.daylight||!rule.start||!rule.end)return {...rule.standard};
  const year=new Date((epochSecond+rule.standard.offset)*1000).getUTCFullYear();
  let latest=-Infinity,latestDst=false,earliest=Infinity,earliestDst=false;
  for(let y=year-1;y<=year+1;y++){
    const events:[number,boolean][]=[[ruleDay(rule.start,y)*86400+rule.startTime-rule.standard.offset,true],[ruleDay(rule.end,y)*86400+rule.endTime-rule.daylight.offset,false]];
    for(const [instant,dst] of events){if(instant<=epochSecond&&instant>latest){latest=instant;latestDst=dst;}if(instant<earliest){earliest=instant;earliestDst=dst;}}
  }
  return {...((latest===-Infinity?!earliestDst:latestDst)?rule.daylight:rule.standard)};
}
const footerCache=new Map<string,Rule>();
function bundledType(record:ZoneRecord,second:number):LocalType {
  const transitions=record[3],indexes=record[4],types=record[5],footer=record[6],count=transitions.length;
  if(footer&&(!count||second>=transitions[count-1])){let rule=footerCache.get(footer);if(!rule){rule=parsePosixRule(footer);footerCache.set(footer,rule);}return posixType(rule,second);}
  if(!count||second<transitions[0])return fromType(types[0]);
  let low=0,high=count-1;while(low<high){const mid=(low+high+1)>>>1;if(transitions[mid]<=second)low=mid;else high=mid-1;}return fromType(types[indexes[low]]);
}
/** Fixed-offset platform IDs absent from zone.tab retain their POSIX sign. */
function platformFixed(id:string):LocalType|null {
  const match=/^Etc\/GMT([+-])(\d{1,2})$/.exec(id);
  if(match&&+match[2]<=14){const offset=+match[2]*3600*(match[1]==='+'?-1:1);return {offset,dst:false,abbreviation:`${offset<0?'-':'+'}${String(Math.abs(offset)/3600).padStart(2,'0')}`};}
  return null;
}
export function homeZone(deviceZone:string,placeZone?:string|null):string {return canonicalZone(placeZone)??canonicalZone(deviceZone)??(platformFixed(deviceZone)?deviceZone:'Etc/UTC');}
export function zoneType(id:string|null|undefined,ms:number,home='Etc/UTC'):LocalType {
  finite(ms);const canonical=canonicalZone(id);if(canonical)return bundledType(TZ_DATA.zones[canonical],Math.floor(ms/1000));
  if(id){const fixed=platformFixed(id);if(fixed)return fixed;}
  const target=canonicalZone(home);return target?bundledType(TZ_DATA.zones[target],Math.floor(ms/1000)):(platformFixed(home)??{offset:0,dst:false,abbreviation:'UTC'});
}
/** Used by astronomy too, so civil-day windows and time text share exactly one database. */
export function localDay(ms:number,zone:string):number {return Math.floor((ms+zoneType(zone,ms).offset*1000)/DAY);}
export function offsetLabel(offsetSeconds:number):string {
  if(!offsetSeconds)return 'UTC';const total=Math.abs(offsetSeconds),hours=Math.floor(total/3600),minutes=Math.floor(total/60)%60;
  return `UTC${offsetSeconds<0?'-':'+'}${hours}${minutes?':'+String(minutes).padStart(2,'0'):''}`;
}
export function cityName(id:string):string {return id==='Etc/UTC'?'UTC':id.split('/').at(-1)!.replaceAll('_',' ');}
export function countryName(code:string,locale:string):string {
  if(!code)return '';try{return new Intl.DisplayNames([localeTag(locale).toString()],{type:'region'}).of(code)||code;}catch{return TZ_DATA.countries[code]??code;}
}
export function zoneInfo(id:string,ms:number,locale:string) {
  const canonical=canonicalZone(id);if(!canonical)return null;
  const [country,latitude,longitude]=TZ_DATA.zones[canonical];
  return {id:canonical,city:cityName(canonical),country,countryName:countryName(country,locale),area:canonical.split('/')[0],latitude:new Number(latitude),longitude:new Number(longitude),...zoneType(canonical,ms)};
}
export function zonesForRegion(region:string,ms:number,locale:string,hour24:boolean) {
  region=region.toUpperCase();if(!/^[A-Z]{2}$/.test(region))throw Error('region must be a two-letter country code');
  return Object.entries(TZ_DATA.zones).filter(([,record])=>record[0]===region).map(([id])=>({id,city:cityName(id),country:countryName(region,locale),time:formatTime(ms,'@time',id,locale,hour24)}));
}

const named:Record<string,Intl.DateTimeFormatOptions>={
  date:{year:'numeric',month:'numeric',day:'numeric'},dateLong:{year:'numeric',month:'long',day:'numeric'},
  dateFull:{year:'numeric',month:'long',day:'numeric',weekday:'long'},weekday:{weekday:'long'},weekdayShort:{weekday:'short'},
  monthYear:{year:'numeric',month:'long'},monthDay:{month:'long',day:'numeric'},monthDayWeekday:{month:'long',day:'numeric',weekday:'long'},
  day:{day:'numeric'},month:{month:'long'},year:{year:'numeric'},
};
const formatCache=new Map<string,Intl.DateTimeFormat>();
// CLDR supplementalData.xml weekData/minDays, reviewed 2026-11-07.
// https://github.com/unicode-org/cldr/blob/main/common/supplemental/supplementalData.xml
// Copyright Unicode, Inc.; Unicode License V3: assets/licenses/Unicode-3.0.txt.
const FOUR_DAY_REGIONS=new Set('AD AN AT AX BE BG CH CZ DE DK EE ES FI FJ FO FR GB GF GG GI GP GR HU IE IM IS IT JE LI LT LU MC MQ NL NO PL PT RE RU SE SJ SK SM VA'.split(' '));
function dateFormat(locale:string,options:Intl.DateTimeFormatOptions):Intl.DateTimeFormat {
  const tag=localeTag(locale).toString(),key=tag+JSON.stringify(options);let value=formatCache.get(key);
  if(!value){value=new Intl.DateTimeFormat(tag,{...options,timeZone:'UTC'});if(formatCache.size>=64)formatCache.delete(formatCache.keys().next().value!);formatCache.set(key,value);}return value;
}
function part(ms:number,locale:string,type:Intl.DateTimeFormatPartTypes,options:Intl.DateTimeFormatOptions):string {
  return dateFormat(locale,options).formatToParts(ms).find(p=>p.type===type)?.value??'';
}
function weekSettings(locale:string):{firstDay:number;minimalDays:number} {
  const loc=localeTag(locale) as Intl.Locale&{weekInfo?:{firstDay:number;minimalDays:number};getWeekInfo?:()=>{firstDay:number;minimalDays:number}};
  const info=loc.getWeekInfo?.()??loc.weekInfo;
  if(info)return {firstDay:info.firstDay,minimalDays:info.minimalDays??(FOUR_DAY_REGIONS.has(loc.region??loc.maximize().region??'')?4:1)};
  // Old browsers without the Intl.Locale API are not silently calibrated.
  throw Error('raw week patterns require Intl.Locale weekInfo');
}
function weekFields(date:Date,locale:string):{year:number;week:number;monthWeek:number} {
  const {firstDay,minimalDays}=weekSettings(locale),year=date.getUTCFullYear(),day=Math.floor(date.getTime()/DAY);
  const first=(year:number,month=1)=>{const instant=utc(year,month)/DAY,weekday=new Date(instant*DAY).getUTCDay();const prior=(weekday-firstDay+7)%7;return instant-prior+(7-prior<minimalDays?7:0);};
  let weekYear=year;if(day<first(year))weekYear--;else if(day>=first(year+1))weekYear++;
  return {year:weekYear,week:Math.floor((day-first(weekYear))/7)+1,monthWeek:Math.floor((day-first(year,date.getUTCMonth()+1))/7)+1};
}
/** SimpleDateFormat patterns use offset-shifted UTC, exactly like ZoneFormat. */
export function formatTime(ms:number,spec:string,zone:string|null|undefined,locale:string,hour24:boolean,home='Etc/UTC'):string {
  ms=Math.trunc(finite(ms));if(!Number.isFinite(new Date(ms).getTime()))throw Error('time is outside the supported date range');
  if(typeof spec!=='string'||spec.length>128)throw Error('time pattern exceeds 128 characters');
  const shifted=ms+zoneType(zone,ms,home).offset*1000,date=new Date(shifted);
  if(spec.startsWith('@')){
    const name=spec.slice(1);let options=named[name];
    if(name==='time'||name==='timeSeconds')options={hour:hour24?'2-digit':'numeric',minute:'2-digit',...(name==='timeSeconds'?{second:'2-digit' as const}:{}),hourCycle:hour24?'h23':'h12'};
    // Formats.pattern returns the bare name; apply the same raw-pattern validation below.
    if(options)return dateFormat(locale,options).format(shifted).replaceAll('\u202f',' ');
    spec=name;
  }
  const numeric=new Intl.NumberFormat(localeTag(locale).toString(),{useGrouping:false,maximumFractionDigits:0});
  const number=(n:number,width:number)=>numeric.format(n).padStart(width,numeric.format(0));
  const year=date.getUTCFullYear(),month=date.getUTCMonth()+1,day=date.getUTCDate(),hour=date.getUTCHours();
  const token=(symbol:string,count:number):string=>{
    switch(symbol){
      case'G':return part(shifted,locale,'era',{era:count>=4?'long':'short'});
      case'y':{const y=Number(part(shifted,'en-u-nu-latn','year',{year:'numeric'}))||Math.abs(year);return number(count===2?y%100:y,count===2?2:count);}
      case'Y':{const y=weekFields(date,locale).year;return number(count===2?y%100:y,count);}
      case'M':case'L':return count>=3?part(shifted,locale,'month',{month:count>=4?'long':'short',...(symbol==='M'?{day:'numeric' as const}:{})}):number(month,count);
      case'w':return number(weekFields(date,locale).week,count);
      case'W':return number(weekFields(date,locale).monthWeek,count);
      case'D':return number(Math.floor((utc(year,month,day)-utc(year))/DAY)+1,count);
      case'd':return number(day,count);
      case'F':return number(Math.floor((day-1)/7)+1,count);
      case'E':return part(shifted,locale,'weekday',{weekday:count>=4?'long':'short'});
      case'u':return number(date.getUTCDay()||7,count);
      case'a':return part(shifted,locale,'dayPeriod',{hour:'numeric',hourCycle:'h12'});
      case'H':return number(hour,count);
      case'k':return number(hour||24,count);
      case'K':return number(hour%12,count);
      case'h':return number(hour%12||12,count);
      case'm':return number(date.getUTCMinutes(),count);
      case's':return number(date.getUTCSeconds(),count);
      case'S':return number(date.getUTCMilliseconds(),count);
      case'z':return part(shifted,locale,'timeZoneName',{timeZoneName:count>=4?'long':'short'});
      case'Z':return '+0000';
      case'X':if(count<=3)return 'Z';throw Error('invalid ISO8601 pattern length');
      default:throw Error(`unsupported SimpleDateFormat pattern character: ${symbol}`);
    }
  };
  let result='',at=0,quoted=false;
  while(at<spec.length){const c=spec[at];if(c==="'"){if(spec[at+1]==="'"){result+="'";at+=2;continue;}quoted=!quoted;at++;continue;}
    if(quoted||!/[A-Za-z]/.test(c)){result+=c;at++;continue;}let end=at+1;while(spec[end]===c)end++;result+=token(c,end-at);at=end;
  }
  if(quoted)throw Error('unterminated quote in time pattern');return result;
}
