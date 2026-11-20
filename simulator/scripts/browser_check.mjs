import {chromium} from 'playwright-core';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const output=resolve('simulator/.local/browser-check');
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.THINKOS_TEST_CHROME||'/Applications/Chromium.app/Contents/MacOS/Chromium'});
const page=await browser.newPage({viewport:{width:1600,height:1000}});
const errors=[];
const failedResponses=[];
page.on('pageerror',e=>errors.push(String(e)));
page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
page.on('response',async response=>{if(response.status()>=400){const request=response.request().postDataJSON();failedResponses.push({path:new URL(response.url()).pathname,status:response.status(),plugin:request?.pluginId,source:request?.url?new URL(request.url).origin:undefined,body:(await response.text().catch(()=>'' )).slice(0,1000)});}});
try{
 await page.goto(process.env.THINKOS_SIM_URL||'http://127.0.0.1:8766',{waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>['运行中','运行错误','启动失败'].includes(document.getElementById('connection')?.textContent??''),{timeout:30000});
 await page.waitForTimeout(1500);
 const status=await page.locator('#connection').textContent();
 const results=[];
 const declared=await page.locator('#page option').evaluateAll(nodes=>nodes.map(n=>n.value));
 if(declared.length<24)errors.push('Expected all 24 builtin pages; got '+declared.length);
 for(const id of declared){
  const exists=await page.locator(`#page option[value="${id}"]`).count();if(!exists)continue;
  await page.selectOption('#page',id);await page.waitForTimeout(300);
  await page.locator('#screen').screenshot({path:resolve(output,id+'.png')});
  const renderError=await page.locator('#screen').getAttribute('data-render-error');if(renderError)errors.push(id+': '+renderError);
  results.push({page:id,notice:await page.locator('#notice').textContent(),state:await page.locator('#inspect').textContent()});
 }
 await page.locator('[data-tab="logs"]').click();
 const logs=await page.locator('#inspect').textContent();
 // Live catalog icons can legitimately be blocked by the same cleartext policy
 // as the device. Preserve that evidence; do not mislabel it a renderer failure.
 const deniedResources=failedResponses.filter(r=>{try{return r.path==='/api/resource'&&JSON.parse(r.body).error?.code==='policy';}catch{return false;}});
 const expected=new Map();for(const r of deniedResources)expected.set(r.status,(expected.get(r.status)??0)+1);
 const unexpected=errors.filter(message=>{const match=/^Failed to load resource: the server responded with a status of (\d+) /.exec(message),status=Number(match?.[1]);if(match&&(expected.get(status)??0)>0){expected.set(status,expected.get(status)-1);return false;}return true;});
 const report={status,errors:unexpected,consoleErrors:errors,deniedResources,failedResponses,results,logs};
 await writeFile(resolve(output,'report.json'),JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify({status,pages:results.length,errors:unexpected,deniedResources:deniedResources.length,report:resolve(output,'report.json')}));
 if(status!=='运行中'||unexpected.length)process.exitCode=1;
}finally{await browser.close();}
