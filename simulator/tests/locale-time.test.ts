import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {TZ_DATA} from '../generated/tz-data.ts';
import {localeInfo,localeCandidates,pluralCategory,formatString,resolvedUnits,temperature,wind,canonicalZone,zoneType,homeZone,localDay,offsetLabel,zoneInfo,zonesForRegion,formatTime,parsePosixRule,posixType,TZ_VERSION} from '../web/locale-time.ts';
const ROOT=fileURLToPath(new URL('../../',import.meta.url));
const time=(value:string)=>Date.parse(value+'Z');

test('locale object preserves explicit/inferred scripts, language fallback and RTL',()=>{
  assert.deepEqual(localeInfo('zh-TW',true),{tag:'zh-TW',language:'zh',region:'TW',script:'Hant',rtl:false,hour24:true});
  assert.deepEqual(localeCandidates('zh-TW'),['zh-Hant-TW','zh-Hant','zh-TW','zh','en']);
  assert.deepEqual(localeCandidates('zh-Hans-HK'),['zh-Hans-HK','zh-Hans','zh-HK','zh','en']);
  assert.deepEqual(localeCandidates('en-US'),['en-US','en']);
  assert.equal(localeInfo('fr',false).script,'');
  for(const locale of ['ar','ur','he','fa','ps','sd','yi','dv','ku','ug'])assert.equal(localeInfo(locale,false).rtl,true,locale);
  assert.equal(localeInfo('zh-HK',false).script,'Hant');
  assert.equal(localeInfo('zh-CN',false).script,'Hans');
  assert.equal(localeInfo('bad__',false).tag,'en');
});
test('Units matches device setting names, automatic wind and all conversion factors',()=>{
  assert.deepEqual(resolvedUnits({}),{system:'metric',temperature:'c',wind:'kmh'});
  const imperial={'units.system':'imperial','units.wind':'auto'};
  assert.deepEqual(resolvedUnits(imperial),{system:'imperial',temperature:'f',wind:'mph'});
  assert.equal(temperature(23.4,imperial),74.12);assert.equal(temperature(23.4,{}),23.4);
  assert.equal(wind(36,{'units.wind':'ms'}),10);
  assert.equal(wind(36,imperial),36*0.621371192);
  assert.equal(wind(36,{'units.wind':'kn'}),36*0.539956803);
  assert.equal(resolvedUnits({'units.temperature':'f'}).temperature,'c');
  assert.equal(temperature(new Number(1) as number,{}),1);
  assert.throws(()=>wind(Infinity,{}));assert.throws(()=>temperature('1' as unknown as number,{}));
});
test('pinned native plural rules and StringTable numeric interpolation',()=>{
 for(const locale of ['fr','pt-PT','pt-BR'])for(const count of [0,0.5,1,1.5])assert.equal(pluralCategory(locale,count),'one');
 assert.deepEqual([0,1,2,3,11,100,1.5].map(n=>pluralCategory('ar',n)),['zero','one','two','few','many','other','other']);
 assert.deepEqual([1,2,5,11,21,22,1.5].map(n=>pluralCategory('ru',n)),['one','few','many','many','one','few','other']);
 const strings={'files.one':'{count} file','files.other':'{count} files','message':'{number} {missing} {nil} {a-b}'};
 assert.equal(formatString(strings,'files',{count:1},'en'),'1 file');assert.equal(formatString(strings,'files',{count:1234},'en'),'1,234 files');
 assert.equal(formatString(strings,'files',{count:new Number(1.5)},'pt-PT'),'1,5 file');
 assert.equal(formatString(strings,'message',{number:1.23456,nil:null,'a-b':'ok'},'en'),'1.235 {missing} {nil} ok');
 assert.equal(formatString(strings,'unknown',{},'en'),'unknown');
});
test('all bundled TZif offsets match independent Python ZoneInfo, including far-future footers',()=>{
  const script=`import datetime,json,pathlib,zoneinfo
root=pathlib.Path(${JSON.stringify(ROOT)})/'shared/tzdata'
dates=[datetime.datetime(y,m,15,12,30,tzinfo=datetime.timezone.utc) for y in [1900,1950,1970,2000,2026,2040,2099] for m in [1,4,7,10]]
result=[]
for line in (root/'zones.tsv').read_text().splitlines():
 name=line.split('\\t')[0]
 with (root/'zoneinfo'/name).open('rb') as f: zone=zoneinfo.ZoneInfo.from_file(f,key=name)
 for date in dates:
  local=date.astimezone(zone)
  result.append([name,int(date.timestamp()*1000),int(local.utcoffset().total_seconds()),bool(local.dst()),local.tzname()])
print(json.dumps(result))`;
  const rows=JSON.parse(execFileSync('python3',['-I','-c',script],{encoding:'utf8',maxBuffer:8*1024*1024}));
  assert.ok(rows.length>11000);
  for(const [id,ms,offset,dst,abbreviation] of rows){const actual=zoneType(id,ms);assert.deepEqual(actual,{offset,dst,abbreviation},`${id} ${new Date(ms).toISOString()}`);}
});
test('Kotlin ZonesTest vectors, aliases and exact transition boundaries',()=>{
  assert.equal(TZ_VERSION,'2026e');assert.ok(Object.keys(TZ_DATA.zones).length>400);
  for(const [alias,canonical] of [['Asia/Calcutta','Asia/Kolkata'],['US/Eastern','America/New_York'],['UTC','Etc/UTC'],['Asia/Saigon','Asia/Ho_Chi_Minh']])assert.equal(canonicalZone(alias),canonical);
  assert.equal(canonicalZone('Mars/Olympus_Mons'),null);assert.equal(canonicalZone('__proto__'),null);
  assert.equal(homeZone('Asia/Hong_Kong','Asia/Tokyo'),'Asia/Tokyo');assert.equal(homeZone('Asia/Hong_Kong','invalid'),'Asia/Hong_Kong');
  const checks:[string,string,number][]=[['Asia/Hong_Kong','2026-07-15',8],['Europe/London','2026-01-15',0],['Europe/London','2026-07-15',1],['Asia/Kolkata','2026-07-15',5.5],['Asia/Kathmandu','2026-07-15',5.75],['Australia/Lord_Howe','2026-07-15',10.5],['Australia/Lord_Howe','2026-01-15',11],['Pacific/Auckland','2026-01-15',13],['Pacific/Auckland','2026-07-15',12],['America/Mexico_City','2026-07-15',-6],['Asia/Tehran','2026-07-15',3.5],['Asia/Amman','2026-01-15',3],['Asia/Damascus','2026-01-15',3],['Pacific/Fiji','2026-01-15',12],['Asia/Almaty','2026-07-15',5],['Europe/Paris','2090-01-15',1],['Europe/Paris','2090-07-15',2],['America/New_York','2099-07-15',-4]];
  for(const [zone,date,hours] of checks)assert.equal(zoneType(zone,time(date)).offset/3600,hours,zone);
  const boundary=time('2026-03-08T07:00:00');assert.equal(zoneType('America/New_York',boundary-1).offset,-18000);assert.equal(zoneType('America/New_York',boundary).offset,-14400);
  assert.equal(zoneType('America/Sao_Paulo',time('2026-01-15')).dst,false);assert.equal(zoneType('Australia/Sydney',time('2026-01-15')).dst,true);
  const now=time('2026-07-01T20:30:00');assert.equal(localDay(now,'Asia/Hong_Kong')-localDay(now,'America/New_York'),1);
  assert.equal(zoneType('bad',now,'Asia/Hong_Kong').offset,28800);
  assert.equal(zoneType('Etc/GMT+3',now).offset,-10800);
  assert.deepEqual([0,19800,-12600,-14400].map(offsetLabel),['UTC','UTC+5:30','UTC-3:30','UTC-4']);
});
test('tz.info and country listing use canonical ids and native field shapes',()=>{
  const now=time('2026-07-01T20:30:00');const info=zoneInfo('US/Eastern',now,'en-US')!;
  assert.equal(info.id,'America/New_York');assert.equal(info.city,'New York');assert.equal(info.country,'US');assert.equal(info.countryName,'United States');assert.equal(info.abbreviation,'EDT');assert.equal(info.area,'America');
  assert.equal(zoneInfo('invalid',now,'en'),null);assert.equal(zoneInfo('UTC',now,'en')!.city,'UTC');
  const china=zonesForRegion('cn',now,'en-US',true);assert.ok(china.length>0);assert.ok(china.every(zone=>zone.country==='China'&&/^\d{2}:\d{2}$/.test(zone.time)));
  assert.throws(()=>zonesForRegion('china',now,'en',true));
});
test('all named formats use intended field granularity and device hour cycle',()=>{
  const now=time('2026-07-01T20:30:05');const expected:Record<string,string>={time:'5:30 AM',timeSeconds:'5:30:05 AM',date:'7/2/2026',dateLong:'July 2, 2026',dateFull:'Thursday, July 2, 2026',weekday:'Thursday',weekdayShort:'Thu',monthYear:'July 2026',monthDay:'July 2',monthDayWeekday:'Thursday, July 2',day:'2',month:'July',year:'2026'};
  for(const [name,value] of Object.entries(expected))assert.equal(formatTime(now,'@'+name,'Asia/Tokyo','en-US',false),value,name);
  assert.equal(formatTime(now,'@time','Asia/Tokyo','en-US',true),'05:30');
  assert.equal(formatTime(time('2026-01-01T00:00:00'),'@time','UTC','en-US',true),'00:00');
  assert.equal(formatTime(now,'@dateLong','Asia/Tokyo','zh-CN',true),'2026年7月2日');
});
test('raw SimpleDateFormat patterns cover every standard letter and literal quoting',()=>{
  const now=time('2026-07-01T20:30:05.007');
  assert.equal(formatTime(now,'MM/dd HH:mm','Asia/Hong_Kong','en-US',true),'07/02 04:30');
  assert.equal(formatTime(now,"yyyy-MM-dd'T'HH:mm:ss.SSS Z X XX XXX",'Asia/Tokyo','en-US',true),'2026-07-02T05:30:05.007 +0000 Z Z Z');
  assert.equal(formatTime(now,"d D F E EEEE u M MMM MMMM L LLLL a H k K h m s S SS G",'Asia/Tokyo','en-US',false),'2 183 1 Thu Thursday 4 7 Jul July 7 July AM 5 5 5 5 30 5 7 07 AD');
  assert.equal(formatTime(now,"'day' d 'it''s' h 'o''clock'",'UTC','en-US',false),"day 1 it's 8 o'clock");
  assert.equal(formatTime(time('2021-01-01T00:00:00'),'YYYY-ww W','UTC','en-GB',true),'2020-53 0');
  assert.equal(formatTime(time('2021-01-01T00:00:00'),'YYYY-ww W','UTC','en-US',true),'2021-01 1');
  assert.equal(formatTime(now,'z','Asia/Tokyo','en-US',true),'UTC');
  assert.throws(()=>formatTime(now,"yyyy'",'UTC','en',true));assert.throws(()=>formatTime(now,'q','UTC','en',true));assert.throws(()=>formatTime(now,'XXXX','UTC','en',true));assert.throws(()=>formatTime(now,'@nonsense','UTC','en',true));assert.throws(()=>formatTime(now,'d'.repeat(129),'UTC','en',true));
});
test('POSIX date grammars, southern hemisphere, leap days and malformed input',()=>{
  for(const text of ['EST5EDT','CET-1CEST,M3.5.0,M10.5.0/3','<-02>2<-01>,M3.5.0/-1,M10.5.0/0','STD0DST,J60/26,J300/-2','STD0DST,59,300']){
    const rule=parsePosixRule(text);for(const instant of ['2024-01-15','2024-07-15','2026-01-15','2026-07-15'])assert.ok(Number.isFinite(posixType(rule,time(instant)/1000).offset));
  }
  for(const invalid of ['', 'A0','EST','EST5EDT,M13.2.0,M10.1.0','EST5EDT,J0,J366','EST5EDT,366,10','EST5EDT,M3.2.0,M11.1.0,garbage'])assert.throws(()=>parsePosixRule(invalid),invalid);
});
test('generated timezone assets fail closed on source drift',()=>{
  for(const [path,hash] of Object.entries(TZ_DATA.sources))assert.equal(createHash('sha256').update(readFileSync(ROOT+'shared/tzdata/'+path)).digest('hex'),hash,path);
  execFileSync('python3',['-I',ROOT+'simulator/scripts/build_assets.py','--check'],{cwd:ROOT});
});
