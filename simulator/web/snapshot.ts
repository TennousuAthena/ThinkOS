import {base64Text,json,type Obj} from './values.ts';
import type {Package} from './runtime.ts';
const bytes=(text:string)=>Uint8Array.from(atob(text),c=>c.charCodeAt(0));
const hash=async(data:Uint8Array)=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data as BufferSource)),n=>n.toString(16).padStart(2,'0')).join('');
/** Check the packaged final bytes again before they enter the renderer or Lua worker. */
export async function verifySnapshot(pkg:Package):Promise<void>{
 const index=JSON.parse(base64Text(pkg.files['index.json']??''));
 if(index.id!==pkg.id||index.version!==pkg.manifest.version||index.digest!==pkg.digest||!Array.isArray(index.files)||index.files.length>128)throw Error('invalid snapshot identity');
 const names=new Set<string>();let canonical='',size=0,previous='';
 for(const entry of index.files as Obj[]){
  const name=entry.path;
  if(typeof name!=='string'||name.split('/').some(s=>!s||s==='.'||s==='..')||name.startsWith('/')||name.includes('\\')||names.has(name)||!Object.hasOwn(pkg.files,name))throw Error('invalid snapshot path');
  if(previous&&name<=previous)throw Error('unsorted snapshot');previous=name;names.add(name);
  const data=bytes(pkg.files[name]);size+=data.length;
  if(size>8*1024*1024||data.length!==entry.size||await hash(data)!==entry.sha256)throw Error('snapshot file digest mismatch');
  canonical+=name+'\0'+entry.sha256+'\n';
 }
 if(Object.keys(pkg.files).some(name=>name!=='index.json'&&!names.has(name))||await hash(new TextEncoder().encode(canonical))!==pkg.digest)throw Error('snapshot digest mismatch');
 const manifest=JSON.parse(base64Text(pkg.files['manifest.json']));
 const canonicalObject=(value:any):any=>Array.isArray(value)?value.map(canonicalObject):value&&typeof value==='object'&&!(value instanceof Number)?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonicalObject(value[key])])):value;
 if(JSON.stringify(canonicalObject(manifest))!==JSON.stringify(canonicalObject(pkg.manifest)))throw Error('snapshot manifest mismatch');
}
