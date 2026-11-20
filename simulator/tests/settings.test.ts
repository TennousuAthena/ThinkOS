import {test} from 'node:test';
import assert from 'node:assert/strict';
import {describeSettings} from '../web/settings.ts';
test('native descriptor hides hidden fields, respects dependencies and separates related scopes',()=>{
 const system={id:'system',name:'OS',fields:{enabled:{type:'bool',default:true},dependent:{type:'text',default:'',depends:{key:'enabled',value:true}},secret:{type:'text',secret:true,default:''},hidden:{type:'bool',level:'hidden',default:false}},values:{enabled:false,dependent:'x',secret:'token',hidden:false},overrides:{secret:'token'}};
 const result=describeSettings(system,system,[],'en',(_,x)=>x,{});assert.deepEqual(result.fields.map((f:any)=>f.key),['enabled','secret']);assert.equal(result.fields[1].value,'');assert.equal(result.fields[1].display,'••••');assert.equal(result.fields[1].overridden,true);
 const plugin={id:'demo',name:'Demo',fields:{n:{type:'number',default:2,unit:'s'}},values:{n:3},overrides:{},related:['enabled','secret']};
 const desc=describeSettings(plugin,system,[],'en',(_,x)=>x,{});assert.deepEqual(desc.fields.map((f:any)=>[f.scope,f.key]),[['system','enabled'],['demo','n']]);assert.equal(desc.fields[1].display,'3 s');
});
test('native depends value/in/not chains compare all Number subtypes equally',()=>{
 const scope:any={id:'test',name:'Test',fields:{mode:{type:'number'},on:{type:'bool'},eq:{type:'text',depends:{key:'mode',value:1}},inside:{type:'text',depends:{key:'mode',in:[1,2]}},not:{type:'text',depends:{key:'on',not:false}},chain:{type:'text',depends:{key:'not',value:'yes'}}},values:{mode:new Number(1),on:true,eq:'',inside:'',not:'yes',chain:''},overrides:{}};
 const keys=()=>describeSettings(scope,scope,[],'en',(_,s)=>s,{}).fields.map((f:any)=>f.key);
 assert.deepEqual(keys(),['mode','on','eq','inside','not','chain']);scope.values.on=false;assert.deepEqual(keys(),['mode','on','eq','inside']);
 scope.values.mode=2n;assert.deepEqual(keys(),['mode','on','inside']);scope.values.mode=3;assert.deepEqual(keys(),['mode','on']);
});
test('place editor preserves follow-device empty zone and compound override state',()=>{
 const fields:any={'location.name':{type:'choice',source:'place',default:'',allowEmpty:true},'location.mode':{type:'choice',level:'hidden'},'location.timezone':{type:'choice',source:'timezone',level:'hidden'},'location.latitude':{type:'number',level:'hidden'}};
 const scope:any={id:'system',name:'System',fields,values:{'location.name':'','location.timezone':'','location.search_url':'https://example.test'},overrides:{'location.latitude':12}};
 const resolved={name:'Hong Kong',timezone:'Asia/Hong_Kong',source:'timezone',latitude:22};
 const first=describeSettings(scope,scope,[],'en',(_,s)=>s,resolved).fields[0];assert.equal(first.place.timezone,'');assert.equal(first.overridden,true);assert.equal(first.display,'Hong Kong');assert.equal(resolved.timezone,'Asia/Hong_Kong');
 scope.values['location.timezone']='Asia/Tokyo';scope.overrides={};const second=describeSettings(scope,scope,[],'en',(_,s)=>s,{...resolved,source:'manual'}).fields[0];assert.equal(second.place.timezone,'Asia/Tokyo');assert.equal(second.overridden,false);
});
test('duration follows pinned device plurals and both Android/shared placeholders',()=>{
 const scope:any={id:'system',name:'System',fields:{time:{type:'number',format:'duration',default:0}},values:{time:1.5},overrides:{}};
 const description=describeSettings(scope,scope,[],'pt-PT',(_,key)=>key.endsWith('.one')?'{1} second %1$s':'wrong',{});
 assert.equal(description.fields[0].display,'1,5 second 1,5');
 scope.values.time=3600;const hours=describeSettings(scope,scope,[],'en',(_,key)=>key==='@setting.duration.hour.one'?'{1} hour':'wrong',{});assert.equal(hours.fields[0].display,'1 hour');
});
test('timezone descriptor includes current bundled offset and brightness preview is native-only',()=>{
 const scope:any={id:'system',name:'System',fields:{zone:{type:'choice',source:'timezone'},'display.brightness_max':{type:'number'}},values:{zone:'America/New_York','display.brightness_max':90},overrides:{}};
 const result=describeSettings(scope,scope,[],'en',(_,s)=>s,{},Date.parse('2026-07-01T00:00:00Z'));assert.equal(result.fields[0].display,'New York  UTC-4');assert.equal(result.fields[1].preview,'brightness');
 scope.id='plugin';assert.equal(describeSettings(scope,scope,[],'en',(_,s)=>s,{}).fields[1].preview,undefined);
});
