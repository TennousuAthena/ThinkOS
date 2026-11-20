import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {verifySnapshot} from '../web/snapshot.ts';
const sha=(s:string)=>createHash('sha256').update(s).digest('hex'),b64=(s:string)=>Buffer.from(s).toString('base64');
function snapshot(){const manifest={id:'test',version:'1.0',api:10},raw=JSON.stringify(manifest),file={path:'manifest.json',sha256:sha(raw),size:raw.length},digest=sha(file.path+'\0'+file.sha256+'\n');return {id:'test',factory:false,digest,manifest,files:{'manifest.json':b64(raw),'index.json':b64(JSON.stringify({id:'test',version:'1.0',digest,files:[file]}))}};}
test('snapshot index is verified independently before executable bytes are accepted',async()=>{
 const good=snapshot();await verifySnapshot(good);
 for(const edit of [(p:any)=>p.files['manifest.json']=b64('{}'),(p:any)=>p.manifest.api=1,(p:any)=>p.files['evil.lua']=b64('return 1'),(p:any)=>p.digest='0'.repeat(64)]){const bad=snapshot();edit(bad);await assert.rejects(verifySnapshot(bad));}
});
