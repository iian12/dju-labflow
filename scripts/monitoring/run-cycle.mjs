import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { appendFile, copyFile, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { createReadStream, createWriteStream, statfsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { createGunzip } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { loadProfile } from './load-profile.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const profile=loadProfile(process.env.LABFLOW_LOAD_PROFILE || 'smoke');
const activeSeconds=profile.warmupSeconds+profile.holdSeconds+profile.rampDownSeconds;
const cycleDirectory=(await readFile(path.join(root,'.external-access/monitoring/current-cycle.txt'),'utf8')).trim();
assert.ok(path.resolve(cycleDirectory).startsWith(path.join(root,'.external-access','load-tests')+path.sep));
await mkdir(cycleDirectory,{recursive:true});
const credentials=JSON.parse(await readFile(path.join(root,'.external-access/deployment-test/credentials.json'),'utf8'));
assert.match(credentials.email,/^deployment-smoke-\d+@example\.invalid$/);
assert.equal(credentials.id,'9876543210001');
const cycleId=path.basename(cycleDirectory);
const createdAt=Math.trunc((await stat(cycleDirectory)).birthtimeMs);
const base=credentials.base;
const controlBase=profile.name==='smoke'?base:'http://127.0.0.1:15174';
const cookieJar=new Map();
const failures=[];
const report={cycleId,base,profile:profile.name,startedAt:new Date(createdAt).toISOString(),functional:null,resources:[],cleanup:{},passed:false};
report.controlTransport=profile.name==='smoke'?'public HTTPS':'encrypted SSH to loopback gateway';
let projectId,apiKey;
const runIds=[];
const contextPath=path.join(cycleDirectory,'private-context.json');
const rawPath=path.join(cycleDirectory,'k6-raw.ndjson.gz');
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
const web=async(method,route,body)=>request(method,controlBase+route,body,{
  Cookie:cookies(),'Content-Type':'application/json',
  ...(!['GET','HEAD'].includes(method)?{'X-XSRF-TOKEN':decodeURIComponent(cookieJar.get('XSRF-TOKEN')??'')}:{})
});
const sdk=(method,route,body)=>request(method,controlBase+'/api/v1/sdk'+route,body,{
  Authorization:'Bearer '+apiKey,'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()
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
  targetsUp:'min(up{job=~"raspberry-pi|labflow-backend"})',
  collectorAge:'time()-labflow_pi_collection_timestamp_seconds{job="raspberry-pi"}',
  nvmeAvailable:'node_filesystem_avail_bytes{job="raspberry-pi",mountpoint="/mnt/nvme"}',
  gcPause:'sum(rate(jvm_gc_pause_seconds_sum{job="labflow-backend"}[1m]))',
  serverP95:'histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{job="labflow-backend",uri!~"/actuator.*"}[1m])))',
};
async function sample(){
  const observation={time:Date.now()};
  await Promise.all(Object.entries(metrics).map(async([name,expr])=>{
    const values=await prom(expr);observation[name]=values.length?Number(values[0].value[1]):null;
  }));
  for(const name of ['temperature','availableMemory','targetsUp','collectorAge','nvmeAvailable'])
    assert.ok(Number.isFinite(observation[name]),'Missing safety metric: '+name);
  assert.ok(observation.targetsUp===1 && observation.collectorAge<30,'Monitoring target is down or stale');
  report.resources.push(observation);return observation;
}
function safetyReason(observation){
  const disk=statfsSync(cycleDirectory);
  if(observation.temperature>=80)return 'temperature >= 80 C';
  if(observation.availableMemory<200*1024*1024)return 'Pi available RAM < 200 MiB';
  if(observation.nvmeAvailable<5*1024**3)return 'NVMe available space < 5 GiB';
  if(disk.bavail*disk.bsize<2*1024**3)return 'PC recording disk available space < 2 GiB';
  return null;
}
async function checkpoint(phase,observation,live){
  if(report.k6Summary && ['recovery','finished'].includes(phase)){
    live={...live,vus:{value:0}};
    for(const name of ['http_reqs','http_req_failed','http_req_duration','checks','session_refreshes']){
      const values=report.k6Summary.metrics[name]?.values;if(values)live[name]=values;
    }
  }
  const status={cycleId,profile:profile.name,phase,loadStartedAt:report.loadStartedAt,
    expectedFinishAt:report.expectedFinishAt,updatedAt:new Date().toISOString(),resources:observation,k6:live,failures};
  const temporary=path.join(cycleDirectory,'status.json.tmp');
  await writeFile(temporary,JSON.stringify(status,null,2));
  await rename(temporary,path.join(cycleDirectory,'status.json'));
  await appendFile(path.join(cycleDirectory,'monitor.ndjson'),JSON.stringify(status)+'\n');
}
async function liveMetrics(){
  const response=await fetch('http://127.0.0.1:6565/v1/metrics',{signal:AbortSignal.timeout(5000)});
  assert.equal(response.status,200);
  const body=await response.json(),result={};
  for(const metric of body.data??[]){
    const attributes=metric.attributes;
    const name=metric.id.replace('{target:labflow}','');
    if(['http_reqs','http_req_failed','http_req_duration','checks','vus','session_refreshes'].includes(name)){
      if(metric.id.includes('{target:labflow}')||!result[name])result[name]=attributes.sample;
    }
  }
  return result;
}
async function connectivityProbe(){
  const result={time:new Date().toISOString()};
  await Promise.all([['external',base],['direct',controlBase]].map(async([name,url])=>{
    const started=Date.now();
    try{
      const response=await fetch(url+'/api/v1/auth/csrf',{signal:AbortSignal.timeout(5000)});
      result[name]={status:response.status,milliseconds:Date.now()-started};
      await response.arrayBuffer();
    }catch(error){result[name]={status:0,milliseconds:Date.now()-started,error:error.cause?.code??error.name};}
  }));
  (report.connectivityProbes??=[]).push(result);return result;
}
function sql(text){
  return execFileSync('C:/Windows/System32/OpenSSH/ssh.exe',[
    '-p','2222','-o','BatchMode=yes','ian@iian12.ddns.net',
    'mariadb --no-defaults --socket=/run/user/1000/labflow-mariadb.sock -u ian labflow --batch --skip-column-names'
  ],{input:text,encoding:'utf8',timeout:30000}).trim();
}
async function rawStatistics(){
  const endpoints={},counters={},windows={},statuses={},sessionClients={};
  const input=createInterface({input:createReadStream(rawPath).pipe(createGunzip()),crlfDelay:Infinity});
  for await(const line of input){
    if(!line)continue;
    const point=JSON.parse(line);if(point.type!=='Point')continue;
    const data=point.data,endpoint=data.tags?.endpoint;
    if(point.metric.startsWith('sdk_')||point.metric.startsWith('session_'))counters[point.metric]=(counters[point.metric]??0)+data.value;
    if(point.metric==='session_refreshes')sessionClients[data.tags.client??'unknown']=(sessionClients[data.tags.client??'unknown']??0)+data.value;
    if(!endpoint)continue;
    const item=endpoints[endpoint]??={durations:[],failures:0};
    if(point.metric==='http_req_duration'){
      item.durations.push(data.value);
      const status=data.tags.status??'unknown';statuses[status]=(statuses[status]??0)+1;
      const index=Math.max(0,Math.floor((Date.parse(data.time)-Date.parse(report.loadStartedAt))/300000));
      const window=windows[index]??={durations:[],failures:0,endpoints:{}};
      window.durations.push(data.value);
      (window.endpoints[endpoint]??=[]).push(data.value);
    }
    if(point.metric==='http_req_failed')item.failures+=data.value;
  }
  function summarize(durations){
    const values=durations.sort((a,b)=>a-b),count=values.length;
    return {requests:count,averageMs:values.reduce((sum,value)=>sum+value,0)/count,
      p95Ms:values[Math.ceil(count*.95)-1],p99Ms:values[Math.ceil(count*.99)-1],maxMs:values[count-1],
      over500:values.filter(value=>value>500).length,over1000:values.filter(value=>value>1000).length};
  }
  const results={};
  for(const [endpoint,item] of Object.entries(endpoints)){
    if(!item.durations.length)continue;
    results[endpoint]={...summarize(item.durations),failures:item.failures};
  }
  return {endpoints:results,counters,statuses,sessionClients,windows:Object.entries(windows).map(([index,window])=>({
    start:new Date(Date.parse(report.loadStartedAt)+Number(index)*300000).toISOString(),
    ...summarize(window.durations),endpoints:Object.fromEntries(Object.entries(window.endpoints).map(([name,values])=>[name,summarize(values)])),
  }))};
}

let activeChild,sessionRelay,controlTunnel,controlLog;
try{
  const functionalPath=path.join(root,'.external-access/deployment-test/report.json');
  assert.ok((await stat(functionalPath)).mtimeMs>=createdAt,'Functional report must come from this cycle');
  report.functional=JSON.parse(await readFile(functionalPath,'utf8'));assert.equal(report.functional.passed,true);
  for(const name of ['report.json','external-desktop.png','external-mobile.png']){
    await copyFile(path.join(root,'.external-access/deployment-test',name),path.join(cycleDirectory,'functional-'+name));
  }
  report.baseline=await sample();
  assert.equal(safetyReason(report.baseline),null,'Baseline resource safety check');
  if(profile.name!=='smoke'){
    controlLog=createWriteStream(path.join(cycleDirectory,'control-ssh.log'));
    controlTunnel=spawn('C:/Windows/System32/OpenSSH/ssh.exe',[
      '-N','-T','-p','2222','-o','BatchMode=yes','-o','ConnectTimeout=10',
      '-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-o','ExitOnForwardFailure=yes',
      '-L','127.0.0.1:15174:127.0.0.1:5174','ian@iian12.ddns.net',
    ],{stdio:['ignore','ignore','pipe']});
    controlTunnel.stderr.pipe(controlLog);
    let ready=false;
    for(let attempt=0;attempt<20;attempt++){
      if(controlTunnel.exitCode!==null)throw new Error('Private control SSH tunnel exited');
      try{const response=await fetch(controlBase+'/api/v1/auth/csrf',{signal:AbortSignal.timeout(1000)});await response.arrayBuffer();if(response.ok){ready=true;break;}}catch{}
      await delay(500);
    }
    assert.ok(ready,'Private control SSH tunnel must be ready');
  }
  await request('POST',controlBase+'/api/v1/auth/login',{email:credentials.email,password:credentials.password},{'Content-Type':'application/json'},[204]);
  await web('GET','/api/v1/auth/csrf');
  projectId=String((await web('POST','/api/v1/projects',{name:'Load verification '+cycleId,description:'Temporary bounded API load verification',visibility:'PRIVATE'})).projectId);
  apiKey=(await web('POST',`/api/v1/project-api-keys/issue/${projectId}`,{name:'Temporary cycle '+cycleId})).rawApiKey;
  for(let i=0;i<10;i++){
    const runId=String((await sdk('POST','/runs',{name:`Load client ${i+1}`,metadata:{testCycle:cycleId}})).runId);
    runIds.push(runId);
    await sdk('PUT',`/runs/${runId}/parameters`,{parameters:{optimizer:'adam',batch_size:'32'}});
    await sdk('POST',`/runs/${runId}/metrics`,{metrics:{loss:1,accuracy:.5},step:0,elapsedTime:0});
  }
  const sessionToken=randomBytes(32).toString('hex');
  if(profile.name!=='smoke'){
    sessionRelay=createServer((request,response)=>{
      response.setHeader('Cache-Control','no-store');
      const token=Buffer.from(String(request.headers['x-labflow-test-token']??''));
      if(request.method!=='GET'||request.url!=='/session'||token.length!==sessionToken.length||
          !timingSafeEqual(token,Buffer.from(sessionToken))){response.writeHead(403);response.end();return;}
      response.setHeader('Content-Type','application/json');response.end(JSON.stringify({cookie:cookies()}));
    });
    await new Promise((resolve,reject)=>{sessionRelay.once('error',reject);sessionRelay.listen(6567,'127.0.0.1',resolve);});
    assert.equal((await fetch('http://127.0.0.1:6567/session')).status,403,'Private relay must reject unsigned requests');
  }
  report.loadStartedAt=new Date().toISOString();
  report.expectedFinishAt=new Date(Date.parse(report.loadStartedAt)+(activeSeconds+profile.recoverySeconds)*1000).toISOString();
  report.phases=[
    {name:'warmup',start:report.loadStartedAt,end:new Date(Date.parse(report.loadStartedAt)+profile.warmupSeconds*1000).toISOString()},
    {name:'steady',start:new Date(Date.parse(report.loadStartedAt)+profile.warmupSeconds*1000).toISOString(),end:new Date(Date.parse(report.loadStartedAt)+(profile.warmupSeconds+profile.holdSeconds)*1000).toISOString()},
    {name:'recovery',start:new Date(Date.parse(report.loadStartedAt)+activeSeconds*1000).toISOString(),end:report.expectedFinishAt},
  ];
  await writeFile(contextPath,JSON.stringify({cycleId,base,projectId,apiKey,cookie:cookies(),runIds,
    loadStartedAtMs:Date.parse(report.loadStartedAt),sessionUrl:'http://127.0.0.1:6567/session',sessionToken}),{mode:0o600});
  report.workload={virtualUsers:[1,5,10],durationSeconds:activeSeconds,recoverySeconds:profile.recoverySeconds,
    warmupSeconds:profile.warmupSeconds,steadySeconds:profile.holdSeconds,sleepPerIterationSeconds:1,seedRuns:10,seedMetrics:20,
    errorRateAborts:profile.name!=='soak',
    thresholds:{unexpectedErrorRate:'<1%',overallP95:'<1000ms',overallP99:'<2000ms',readP95:'<500ms',writeP95:'<1000ms',fileP95:'<2000ms'}};
  const log=createWriteStream(path.join(cycleDirectory,'k6-console.log'));
  const child=spawn('C:/Program Files/k6/k6.exe',['run','--quiet','--no-usage-report','--address','127.0.0.1:6565','--out','json='+rawPath,path.join(root,'scripts/monitoring/api-load.js')],{
    cwd:root,env:{...process.env,LABFLOW_LOAD_CONTEXT:contextPath,LABFLOW_K6_SUMMARY:summaryPath},stdio:['ignore','pipe','pipe']
  });
  activeChild=child;
  child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});
  let finished=false;
  const exited=new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',code=>{finished=true;resolve(code);});});
  const deadline=Date.now()+(activeSeconds+90)*1000;
  let lastRefresh=Date.now(),lastCheckpoint=0,monitorFailures=0,lastLive;
  const keepSession=async()=>{
    if(Date.now()-lastRefresh<profile.refreshSeconds*1000)return;
    await web('GET','/api/v1/auth/csrf');await web('POST','/api/v1/auth/refresh');
    report.sessionRotations=(report.sessionRotations??0)+1;
    lastRefresh=Date.now();
  };
  console.log(`Started ${profile.name}: warmup ${profile.warmupSeconds}s, hold ${profile.holdSeconds}s at 10 VUs, recovery ${profile.recoverySeconds}s.`);
  console.log('Expected finish: '+report.expectedFinishAt+'; live records: '+path.join(cycleDirectory,'status.json'));
  while(!finished){
    try{
      const observation=await sample();
      const reason=safetyReason(observation);
      if(reason||Date.now()>deadline){
        failures.push('Safety stop: '+(reason??'run deadline exceeded'));child.kill('SIGINT');
        break;
      }
      await keepSession();
      if(Date.now()-lastCheckpoint>=30000){
        const elapsed=(Date.now()-Date.parse(report.loadStartedAt))/1000;
        try{lastLive=await liveMetrics();}catch(error){
          if(elapsed>=30)throw new Error('k6 control API: '+error.message);
          lastLive={starting:true};
        }
        const probes=await connectivityProbe();
        const phase=elapsed<profile.warmupSeconds?'warmup':'steady';
        await checkpoint(phase,observation,lastLive);lastCheckpoint=Date.now();
        console.log(JSON.stringify({phase,elapsedSeconds:Math.round(elapsed),temperature:observation.temperature,
          memoryPercent:observation.memory,k6:lastLive,probes}));
      }
      monitorFailures=0;
    }catch(error){
      monitorFailures++;
      console.error('Monitor/session check failed: '+error.message);
      if(monitorFailures>=3){failures.push('Safety stop: monitoring/session unavailable: '+error.message);child.kill('SIGINT');break;}
    }
    await Promise.race([exited,delay(5000)]);
  }
  const killTimer=setTimeout(()=>{if(!finished)child.kill('SIGKILL');},15000);
  try{report.k6ExitCode=await exited;}finally{clearTimeout(killTimer);activeChild=undefined;}
  await new Promise(resolve=>log.end(resolve));
  report.loadFinishedAt=new Date().toISOString();
  report.completedLoadDuration=Date.parse(report.loadFinishedAt)-Date.parse(report.loadStartedAt)>=(activeSeconds-10)*1000;
  if(report.k6ExitCode===0)assert.ok(report.completedLoadDuration,'The requested load duration must finish');
  report.raw=await rawStatistics();
  report.k6Summary=JSON.parse(await readFile(summaryPath,'utf8'));
  if(report.k6ExitCode!==0)failures.push('k6 returned '+report.k6ExitCode+'; inspect threshold results');
  if(profile.name!=='smoke'&&report.k6ExitCode===0){
    assert.ok((report.raw.counters.session_refreshes??0)>=10,'All VUs must exercise session refresh');
    if(profile.name==='soak')assert.deepEqual(Object.keys(report.raw.sessionClients).sort(),Array.from({length:10},(_,i)=>String(i+1)).sort());
  }
  if(report.completedLoadDuration && [0,99].includes(report.k6ExitCode)){
    const recoveryDeadline=Date.parse(report.loadStartedAt)+(activeSeconds+profile.recoverySeconds)*1000;
    console.log('Load complete. Observing recovery until '+new Date(recoveryDeadline).toISOString());
    while(Date.now()<recoveryDeadline){
      const observation=await sample();await keepSession();
      await checkpoint('recovery',observation,lastLive);
      const reason=safetyReason(observation);if(reason){failures.push('Recovery safety condition: '+reason);break;}
      await delay(Math.min(5000,Math.max(0,recoveryDeadline-Date.now())));
    }
  }
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
  await sample();
}catch(error){
  failures.push(error.stack??error.message);console.error(error.message);
}finally{
  if(activeChild)activeChild.kill('SIGKILL');
  if(sessionRelay)await new Promise(resolve=>sessionRelay.close(resolve));
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
  if(controlTunnel && controlTunnel.exitCode===null && controlTunnel.signalCode===null){
    await new Promise(resolve=>{controlTunnel.once('close',resolve);controlTunnel.kill();});
  }
  if(controlLog && !controlLog.writableFinished)await new Promise(resolve=>controlLog.end(resolve));
  await rm(contextPath,{force:true});
  // Keep the ignored recovery credentials if a network failure prevented cleanup.
  // They are removed only after the guarded temporary account has been deleted.
  if(report.cleanup.testUserDeleted){
    await rm(path.join(root,'.external-access/deployment-test/credentials.json'),{force:true});
    await rm(path.join(root,'.external-access/deployment-test/seed.sql'),{force:true});
  }
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
      },body:JSON.stringify({dashboardUID:'labflow-performance',time:Date.parse(report.loadStartedAt??report.startedAt),timeEnd:Date.now(),tags:['labflow','api-cycle','k6',profile.name,cycleId],text:`API cycle ${cycleId}: ${report.passed?'PASS':'REVIEW'}; ${profile.name} warmup ${profile.warmupSeconds}s / hold ${profile.holdSeconds}s / recovery ${profile.recoverySeconds}s.`})});
      assert.equal(response.status,200);report.grafanaAnnotation=await response.json();
    }catch(error){failures.push('Grafana annotation: '+error.message);report.passed=false;}
  }
  await writeFile(path.join(cycleDirectory,'report.json'),JSON.stringify(report,null,2));
  await checkpoint('finished',report.resources.at(-1),{passed:report.passed});
  execFileSync(process.execPath,[path.join(root,'scripts/monitoring/render-report.mjs')],{cwd:root,stdio:'inherit'});
  console.log(JSON.stringify({passed:report.passed,k6ExitCode:report.k6ExitCode,persistenceVerified:report.persistenceVerified,cleanup:report.cleanup,report:path.join(cycleDirectory,'report.json'),grafana:report.grafanaUrl},null,2));
  process.exitCode=report.passed?0:1;
}
