import {test} from 'node:test';
import assert from 'node:assert/strict';
import {merge,path,json,parseJson,Health,eventAllowed,validateSetting} from '../web/values.ts';
test('atomic recursive patch preserves missing keys and replaces arrays/null',()=>{
 const original={a:{b:1,c:2},items:[1,2]};
 assert.equal(json(merge(original,{a:{b:null},items:[3]})), '{"a":{"b":null,"c":2},"items":[3]}');
 assert.throws(()=>merge(original,{large:'x'.repeat(100)},20)); assert.equal(original.a.b,1);
});
test('prototype keys remain data and never expose inherited members',()=>{
 const value=merge({},JSON.parse('{"__proto__":{"polluted":true},"constructor":9}'));
 assert.equal(path(value,'__proto__.polluted'),true); assert.equal(path({},'toString'),null);
 assert.equal(({} as any).polluted,undefined);
});
test('int64 JSON is not rounded',()=>assert.equal(json({n:9007199254740993n}),' {"n":9007199254740993}'.trim()));
test('event grants include API gate',()=>{
 assert.equal(eventAllowed('update',9,new Set(['system.update'])),false);
 assert.equal(eventAllowed('update',10,new Set()),false);
 assert.equal(eventAllowed('update',10,new Set(['system.update'])),true);
});
test('health preserves ordinary errors then backs off and cools down',()=>{
 const h=new Health(); for(let i=0;i<4;i++)assert.equal(h.failure(i,false),null);
 assert.equal(h.failure(4,false),2000); assert.equal(h.failure(5,true),4000);
 assert.equal(h.failure(6,true),8000); assert.equal(h.failure(7,true),16000); assert.equal(h.failure(8,true),3600000);
});
test('settings reject bad types and values',()=>{
 assert.throws(()=>validateSetting({type:'bool'},1));
 // Native SettingField validates bounds; step only controls the editor increment.
 assert.equal(validateSetting({type:'number',min:0,max:10,step:2},3),3);
 assert.throws(()=>validateSetting({type:'number',min:0,max:10},11));
 assert.throws(()=>validateSetting({type:'time'},'24:00'));
 assert.throws(()=>validateSetting({type:'time'},'20:03'));
 assert.equal(validateSetting({type:'time'},'20:05'),'20:05');
 assert.equal(validateSetting({type:'choice',choices:[{value:'a'}]},'a'),'a');
});
test('lossless persistence retains integral doubles and int64 through JSON',()=>{
 const source='{"int":9007199254740993,"float":1.0,"negative":-0.0,"array":[],"object":{},"null":null}';
 const value=parseJson(source);assert.equal(value.int,9007199254740993n);assert.ok(value.float instanceof Number);assert.ok(Object.is(value.negative.valueOf(),-0));assert.equal(json(value),source);
});
test('JSON parser rejects malformed, deep and non-finite input without prototype effects',()=>{
 for(const input of ['[1,]','{"x":1,}','01','true false','{"x":"a\n"}','[','1e999','[ '.repeat(33)+'0'+']'.repeat(33)])assert.throws(()=>parseJson(input),input);
 assert.throws(()=>parseJson('"abcd"',3));assert.equal(parseJson('{"__proto__":{"test":1}}').__proto__.test,1);assert.equal(({} as any).test,undefined);
});
