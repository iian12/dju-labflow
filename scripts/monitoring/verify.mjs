import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const prometheus='http://127.0.0.1:9090';
const output=new URL('../../.external-access/monitoring/',import.meta.url);
const json=async(path)=>{
  const response=await fetch(prometheus+path,{signal:AbortSignal.timeout(10000)});
  assert.equal(response.status,200,path);return response.json();
};
let targets;
for(let attempt=0;attempt<30;attempt++){
  targets=(await json('/api/v1/targets')).data.activeTargets;
  if(targets.length===3&&targets.every(target=>target.health==='up'))break;
  await new Promise(resolve=>setTimeout(resolve,1000));
}
assert.equal(targets.length,3);
assert.ok(targets.every(target=>target.health==='up'),JSON.stringify(targets.map(target=>({job:target.labels.job,health:target.health,lastError:target.lastError}))));
const query=async(expr)=>{
  const result=await json('/api/v1/query?query='+encodeURIComponent(expr));
  assert.equal(result.status,'success',expr);return result.data.result;
};
const required={
  cpu:'node_cpu_seconds_total{job="raspberry-pi"}',
  nvme:'node_filesystem_avail_bytes{job="raspberry-pi",mountpoint="/mnt/nvme"}',
  temperature:'labflow_pi_temperature_celsius{job="raspberry-pi"}',
  throttle:'labflow_pi_throttled{job="raspberry-pi"}',
  jvm:'jvm_memory_used_bytes{job="labflow-backend",area="heap"}',
  connections:'hikaricp_connections_max{job="labflow-backend"}',
  timerAge:'time()-labflow_pi_collection_timestamp_seconds{job="raspberry-pi"}'
};
const samples={};
for(const [name,expr] of Object.entries(required)){
  const results=await query(expr);assert.ok(results.length>0,'Missing metric: '+name);
  samples[name]=results.map(result=>({labels:result.metric,value:result.value}));
}
assert.ok(Number(samples.timerAge[0].value[1])<30,'Pi collection timer is stale');
const dashboard=JSON.parse(await readFile(new URL('../../monitoring/grafana/dashboards/labflow-performance.json',import.meta.url),'utf8'));
const dashboardQueries=[];
for(const panel of dashboard.panels){
  for(const target of panel.targets){
    const result=await query(target.expr);
    dashboardQueries.push({panel:panel.title,refId:target.refId,series:result.length});
  }
}
const grafana=await fetch('http://127.0.0.1:3000/api/health',{signal:AbortSignal.timeout(10000)});
assert.equal(grafana.status,200);
assert.equal((await grafana.json()).database,'ok');
await mkdir(output,{recursive:true});
await writeFile(new URL('verification.json',output),JSON.stringify({
  checkedAt:new Date().toISOString(),passed:true,
  targets:targets.map(target=>({job:target.labels.job,health:target.health,scrapeUrl:target.scrapeUrl,lastError:target.lastError})),
  samples,dashboardQueries,grafanaDashboard:'http://localhost:3000/d/labflow-performance'
},null,2));
console.log('PASS: three Prometheus targets UP, CPU/NVMe/temperature/throttle/JVM/DB metrics present');
console.log('PASS: all '+dashboardQueries.length+' dashboard PromQL queries parsed; Grafana database healthy');
console.log('Report: '+fileURLToPath(new URL('verification.json',output)));
