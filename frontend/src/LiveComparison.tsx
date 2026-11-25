import { useState } from 'react';
import { projectPath } from './api.ts';
import { useApi } from './useData.ts';
import type { Comparison, LiveRun } from './liveTypes.ts';
import MetricChart from './MetricChart.tsx';
import { Badge, Empty, ErrorBox, number } from './LiveShared.tsx';
export default function LiveComparison({ projectId, selected, runs, metric, metrics, setMetric, baseline, setBaseline, remove, scope }: {
  projectId: string; selected: string[]; runs: LiveRun[]; metric: string; metrics: string[]; setMetric: (value: string) => void;
  baseline: string; setBaseline: (value: string) => void; remove: (id: string) => void; scope: string;
}) {
  const [differences, setDifferences] = useState(false);
  const baselineId = selected.includes(baseline) ? baseline : selected[0];
  const query = new URLSearchParams({ runIds: selected.join(','), metricKey: metric, baselineRunId: baselineId ?? '', differencesOnly: String(differences), maxPoints: '500' });
  const result = useApi<Comparison>(selected.length >= 2 && metric ? `${projectPath(projectId)}/runs/compare?${query}` : null, scope, 5000, value => value.runs.some(run => run.status === 'RUNNING'));
  if (selected.length < 2) return <Empty title="비교할 Run을 선택하세요"><p>실험 기록에서 2~4개 Run을 선택해 주세요.</p><a href={`#/projects/${projectId}/runs`}>실험 기록으로 →</a></Empty>;
  if (!metric) return <Empty title="비교할 지표가 없습니다"><p>선택한 실험에 숫자 지표를 기록하면 비교할 수 있습니다.</p></Empty>;
  const data = result.data;
  return <><div className="compare-controls"><label>비교 지표<select aria-label="비교 지표" value={metric} onChange={event => setMetric(event.target.value)}>{metrics.map(key => <option key={key}>{key}</option>)}</select></label><label>기준 Run<select aria-label="기준 Run" value={baselineId} onChange={event => setBaseline(event.target.value)}>{selected.map(id => <option value={id} key={id}>{runs.find(run => run.runId === id)?.name ?? id}</option>)}</select></label><button onClick={result.refresh}>새로고침</button></div>
    <div className="compare-chips">{selected.map((id, index) => <span key={id}><i style={{ background: `var(--curve-${index + 1})` }}/>{runs.find(run => run.runId === id)?.name ?? id}{baselineId === id && <small>기준</small>}<button aria-label={`${runs.find(run => run.runId === id)?.name ?? id} 비교 제거`} onClick={() => remove(id)}>×</button></span>)}</div>
    <ErrorBox error={result.error} retry={result.refresh}/>{result.loading && <p role="status" className="live-empty">비교 데이터를 불러오는 중…</p>}
    {data && <><section className="panel"><div className="section-title"><div><h2>동일 지표의 학습 추이</h2><p>{metric} · {data.commonStep == null ? '모든 Run에 기록된 공통 Step이 없습니다' : `공통 Step ${data.commonStep}`}</p></div></div><MetricChart label={metric} curves={data.runs.map(run => ({ id: run.runId, name: run.name, points: run.series.points, sampled: run.series.sampled }))}/></section>
      <section className="panel compare-table"><div className="section-title"><div><h2>성능과 설정의 차이</h2><p>공통 Step에서 현재 Run 값에서 기준 Run 값을 뺀 수치입니다.</p></div><label><input type="checkbox" checked={differences} onChange={event => setDifferences(event.target.checked)}/>다른 설정만 보기</label></div><div className="live-table-scroll"><table><thead><tr><th>비교 항목</th>{data.runs.map(run => <th key={run.runId}>{run.name}{run.runId === data.baselineRunId && ' · 기준'}</th>)}</tr></thead><tbody>
        <tr><th>상태</th>{data.runs.map(run => <td key={run.runId}><Badge status={run.status}/></td>)}</tr>
        <tr><th>공통 Step {data.commonStep ?? '—'} · {metric}</th>{data.runs.map(run => <td key={run.runId}>{number(run.commonStepMetric?.value)}</td>)}</tr>
        <tr><th>기준 Run 대비 차이</th>{data.runs.map(run => <td key={run.runId}>{run.deltaFromBaseline == null ? '—' : run.runId === data.baselineRunId ? '기준' : `${run.deltaFromBaseline > 0 ? '+' : ''}${number(run.deltaFromBaseline)}`}</td>)}</tr>
        <tr><th>마지막 {metric}</th>{data.runs.map(run => <td key={run.runId}>{number(run.latestMetric?.value)}</td>)}</tr>
        <tr><th>마지막 기록 Step</th>{data.runs.map(run => <td key={run.runId}>{run.lastStep ?? '—'}</td>)}</tr>
        {data.parameters.map(row => <tr key={row.key}><th>{row.key}</th>{row.values.map((value, index) => <td key={data.runs[index].runId}>{value == null ? '—' : value === '' ? '(빈 문자열)' : value}</td>)}</tr>)}
      </tbody></table></div>{differences && !data.parameters.length && <p className="comparison-note">선택한 Run의 설정이 모두 같습니다.</p>}</section></>}
  </>;
}
