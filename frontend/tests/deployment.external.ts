// Explicitly invoked deployment smoke; never included in the normal unit test suite.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright';
import { parseJson } from '../src/api.ts';

const folder = fileURLToPath(new URL('../../.external-access/deployment-test/', import.meta.url));
const credentials = JSON.parse(await readFile(folder + 'credentials.json', 'utf8')) as {base:string;email:string;password:string};
const base = credentials.base;
const browser = await chromium.launch({channel:'msedge', headless:true});
const context = await browser.newContext({viewport:{width:1440,height:1000}});
const page = await context.newPage();
page.setDefaultTimeout(25000);
const errors:string[]=[];
page.on('pageerror', error => errors.push(error.message));
let projectId:string|undefined;
const checks:string[]=[];
const pass=(message:string)=>{checks.push(message);console.log('PASS: '+message);};
const csrf = async () => {
  await context.request.get(base + '/api/v1/auth/csrf');
  const token = (await context.cookies(base)).find(value => value.name==='XSRF-TOKEN');
  assert.ok(token); return decodeURIComponent(token.value);
};
const api=async(method:string,path:string,body?:unknown)=>{
  const headers:Record<string,string> = {};
  if(!['GET','HEAD'].includes(method))headers['X-XSRF-TOKEN']=await csrf();
  const response=await context.request.fetch(base+path,{method,headers,...(body===undefined?{}:{data:body}),timeout:45000});
  assert.ok(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`);
  const text=await response.text();return text?parseJson(text) as any:undefined;
};
try {
  await mkdir(folder,{recursive:true});
  const anonymous=await browser.newContext();
  assert.equal((await anonymous.request.get(base+'/')).status(),200);
  assert.equal((await anonymous.request.get(base+'/.env')).status(),404);
  assert.equal((await anonymous.request.get(base+'/api/v1/projects')).status(),401);
  assert.equal((await anonymous.request.get(base+'/api/v1/auth/csrf',{headers:{Origin:'https://untrusted.example'}})).status(),403);
  pass('external HTTPS, frontend, anonymous access controls and origin rejection');
  await page.goto(base+'/#/login');
  await page.getByLabel('이메일',{exact:true}).fill(credentials.email);
  await page.getByLabel('비밀번호',{exact:true}).fill(credentials.password);
  await page.getByRole('button',{name:'로그인',exact:true}).click();
  await page.getByRole('button',{name:'로그아웃',exact:true}).waitFor();
  const cookies=await context.cookies();
  for(const name of ['access_token','refresh_token']){
    const cookie=cookies.find(value=>value.name===name);
    assert.ok(cookie?.httpOnly && cookie.secure,`${name} must use Secure/HttpOnly`);
  }
  pass('browser login and Secure/HttpOnly authentication cookies');
  const created=await api('POST','/api/v1/projects',{name:'External deployment smoke',description:'Temporary end-to-end deployment verification',visibility:'PRIVATE'});
  projectId=String(created.projectId);
  const projectPath='/api/v1/projects/'+projectId;
  assert.equal((await anonymous.request.get(base+projectPath)).status(),401);
  const {rawApiKey}=await api('POST',`/api/v1/project-api-keys/issue/${projectId}`,{name:'Temporary deployment smoke key'});
  pass('private project creation, permissions and API key issuance');
  const output=folder+'sdk-result.json';
  const python=process.env.LABFLOW_SMOKE_PYTHON ?? fileURLToPath(new URL('../../sdk/.venv/Scripts/python.exe',import.meta.url));
  const child=spawn(python,[fileURLToPath(new URL('../../scripts/deploy/smoke-sdk.py',import.meta.url))],{
    env:{...process.env,PYTHONPATH:fileURLToPath(new URL('../../sdk/src/',import.meta.url)),LABFLOW_BASE_URL:base,LABFLOW_API_KEY:rawApiKey,LABFLOW_SDK_SMOKE_OUTPUT:output,LABFLOW_SPOOL_DIR:folder+'spool'},
    stdio:['ignore','pipe','pipe']
  });
  let childOutput='';child.stdout.on('data',value=>childOutput+=value);child.stderr.on('data',value=>childOutput+=value);
  const exitCode=await new Promise<number|null>((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});
  assert.equal(exitCode,0,childOutput.replaceAll(rawApiKey,'<redacted>'));
  console.log(childOutput.trim());
  const run=JSON.parse(await readFile(output,'utf8')) as {runId:string;artifactId:string;sha256:string;bytes:number};
  pass('real Python SDK round trip with 1 MiB file and completed Run');
  const runPath=projectPath+'/runs/'+run.runId;
  const metrics=await api('GET',runPath+'/records/metrics?limit=200&latest=true');
  assert.equal(metrics.items.length,2,'Retried metric request must contain two metric keys only once');
  const downloaded=await context.request.get(base+runPath+'/artifacts/'+run.artifactId);
  assert.equal(downloaded.status(),200);
  assert.equal(createHash('sha256').update(await downloaded.body()).digest('hex'),run.sha256);
  await page.goto(`${base}/#/projects/${projectId}/runs/${run.runId}/logs`);
  await page.getByText('External SDK round-trip log',{exact:true}).waitFor();
  await page.screenshot({path:folder+'external-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.screenshot({path:folder+'external-mobile.png',fullPage:true});
  pass('persisted SDK records, retry idempotency, web file download and desktop/mobile UI');
  const project=await api('GET',projectPath);
  await api('PUT',projectPath,{name:project.name,description:project.description,visibility:'PUBLIC',version:project.version});
  assert.equal((await anonymous.request.get(base+projectPath)).status(),200);
  assert.equal((await anonymous.request.get(base+runPath+'/artifacts/'+run.artifactId)).status(),200);
  pass('public project and artifact sharing');
  await api('POST','/api/v1/auth/refresh');
  await api('GET','/api/v1/auth/me');
  pass('refresh cookie rotation and authenticated session');
  const latest=await api('GET',projectPath);
  await api('DELETE',projectPath+'?version='+latest.version);
  projectId=undefined;
  assert.equal((await context.request.post(base+'/api/v1/sdk/runs',{headers:{Authorization:'Bearer '+rawApiKey},data:{name:'revoked project must fail'}})).status(),401);
  await api('POST','/api/v1/auth/logout');
  assert.equal((await context.request.get(base+'/api/v1/auth/me')).status(),401);
  pass('project cleanup, SDK key invalidation and logout');
  assert.deepEqual(errors,[]);
  await writeFile(folder+'report.json',JSON.stringify({base,checks,run,errors,passed:true},null,2));
  await anonymous.close();
} finally {
  if(projectId){
    const project=await api('GET','/api/v1/projects/'+projectId);
    await api('DELETE','/api/v1/projects/'+projectId+'?version='+project.version);
  }
  await browser.close();
}
