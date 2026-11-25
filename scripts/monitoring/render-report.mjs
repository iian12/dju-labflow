import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root=fileURLToPath(new URL('../../',import.meta.url));
const directory=(await readFile(path.join(root,'.external-access/monitoring/current-cycle.txt'),'utf8')).trim();
const report=JSON.parse(await readFile(path.join(directory,'report.json'),'utf8'));
const escape=value=>String(value??'').replace(/[&<>"']/g,value=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[value]));
const number=(value,digits=1)=>Number.isFinite(Number(value))?Number(value).toLocaleString('ko-KR',{maximumFractionDigits:digits}):'—';
const time=value=>new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',dateStyle:'medium',timeStyle:'medium'}).format(new Date(value));
const series=name=>report.timeSeries?.[name]?.[0]?.values??[];
const maximum=name=>Math.max(...series(name).map(point=>Number(point[1])).filter(Number.isFinite));
const requests=Object.values(report.raw?.endpoints??{}).reduce((sum,item)=>sum+item.requests,0);
const failures=Object.values(report.raw?.endpoints??{}).reduce((sum,item)=>sum+item.failures,0);
const overall=report.k6Summary?.metrics?.http_req_duration?.values??{};
function chart(name,title,unit,color){
  const points=series(name),valid=points.filter(point=>Number.isFinite(Number(point[1])));
  if(!valid.length)return `<section class="chart"><h3>${escape(title)}</h3><p>이 구간의 데이터가 없습니다.</p></section>`;
  const width=600,height=180,left=52,right=15,top=12,bottom=28;
  const start=Number(points[0][0]),end=Number(points.at(-1)[0]);
  const values=valid.map(point=>Number(point[1]));
  let low=Math.min(...values),high=Math.max(...values);
  if(name==='cpu'||name==='memory'){low=0;high=Math.max(20,Math.ceil(high/10)*10);}
  else {const padding=Math.max((high-low)*.15,.5);low=Math.max(0,low-padding);high+=padding;}
  if(high===low)high=low+1;
  let drawing='',open=false;
  for(const [timestamp,value] of points){
    const numeric=Number(value);if(!Number.isFinite(numeric)){open=false;continue;}
    const x=left+(Number(timestamp)-start)/Math.max(end-start,1)*(width-left-right);
    const y=top+(high-numeric)/(high-low)*(height-top-bottom);
    drawing+=`${open?'L':'M'}${x.toFixed(1)},${y.toFixed(1)} `;open=true;
  }
  const grid=[0,.5,1].map(fraction=>{
    const y=top+fraction*(height-top-bottom),value=high-fraction*(high-low);
    return `<line x1="${left}" x2="${width-right}" y1="${y}" y2="${y}" stroke="#e2e8f0"/><text x="${left-8}" y="${y+4}" text-anchor="end">${number(value)}${unit}</text>`;
  }).join('');
  const clock=timestamp=>new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(timestamp*1000));
  return `<section class="chart"><h3>${escape(title)}</h3><svg viewBox="0 0 ${width} ${height}" role="img" aria-label="${escape(title)}">${grid}<path d="${drawing}" stroke="${color}" fill="none" stroke-width="2"/><text x="${left}" y="${height-6}">${clock(start)}</text><text x="${width-right}" y="${height-6}" text-anchor="end">${clock(end)}</text></svg></section>`;
}
const endpointRows=Object.entries(report.raw?.endpoints??{}).map(([name,item])=>`<tr><td>${escape(name)}</td><td>${number(item.requests,0)}</td><td>${number(item.failures,0)}</td><td>${number(item.averageMs)}</td><td>${number(item.p95Ms)}</td><td>${number(item.p99Ms)}</td><td>${number(item.maxMs)}</td></tr>`).join('');
const countRows=Object.entries(report.expectedCounts??{}).map(([name,count])=>`<tr><td>${escape(name)}</td><td>${number(count,0)}</td><td>${number(report.databaseCounts?.[name],0)}</td><td>${report.databaseCounts?.[name]===count?'일치':'확인 필요'}</td></tr>`).join('');
const thresholdRows=Object.entries(report.k6Summary?.metrics??{}).flatMap(([metric,item])=>Object.entries(item.thresholds??{}).map(([threshold,result])=>`<tr><td>${escape(metric)}</td><td>${escape(threshold)}</td><td>${result.ok?'통과':'미달'}</td></tr>`)).join('');
const html=`<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LabFlow API 검증 ${escape(report.cycleId)}</title>
<style>body{margin:0;background:#f4f6f8;color:#18242f;font:15px/1.65 system-ui,"Malgun Gothic",sans-serif}main{max-width:1180px;margin:40px auto;padding:0 24px}h1{font-size:30px;line-height:1.3}h2{font-size:21px;margin-top:38px}h3{font-size:16px;margin:0 0 12px}a{color:#116963}header,.card,.chart,.table{background:white;border:1px solid #dce4e8;border-radius:12px;padding:24px}header p{margin:8px 0}.badge{display:inline-block;padding:4px 12px;border-radius:20px;background:${report.passed?'#d9f2e6':'#fff0d0'};color:${report.passed?'#165c40':'#805000'};font-weight:700}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-top:20px}.card strong{display:block;font-size:27px}.card span,small{color:#53636f}.charts{display:grid;grid-template-columns:1fr 1fr;gap:18px}.chart svg{width:100%;height:auto}.chart text{font:11px system-ui;fill:#566573}.table{overflow:auto;padding:8px 20px}table{border-collapse:collapse;width:100%;white-space:nowrap}th,td{padding:10px;border-bottom:1px solid #e5eaef;text-align:right}th:first-child,td:first-child{text-align:left}th{color:#53636f}code{background:#edf2f4;padding:2px 5px;border-radius:4px}.note{color:#53636f}li{margin:6px 0}@media(max-width:760px){.cards,.charts{grid-template-columns:1fr 1fr}.chart{grid-column:1/-1}main{padding:0 14px}.card{padding:16px}.card strong{font-size:22px}}</style>
<main><header><span class="badge">${report.passed?'PASS':'공개 전 수정 필요'}</span><h1>LabFlow 주요 API 검증 및 단계별 부하</h1><p>${escape(time(report.startedAt))} ~ ${escape(time(report.finishedAt))} · Asia/Seoul</p><p>테스트 대상: ${escape(report.base)} · Windows PC의 k6 → 외부 HTTPS → Raspberry Pi 5</p><p>기능 검증 후 1 → 5 → 10 가상 클라이언트. 계획 170초, 실제 ${number((Date.parse(report.loadFinishedAt)-Date.parse(report.loadStartedAt))/1000)}초. 각 반복 사이 1초 대기. 오류율 기준 초과 시 조기 종료합니다.</p><p><a href="${escape(report.grafanaUrl)}">같은 시간대 Grafana 대시보드</a> · <a href="report.json">전체 JSON 보고서</a> · <a href="k6-summary.json">k6 요약</a> · <a href="k6-raw.ndjson.gz">압축 원시 측정</a></p></header>
<div class="cards"><div class="card"><strong>${number(requests,0)}</strong><span>부하 단계 HTTP 요청</span></div><div class="card"><strong>${number(requests?100*failures/requests:0,2)}%</strong><span>예상치 못한 요청 실패</span></div><div class="card"><strong>${number(overall['p(95)'])} ms</strong><span>클라이언트 응답 p95</span></div><div class="card"><strong>${number(maximum('temperature'))} °C</strong><span>사이클 최고 SoC 온도</span></div></div>
${report.findings?.length?`<h2>발견된 문제</h2><ul>${report.findings.map(finding=>`<li><strong>${escape(finding.title)}</strong><br>${escape(finding.description)}</li>`).join('')}</ul><p><a href="backend-errors.log">서버 오류 발췌</a> · <a href="failure-analysis.json">실패 요청 집계</a></p>`:''}<h2>주요 기능 검증</h2><ul>${(report.functional?.checks??[]).map(check=>`<li>통과 · ${escape(check)}</li>`).join('')}</ul><p>실제 Python SDK의 1MiB 파일 왕복과 해시 일치, k6의 64KiB 파일 전송을 포함합니다.</p>
<h2>서버 자원 기록</h2><p class="note">Prometheus 5초 수집 기준. 최대 CPU ${number(maximum('cpu'))}%, 최대 RAM 사용률 ${number(maximum('memory'))}%, 현재 스로틀링 감지 ${maximum('throttled')>0?'있음':'없음'}. WAS 지연은 서버 내부 측정이며 위 k6 지연은 외부 왕복 측정입니다.</p><div class="charts">${chart('cpu','CPU 사용률','%','#196f69')}${chart('memory','RAM 사용률','%','#5865a6')}${chart('temperature','SoC 온도','°C','#bd642a')}${chart('serverP95','WAS 요청 p95','s','#496cac')}</div>
<h2>API별 응답 결과</h2><div class="table"><table><thead><tr><th>API</th><th>요청</th><th>실패</th><th>평균 ms</th><th>p95 ms</th><th>p99 ms</th><th>최대 ms</th></tr></thead><tbody>${endpointRows}</tbody></table></div>
<h2>k6 합격 기준</h2><div class="table"><table><thead><tr><th>지표</th><th>기준</th><th>결과</th></tr></thead><tbody>${thresholdRows}</tbody></table></div>
<h2>저장 데이터 정확성</h2><p>성공한 쓰기 수와 DB 실제 행 수를 비교했습니다. 같은 요청 ID의 중복 재전송 ${number(report.raw?.counters?.sdk_duplicate_retries??0,0)}건이 별도 행을 만들지 않는지도 포함합니다.</p><div class="table"><table><thead><tr><th>종류</th><th>예상</th><th>실제</th><th>판정</th></tr></thead><tbody>${countRows}</tbody></table></div>
<h2>정리 및 기록</h2><ul><li>테스트 프로젝트 삭제: ${report.cleanup.projectDeleted?'완료':'확인 필요'}</li><li>테스트 계정 삭제: ${report.cleanup.testUserDeleted?'완료':'확인 필요'}</li><li>로그아웃: ${report.cleanup.loggedOut?'완료':'확인 필요'}</li><li>Grafana 구간 주석: ${report.grafanaAnnotation?'등록 완료':'확인 필요'}</li><li>부하 종료 후 회복 지표까지 기록</li></ul>${report.failures?.length?`<h2>확인 사항</h2><pre>${escape(report.failures.join('\n'))}</pre>`:''}<p class="note">원시 결과와 서버 시계열은 이 보고서 폴더에 보관됩니다. 부하 단계에서 계정·키 원문은 결과 파일에 기록하지 않았으며 실행용 임시 인증 파일은 정리했습니다.</p></main></html>`;
await writeFile(path.join(directory,'report.html'),html);
console.log(path.join(directory,'report.html'));
