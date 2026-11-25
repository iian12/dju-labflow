import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { copyFile, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { createGzip } from 'node:zlib';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../../',import.meta.url));
const cycleDirectory=(await readFile(path.join(root,'.external-access/monitoring/current-cycle.txt'),'utf8')).trim();
assert.ok(path.resolve(cycleDirectory).startsWith(path.join(root,'.external-access','load-tests')+path.sep));
await mkdir(cycleDirectory,{recursive:true});
const credentials=JSON.parse(await readFile(path.join(root,'.external-access/deployment-test/credentials.json'),'utf8'));
assert.match(credentials.email,/^deployment-smoke-\d+@example\.invalid$/);
assert.equal(credentials.id,'9876543210001');
const cycleId=path.basename(cycleDirectory);
const createdAt=Math.trunc((await stat(cycleDirectory)).birthtimeMs);
const base=credentials.base;
const cookieJar=new Map();
const failures=[];
const report={cycleId,base,startedAt:new Date(createdAt).toISOString(),functional:null,resources:[],cleanup:{},passed:false};
let projectId,apiKey;
const runIds=[];
const contextPath=path.join(cycleDirectory,'private-context.json');
const rawPath=path.join(cycleDirectory,'k6-raw.ndjson');
const summaryPath=path.join(cycleDirectory,'k6-summary.json');
const delay=milliseconds=>new Promise(resolve=>setTimeout(resolve,milliseconds));
function parseJson(text){
  return JSON.parse(text.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,token=>
    /^-?\d+$/.test(token)&&!Number.isSafeInteger(Number(token))?JSON.stringify(token):token));
}
async function request(method,url,body,headers={},expected=[200,201,204]){
  const response=await fetch(url,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
  for(const cookie of response.headers.getSetCookie()){
    const first=cookie.split(';',1)[0],separator=first.indexOf('=');
    cookieJar.set(first.slice(0,separator),first.slice(separator+1));
  }
  const text=await response.text();
  assert.ok(expected.includes(response.status),`${method} ${new URL(url).pathname}: ${response.status}`);
  return text?parseJson(text):undefined;
}
const cookies=()=>[...cookieJar].map(([key,value])=>`${key}=${value}`).join('; ');
const web=async(method,route,body)=>request(method,base+route,body,{
  Cookie:cookies(),'Content-Type':'application/json',
  ...(!['GET','HEAD'].includes(method)?{'X-XSRF-TOKEN':decodeURIComponent(cookieJar.get('XSRF-TOKEN')??'')}:{})
});
const sdk=(method,route,body)=>request(method,base+'/api/v1/sdk'+route,body,{
  Authorization:'Bearer '+apiKey,'Content-Type':'application/json','X-Request-ID':crypto.randomUUID()
});
const prom=async(expr,start,end)=>{
  const route=start===undefined?'/api/v1/query':'/api/v1/query_range';
  const query=new URLSearchParams({query:expr,...(start===undefined?{}:{start:String(start),end:String(end),step:'5s'})});
  const response=await fetch('http://127.0.0.1:9090'+route+'?'+query,{signal:AbortSignal.timeout(10000)});
  const json=await response.json();assert.equal(json.status,'success',expr);return json.data.result;
};
const metrics={
  cpu:'100 * (1 - avg(rate(node_cpu_seconds_total{job="raspberry-pi",mode="idle"}[1m])))',
  memory:'100 * (1 - node_memory_MemAvailable_bytes{job="raspberry-pi"}/node_memory_MemTotal_bytes{job="raspberry-pi"})',
  availableMemory:'node_memory_MemAvailable_bytes{job="raspberry-pi"}',
  temperature:'labflow_pi_temperature_celsius{job="raspberry-pi"}',
  throttled:'labflow_pi_throttled{job="raspberry-pi"}',
  heap:'sum(jvm_memory_used_bytes{job="labflow-backend",area="heap"})',
  connections:'sum(hikaricp_connections_active{job="labflow-backend"})',
  pendingConnections:'sum(hikaricp_connections_pending{job="labflow-backend"})',
  serverP95:'histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{job="labflow-backend",uri!~"/actuator.*"}[1m])))',
};
async function sample(){
  const observation={time:Date.now()};
  for(const [name,expr] of Object.entries(metrics)){
    const values=await prom(expr);observation[name]=values.length?Number(values[0].value[1]):null;
  }
  report.resources.push(observation);return observation;
}
function sql(text){
  return execFileSync('C:/Windows/System32/OpenSSH/ssh.exe',[
    '-p','2222','-o','BatchMode=yes','ian@iian12.ddns.net',
    'mariadb --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian labflow --batch --skip-column-names'
  ],{input:text,encoding:'utf8',timeout:30000}).trim();
}
async function rawStatistics(){
  const endpoints={},counters={};
  const input=createInterface({input:createReadStream(rawPath),crlfDelay:Infinity});
  for await(const line of input){
    if(!line)continue;
    const point=JSON.parse(line);if(point.type!=='Point')continue;
    const data=point.data,endpoint=data.tags?.endpoint;
    if(point.metric.startsWith('sdk_'))counters[point.metric]=(counters[point.metric]??0)+data.value;
    if(!endpoint)continue;
    const item=endpoints[endpoint]??={durations:[],failures:0};
    if(point.metric==='http_req_duration')item.durations.push(data.value);
    if(point.metric==='http_req_failed')item.failures+=data.value;
  }
  const results={};
  for(const [endpoint,item] of Object.entries(endpoints)){
    const values=item.durations.sort((a,b)=>a-b),count=values.length;
    if(!count)continue;
    results[endpoint]={requests:count,failures:item.failures,averageMs:values.reduce((sum,value)=>sum+value,0)/count,
      p95Ms:values[Math.ceil(count*.95)-1],p99Ms:values[Math.ceil(count*.99)-1],maxMs:values[count-1]};
  }
  return {endpoints:results,counters};
}

try{
  const functionalPath=path.join(root,'.external-access/deployment-test/report.json');
  assert.ok((await stat(functionalPath)).mtimeMs>=createdAt,'Functional report must come from this cycle');
  report.functional=JSON.parse(await readFile(functionalPath,'utf8'));assert.equal(report.functional.passed,true);
  for(const name of ['report.json','external-desktop.png','external-mobile.png']){
    await copyFile(path.join(root,'.external-access/deployment-test',name),path.join(cycleDirectory,'functional-'+name));
  }
  await request('POST',base+'/api/v1/auth/login',{email:credentials.email,password:credentials.password},{'Content-Type':'application/json'},[204]);
  await web('GET','/api/v1/auth/csrf');
  projectId=String((await web('POST','/api/v1/projects',{name:'Load verification '+cycleId,description:'Temporary bounded API load verification',visibility:'PRIVATE'})).projectId);
  apiKey=(await web('POST',`/api/v1/project-api-keys/issue/${projectId}`,{name:'Temporary cycle '+cycleId})).rawApiKey;
  for(let i=0;i<10;i++){
    const runId=String((await sdk('POST','/runs',{name:`Load client ${i+1}`,metadata:{testCycle:cycleId}})).runId);
    runIds.push(runId);
    await sdk('PUT',`/runs/${runId}/parameters`,{parameters:{optimizer:'adam',batch_size:'32'}});
    await sdk('POST',`/runs/${runId}/metrics`,{metrics:{loss:1,accuracy:.5},step:0,elapsedTime:0});
  }
  await writeFile(contextPath,JSON.stringify({cycleId,base,projectId,apiKey,cookie:cookies(),runIds}));
  report.loadStartedAt=new Date().toISOString();
  report.workload={virtualUsers:[1,5,10],durationSeconds:170,sleepPerIterationSeconds:1,seedRuns:10,seedMetrics:20,
    thresholds:{unexpectedErrorRate:'<1%',overallP95:'<1000ms',overallP99:'<2000ms',readP95:'<500ms',writeP95:'<1000ms',fileP95:'<2000ms'}};
  const log=createWriteStream(path.join(cycleDirectory,'k6-console.log'));
  const child=spawn('C:/Program Files/k6/k6.exe',['run','--quiet','--out','json='+rawPath,path.join(root,'scripts/monitoring/api-load.js')],{
    cwd:root,env:{...process.env,LABFLOW_LOAD_CONTEXT:contextPath,LABFLOW_K6_SUMMARY:summaryPath},stdio:['ignore','pipe','pipe']
  });
  child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});
  let finished=false;
  const exited=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>{finished=true;resolve(code);});});
  const deadline=Date.now()+240000;
  console.log('Started bounded k6 cycle: 1 -> 5 -> 10 VUs, approximately 3 minutes.');
  while(!finished){
    const observation=await sample();
    if(observation.temperature>=80||observation.availableMemory<200*1024*1024||Date.now()>deadline){
      failures.push('Safety stop: temperature, memory or run deadline exceeded');child.kill('SIGINT');
    }
    await Promise.race([exited,delay(5000)]);
  }
  report.k6ExitCode=await exited;
  await new Promise(resolve=>log.end(resolve));
  report.loadFinishedAt=new Date().toISOString();
  report.raw=await rawStatistics();
  report.k6Summary=JSON.parse(await readFile(summaryPath,'utf8'));
  if(report.k6ExitCode!==0)failures.push('k6 returned '+report.k6ExitCode+'; inspect threshold results');
  for(const runId of runIds){
    await sdk('POST',`/runs/${runId}/complete`);
    assert.equal((await sdk('GET',`/runs/${runId}`)).status,'COMPLETED');
  }
  const countsSql=[['runs','run_entity'],['metrics','run_metric_entity'],['logs','run_log_entity'],['texts','run_text_entity'],['system','run_system_metric_entity'],['parameters','run_parameter_entity'],['artifacts','run_artifact_entity']]
    .map(([name,table])=>table==='run_entity'?`SELECT '${name}',COUNT(*) FROM run_entity WHERE project_id=${projectId};`:
      `SELECT '${name}',COUNT(*) FROM ${table} t JOIN run_entity r ON r.id=t.run_id WHERE r.project_id=${projectId};`).join('\n');
  report.databaseCounts=Object.fromEntries(sql(countsSql).split(/\r?\n/).map(line=>{const [name,value]=line.split('\t');return[name,Number(value)];}));
  const counters=report.raw.counters;
  report.expectedCounts={runs:10,metrics:20+2*(counters.sdk_metric_batches??0),logs:counters.sdk_log_records??0,
    texts:counters.sdk_text_records??0,system:counters.sdk_system_records??0,parameters:20,artifacts:counters.sdk_artifacts??0};
  assert.deepEqual(report.databaseCounts,report.expectedCounts,'Persisted counts must match successful writes; retries must not duplicate records');
  report.persistenceVerified=true;
  await web('POST','/api/v1/auth/refresh');await web('GET','/api/v1/auth/me');
  for(let i=0;i<3;i++){await delay(5000);await sample();}
}catch(error){
  failures.push(error.stack??error.message);console.error(error.message);
}finally{
  if(projectId){
    try{
      const project=await web('GET','/api/v1/projects/'+projectId);
      await web('DELETE','/api/v1/projects/'+projectId+'?version='+project.version);
      report.cleanup.projectDeleted=true;
    }catch(error){failures.push('Project cleanup: '+error.message);}
  }
  try{await web('POST','/api/v1/auth/logout');report.cleanup.loggedOut=true;}catch(error){failures.push('Logout: '+error.message);}
  try{
    const guard=`u.id=${credentials.id} AND u.email='${credentials.email}'`;
    sql(`START TRANSACTION;\nDELETE t FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE ${guard};\nDELETE v FROM email_verifications v JOIN users u ON u.id=v.user_id WHERE ${guard};\nDELETE u FROM users u WHERE ${guard} AND NOT EXISTS(SELECT 1 FROM project_entity p WHERE p.created_by=u.id);\nCOMMIT;`);
    const remaining=sql(`SELECT COUNT(*) FROM users WHERE id=${credentials.id};`);
    assert.equal(remaining,'0');report.cleanup.testUserDeleted=true;
  }catch(error){failures.push('Temporary user cleanup: '+error.message);}
  await rm(contextPath,{force:true});
  await rm(path.join(root,'.external-access/deployment-test/credentials.json'),{force:true});
  await rm(path.join(root,'.external-access/deployment-test/seed.sql'),{force:true});
  report.finishedAt=new Date().toISOString();
  report.failures=failures;report.passed=failures.length===0;
  try{
    report.timeSeries={};
    for(const [name,expr] of Object.entries(metrics))report.timeSeries[name]=await prom(expr,Math.floor(createdAt/1000),Math.ceil(Date.now()/1000));
    await writeFile(path.join(cycleDirectory,'server-timeseries.json'),JSON.stringify(report.timeSeries,null,2));
  }catch(error){failures.push('Metric archive: '+error.message);report.passed=false;}
  report.grafanaUrl='http://localhost:3000/d/labflow-performance?from='+createdAt+'&to='+Date.now();
  if(process.env.LABFLOW_GRAFANA_USER&&process.env.LABFLOW_GRAFANA_PASSWORD){
    try{
      const response=await fetch('http://127.0.0.1:3000/api/annotations',{method:'POST',headers:{
        'Content-Type':'application/json',Authorization:'Basic '+Buffer.from(process.env.LABFLOW_GRAFANA_USER+':'+process.env.LABFLOW_GRAFANA_PASSWORD).toString('base64')
      },body:JSON.stringify({dashboardUID:'labflow-performance',time:createdAt,timeEnd:Date.now(),tags:['labflow','api-cycle','k6',cycleId],text:`API cycle ${cycleId}: ${report.passed?'PASS':'REVIEW'}; functional browser/SDK and bounded 1/5/10 VU load.`})});
      assert.equal(response.status,200);report.grafanaAnnotation=await response.json();
    }catch(error){failures.push('Grafana annotation: '+error.message);report.passed=false;}
  }
  await writeFile(path.join(cycleDirectory,'report.json'),JSON.stringify(report,null,2));
  try{await pipeline(createReadStream(rawPath),createGzip(),createWriteStream(rawPath+'.gz'));await rm(rawPath);}catch{}
  execFileSync(process.execPath,[path.join(root,'scripts/monitoring/render-report.mjs')],{cwd:root,stdio:'inherit'});
  console.log(JSON.stringify({passed:report.passed,k6ExitCode:report.k6ExitCode,persistenceVerified:report.persistenceVerified,cleanup:report.cleanup,report:path.join(cycleDirectory,'report.json'),grafana:report.grafanaUrl},null,2));
  process.exitCode=report.passed?0:1;
}
