import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { sun, moon, seasons, moonPhase, nextPhase, dayWindow, crossings, ASTRONOMY_SOURCES } from '../web/astronomy.ts';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const rows=(name:string)=>readFileSync(resolve(root,'android/app/src/test/resources',name),'utf8').split(/\r?\n/).filter(line=>line&&!line.startsWith('#')).map(line=>line.split('\t'));
const near=(actual:number,expected:number,limit:number,label:string)=>assert.ok(Math.abs(actual-expected)<=limit,`${label}: difference ${actual-expected}, tolerance ${limit}`);

test('astronomy port source identities remain explicit',()=>{
  for(const [name,expected] of Object.entries(ASTRONOMY_SOURCES)){
    const source=readFileSync(resolve(root,'android/app/src/main/java/moe/kiwi/thinkos/astro',name));
    assert.equal(createHash('sha256').update(source).digest('hex'),expected,`Review astronomy port when ${name} changes`);
  }
});
test('all 101 season years match the Android independent vectors',()=>{
  const vectors=rows('seasons-golden.tsv');assert.equal(vectors.length,101);
  for(const row of vectors){const [year,...expected]=row.map(Number),result=seasons(year),actual=[result.marchEquinox,result.juneSolstice,result.septemberEquinox,result.decemberSolstice];
    actual.forEach((value,index)=>near(value,expected[index],year>=2020&&year<=2029?60000:90000,`${year} season ${index}`));
    assert.ok(actual.every((value,index)=>index===0||value>actual[index-1]));
  }
});
test('solar almanac follows all Android independent geographic vectors',()=>{
  const vectors=rows('astro-golden.tsv');assert.ok(vectors.length>=1000);
  for(const v of vectors){const label=`${v[0]} ${v[5]}`,result=sun(+v[5],{latitude:+v[2],longitude:+v[3],elevation:+v[4]},v[1]);assert.ok(result);
    const actual=[result.noon,result.sunrise,result.sunset,result.civil.dawn,result.civil.dusk,result.nautical.dawn,result.nautical.dusk,result.astronomical.dawn,result.astronomical.dusk];
    actual.forEach((value,index)=>{const expected=v[index+6]==='-'?null:+v[index+6];if(value===null||expected===null)assert.equal(value,expected,`${label} event ${index}`);else near(value,expected,index<=2?90000:120000,`${label} event ${index}`);});
    const endpoints=v.slice(15).filter(value=>value!=='-').map(Number);
    for(const band of [...result.golden,...result.blue]){assert.ok(band.start<band.end);for(const endpoint of [band.start,band.end]){if(Math.abs(endpoint-(result.noon-43200000))<1000||Math.abs(endpoint-(result.noon+43200000))<1000)continue;assert.ok(endpoints.some(value=>Math.abs(value-endpoint)<=120000),`${label} band endpoint`);}}
    assert.ok(result.dayLength>=0&&result.dayLength<=86400000);
  }
});
test('moon positions, civil-day events and phases match Android independent vectors',()=>{
  let days=0,phases=0;
  const eventList=(text:string)=>text==='-'?[]:text.split(',').map(Number);
  for(const c of rows('moon-golden.tsv')){const ms=+c[1];
    if(c[0]==='D'){days++;const result=moon(ms,{latitude:+c[3],longitude:+c[4],elevation:+c[5]},c[2]);assert.ok(result);const label=`${c[2]} ${ms}`;
      assert.equal(result.start,+c[6],`${label} start`);assert.equal(result.end,+c[7],`${label} end`);
      for(const [name,expected,actual] of [['rise',eventList(c[8]),result.rises],['set',eventList(c[9]),result.sets]] as const){assert.equal(actual.length,expected.length,`${label} ${name} count`);actual.forEach((value,index)=>near(value,expected[index],180000,`${label} ${name}`));}
      near(result.altitude,+c[10],0.1,`${label} altitude`);
      const rad=Math.PI/180,alt=result.altitude*rad,truth=+c[10]*rad,az=(result.azimuth-(+c[11]))*rad;
      const separation=Math.acos(Math.max(-1,Math.min(1,Math.sin(alt)*Math.sin(truth)+Math.cos(alt)*Math.cos(truth)*Math.cos(az))))/rad;
      near(separation,0,0.1,`${label} position`);near(result.phase.illumination,+c[12],0.01,`${label} illumination`);
      assert.ok(result.transits.every(value=>value>=result.start&&value<result.end));assert.ok(!(result.alwaysUp&&result.alwaysDown));
    }else{phases++;const phase=moonPhase(ms);near(phase.illumination,+c[2],0.01,`phase ${ms}`);
      const actual=[phase.previousNewMoon,phase.nextNewMoon,phase.nextFirstQuarter,phase.nextFullMoon,phase.nextLastQuarter];actual.forEach((value,index)=>near(value,+c[index+3],600000,`phase ${ms} event ${index}`));
      assert.ok(phase.previousNewMoon<=ms&&phase.nextNewMoon>=ms);assert.equal(phase.waxing,phase.angle<180);assert.ok(phase.ageDays>=0&&phase.ageDays<=30);
    }
  }
  assert.ok(days>=300&&phases>=180);
});
test('DST, polar states, date-line zone and grazing crossings retain device semantics',()=>{
  const spring=dayWindow(Date.UTC(2026,2,8,15),'America/New_York'),fall=dayWindow(Date.UTC(2026,10,1,15),'America/New_York');
  assert.equal(spring.end-spring.start,23*3600000);assert.equal(fall.end-fall.start,25*3600000);
  const p={latitude:78.223,longitude:15.627},summer=sun(Date.UTC(2026,5,21,12),p,'Arctic/Longyearbyen')!,winter=sun(Date.UTC(2026,11,21,12),p,'Arctic/Longyearbyen')!;
  assert.equal(summer.polar,'midnight_sun');assert.equal(summer.dayLength,86400000);assert.equal(summer.sunrise,null);
  assert.equal(winter.polar,'polar_night');assert.equal(winter.dayLength,0);assert.equal(winter.sunset,null);
  const island=sun(Date.UTC(2026,5,21,12),{latitude:1.872,longitude:-157.427},'Pacific/Kiritimati')!;
  assert.ok(island.noon>=island.start&&island.noon<island.end);assert.equal(island.dayLength,island.sunset!-island.sunrise!);
  for(const peak of [5000,300000,595000]){const events=crossings(0,600000,t=>1-((t-peak)/1000)**2);assert.equal(events.length,2);assert.equal(events[0].rising,true);assert.equal(events[1].rising,false);near(events[0].time,peak-1000,250,'grazing rise');near(events[1].time,peak+1000,250,'grazing set');}
  assert.equal(crossings(0,600000,t=>((t-300000)/1000)**2).length,0);
  assert.deepEqual(crossings(0,600000,t=>t),[{time:0,rising:true}]);assert.deepEqual(crossings(0,600000,t=>t-600000),[]);
  const a=nextPhase(Date.UTC(2026,0,1),0),b=nextPhase(a+1000,0),c=nextPhase(b+1000,0);assert.notEqual(b-a,c-b);assert.ok(moonPhase(a).ageDays<1e-6);
});
test('untrusted time and invalid input never become plausible astronomy',()=>{
  const place={latitude:22.3193,longitude:114.1694};assert.equal(sun(0,place,'Asia/Hong_Kong'),null);assert.equal(moon(0,place,'Asia/Hong_Kong'),null);
  for(const year of [1949,2051,2026.5,NaN])assert.throws(()=>seasons(year));
  for(const ms of [-1,NaN,Infinity,Date.UTC(2051,0,1)])assert.throws(()=>sun(ms,place,'Asia/Hong_Kong'));
  for(const p of [{latitude:NaN,longitude:0},{latitude:0,longitude:181},{latitude:0,longitude:0,elevation:Infinity}])assert.throws(()=>moon(Date.UTC(2026,0,1),p,'UTC'));
  assert.throws(()=>sun(Date.UTC(2026,0,1),place,'Unknown/Timezone'));assert.throws(()=>nextPhase(Date.UTC(2026,0,1),4));
  assert.equal(sun(Date.UTC(2026,0,1),place,'US/Eastern')!.timezone,'America/New_York');
  assert.equal(moon(Date.UTC(2026,0,1),place,'Asia/Calcutta')!.timezone,'Asia/Kolkata');
});
