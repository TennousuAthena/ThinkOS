import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeOption,normalizeSetting,normalizeSettingsPatch,locationFromSettings,SettingValidationError} from '../web/settings-normalization.ts';
import {readFileSync} from 'node:fs';
const schema=JSON.parse(readFileSync(new URL('../../shared/schema/settings/system.json',import.meta.url),'utf8'));
const defaults=Object.fromEntries(Object.entries(schema.fields).map(([key,field]:[string,any])=>[key,field.default]));
const error=(code:string,args?:object)=>(value:unknown)=>value instanceof SettingValidationError&&value.code===code&&(!args||JSON.stringify(value.args)===JSON.stringify(args));
test('native option sources normalize only known zones/regions and bounded place text',()=>{
 assert.equal(normalizeOption('timezone','Asia/Calcutta'),'Asia/Kolkata');assert.equal(normalizeOption('timezone','Asia/Kolkata '),null);assert.equal(normalizeOption('timezone','Mars/Olympus_Mons'),null);
 assert.equal(normalizeOption('region','cn'),'CN');assert.equal(normalizeOption('region','xx'),null);assert.equal(normalizeOption('region','USA'),null);
 assert.equal(normalizeOption('place','  中国，上海  '),'中国，上海');assert.equal(normalizeOption('place','a\tb'),null);assert.equal(normalizeOption('place','x'.repeat(161)),null);
 assert.equal(normalizeSetting({type:'choice',source:'place',allowEmpty:true},''),'');assert.throws(()=>normalizeSetting({type:'choice',source:'place',allowEmpty:true},'   '),error('option'));
 assert.equal(normalizeSetting({type:'choice',source:'region'},'hk'),'HK');
});
test('native SettingField type/error/number and UTF16 length semantics',()=>{
 assert.equal(normalizeSetting({type:'number',min:0,max:10,step:2},1.3),1.3);
 assert.equal(normalizeSetting({type:'number'},new Number(1)),1);assert.equal(normalizeSetting({type:'number'},1n),1);assert.ok(normalizeSetting({type:'number'},9000000000000000n) instanceof Number);
 assert.throws(()=>normalizeSetting({type:'number',min:2},1),error('min',{min:2}));assert.throws(()=>normalizeSetting({type:'number',max:2},3),error('max',{max:2}));
 assert.throws(()=>normalizeSetting({type:'text',maxLength:1},'😀'),error('length',{max:1}));assert.equal(normalizeSetting({type:'text'},'a\tb'),'a\tb');assert.throws(()=>normalizeSetting({type:'text'},'a\nb'),error('chars'));
 for(const text of ['00:00','05:05','23:55'])assert.equal(normalizeSetting({type:'time'},text),text);
 for(const text of ['24:00','9:00','10:01','09:00:00',' 09:00'])assert.throws(()=>normalizeSetting({type:'time'},text),error('time'));
 assert.throws(()=>normalizeSetting({type:'time'},9),error('type'));assert.throws(()=>normalizeSetting({type:'bool'},1),error('type'));
});
test('patch validation is atomic and time windows may wrap midnight',()=>{
 const fields={low:{type:'number'},high:{type:'number'},start:{type:'time'},end:{type:'time'},name:{type:'text',maxLength:3}};
 const current={low:1,high:4,start:'22:00',end:'07:00',name:'old'};
 assert.equal(normalizeSettingsPatch(fields,{start:'23:00'},current,[['start','end']]).start,'23:00');
 assert.throws(()=>normalizeSettingsPatch(fields,{low:4},current,[['low','high']]),error('order',{key:'high',below:true}));
 assert.throws(()=>normalizeSettingsPatch(fields,{high:0},current,[['low','high']]),error('order',{key:'low',below:false}));
 assert.throws(()=>normalizeSettingsPatch(fields,{end:'22:00'},current,[['start','end']]),error('time_window',{key:'start'}));
 assert.throws(()=>normalizeSettingsPatch(fields,{low:2,name:'invalid'},current),error('length',{max:3}));assert.deepEqual(current,{low:1,high:4,start:'22:00',end:'07:00',name:'old'});
 assert.equal(normalizeSettingsPatch(fields,{name:'new'},{...current,low:5},[['low','high']]).name,'new');
});
test('all shipped system defaults validate, including dynamic option sources',()=>{
 for(const [key,field] of Object.entries(schema.fields))assert.doesNotThrow(()=>normalizeSetting(field as any,defaults[key]),key);
 const patch=normalizeSettingsPatch(schema.fields,{'location.timezone':'Asia/Calcutta','location.name':' Kolkata ','location.mode':'manual'},defaults,schema.constraints);
 assert.equal(patch['location.timezone'],'Asia/Kolkata');assert.equal(patch['location.name'],'Kolkata');
});
test('location provider uses configured coordinates, device representative cities and fallback',()=>{
 const auto=locationFromSettings(defaults,'Asia/Hong_Kong');assert.equal(auto.source,'timezone');assert.equal(auto.timezone,'Asia/Hong_Kong');assert.equal(auto.name,'Hong Kong');assert.ok(Number(auto.latitude)>22&&Number(auto.latitude)<23);assert.ok(auto.elevation instanceof Number);
 const manual=locationFromSettings({...defaults,'location.mode':'manual','location.latitude':31.2,'location.longitude':121.4,'location.elevation':5,'location.timezone':'Asia/Shanghai'},'Asia/Hong_Kong');
 assert.equal(manual.name,'31.2000°, 121.4000°');assert.equal(manual.source,'manual');assert.equal(manual.timezone,'Asia/Shanghai');assert.equal(Number(manual.elevation),5);
 const fallback=locationFromSettings(defaults,'Etc/GMT+3');assert.equal(fallback.source,'manual-fallback');assert.equal(fallback.timezone,'Etc/GMT+3');
});
