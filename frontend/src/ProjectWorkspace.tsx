import { useEffect, useState } from 'react';
import { ApiError, api, projectPath } from './api.ts';
import type { Page } from './api.ts';
import { useApi, useData } from './useData.ts';
import type { Comparison, LiveProject, LiveRun, RunData, Status, Summary } from './liveTypes.ts';
import ProjectTabs from './ProjectTabs.tsx';
import RunsScroll from './RunsScroll.tsx';
import LiveComparison from './LiveComparison.tsx';
import ProjectSettings from './ProjectSettings.tsx';
import LiveRunDetail from './LiveRunDetail.tsx';
import MetricChart from './MetricChart.tsx';
import { Badge, date, duration, Empty, ErrorBox, labels, number } from './LiveShared.tsx';
type State = { query: string; filter: Status | 'ALL'; sort: string; metric: string; selected: string[]; baseline: string };
const initial: State = { query: '', filter: 'ALL', sort: 'recent', metric: '', selected: [], baseline: '' };
export default function ProjectWorkspace({ project, view, runId, tab, scope, owner, edit, refreshProject, refreshProjects, deleted }: {
  project: LiveProject; view: string; runId: string; tab: string; scope: string; owner: boolean;
  edit: () => void; refreshProject: () => void; refreshProjects: () => void; deleted: () => void;
}) {
  const storageKey = `lf-live-exploration:${scope.split(':').at(-1)}:${project.projectId}`;
  const [state, setState] = useState<State>(() => { try { const value = JSON.parse(localStorage.getItem(storageKey) ?? '{}'); return { ...initial, ...value, selected: Array.isArray(value.selected) ? value.selected.filter((id: unknown) => typeof id === 'string' && /^\d+$/.test(id)).slice(0,4) : [] }; } catch { return initial; } });
  const [notice, setNotice] = useState('');
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState(state.query);
  const [knownMetrics, setKnownMetrics] = useState<string[]>([]);
  const [projectTotal, setProjectTotal] = useState(0);
  useEffect(() => { const timer = setTimeout(() => setSearch(state.query), 250); return () => clearTimeout(timer); }, [state.query]);
  useEffect(() => { try { localStorage.setItem(storageKey, JSON.stringify(state)); } catch {} }, [state, storageKey]);
  const path = `${projectPath(project.projectId)}/runs`;
  const listView = view === 'runs' && !runId;
  const selected = state.selected;
  const requestedMetric = state.metric || knownMetrics[0] || '';
  const query = new URLSearchParams({ page: String(listView ? page : 0), size: view === 'compare' ? '4' : '20' });
  if (listView) {
    if (search.trim()) query.set('search', search.trim());
    if (state.filter !== 'ALL') query.set('status', state.filter);
    const sort = ['high', 'low'].includes(state.sort) && !requestedMetric ? 'recent' : state.sort;
    query.set('sort', sort);
    if (['high', 'low'].includes(sort)) query.set('metricKey', requestedMetric);
  }
  if (view === 'compare') query.set('runIds', selected.join(','));
  const active = listView || view === 'overview' || (view === 'compare' && selected.length > 0);
  type RunList = Page<LiveRun> & { counts: Record<Status, number>; projectTotal?: number; availableMetrics?: string[] };
  const result = useData<RunData>(active ? `${scope}:${path}?${query}` : null, async signal => {
    const response = await api<RunList>(`${path}?${query}`, { signal });
    if (view === 'compare' && response.items.length !== selected.length) throw new ApiError('선택한 실험을 찾을 수 없습니다. 비교 선택을 다시 확인해 주세요.', 404);
    let attention: LiveRun[] = [];
    if (view === 'overview' && response.counts.RUNNING + response.counts.FAILED > 0) {
      attention = (await api<RunList>(`${path}?page=0&size=3&status=RUNNING,FAILED`, { signal })).items;
    }
    const summaries: Record<string, Summary> = {};
    const ids = [...new Set([...response.items, ...attention].map(run => run.runId))];
    if (ids.length) {
      const summaryQuery = new URLSearchParams({ runIds: ids.join(',') });
      for (const summary of await api<Summary[]>(`${path}/summaries?${summaryQuery}`, { signal })) summaries[summary.runId] = summary;
    }
    return { items: response.items, total: response.projectTotal ?? Object.values(response.counts).reduce((sum, count) => sum + count, 0), matched: response.totalElements, page: response.page, totalPages: response.totalPages, counts: response.counts, summaries, attention,
      metrics: response.availableMetrics ?? [...new Set(Object.values(summaries).flatMap(summary => summary.metrics.map(metric => metric.key!)))].sort() };
  }, 5000, data => view !== 'compare' || data.items.some(run => run.status === 'RUNNING'));
  const data = result.data, runs = data?.items ?? [];
  useEffect(() => { if (data) { setKnownMetrics(previous => JSON.stringify(previous) === JSON.stringify(data.metrics) ? previous : data.metrics); setProjectTotal(data.total); if (listView && page > 0 && page >= data.totalPages) setPage(Math.max(0, data.totalPages - 1)); } }, [data, listView, page]);
  const metrics = data?.metrics ?? knownMetrics;
  const metric = metrics.includes(state.metric) ? state.metric : metrics[0] ?? '';
  const update = (value: Partial<State>) => { if ('query' in value || 'filter' in value || 'sort' in value || 'metric' in value) setPage(0); setState(previous => ({ ...previous, ...value })); };
  const select = (id: string) => { if (!selected.includes(id) && selected.length >= 4) { setNotice('최대 4개의 Run을 선택할 수 있습니다.'); return; } setNotice(''); update({ selected: selected.includes(id) ? selected.filter(value => value !== id) : [...selected, id] }); };
  const value = (run: LiveRun) => data?.summaries[run.runId]?.metrics.find(point => point.key === metric)?.value;
  return <><div className="project-heading"><div className="project-heading-copy"><h1>{project.name}</h1>{project.description && <p>{project.description}</p>}</div><ProjectTabs projectId={project.projectId} view={view} runCount={data?.total ?? projectTotal}/></div>
    <ErrorBox error={result.error} retry={result.refresh}/>{notice && <p role="status" className="live-empty">{notice}</p>}
    {view === 'runs' && !runId && <><div className="toolbar"><div className="search"><span aria-hidden="true">⌕</span><input aria-label="Run 검색" placeholder="Run 이름 검색" value={state.query} onChange={event => update({ query: event.target.value })}/></div><label className="compact">표시 지표<select aria-label="표시 지표" value={metric} onChange={event => update({ metric: event.target.value })} disabled={!data?.metrics.length}>{!data?.metrics.length && <option value="">지표 없음</option>}{data?.metrics.map(key => <option key={key}>{key}</option>)}</select></label><select aria-label="정렬" value={state.sort} onChange={event => update({ sort: event.target.value, metric })}><option value="recent">최근 실행순</option><option value="high" disabled={!metric}>지표 높은순</option><option value="low" disabled={!metric}>지표 낮은순</option><option value="duration">소요 시간 짧은순</option></select><button onClick={result.refresh}>새로고침</button></div>
      <div className="filters">{(['ALL','RUNNING','COMPLETED','FAILED'] as const).map(status => <button key={status} className={state.filter === status ? 'selected' : ''} onClick={() => update({ filter: status })}>{status === 'ALL' ? '전체' : labels[status]} <span>{status === 'ALL' ? data?.total ?? '—' : data?.counts[status] ?? '—'}</span></button>)}</div>
      {result.loading && <p role="status" className="live-empty">실험을 불러오는 중…</p>}{data && (data.total > 0 ? <RunsScroll key={state.query + state.filter + state.sort + page}><table className="runs-table"><colgroup><col className="runs-col-select"/><col className="runs-col-name"/><col className="runs-col-status"/><col className="runs-col-metric"/><col className="runs-col-step"/><col className="runs-col-duration"/><col/></colgroup><thead><tr><th>선택</th><th>Run</th><th>상태</th><th className="num">{metric || '지표'}</th><th className="num">마지막 Step</th><th className="num">소요 시간</th><th>시작 시각</th></tr></thead><tbody>{runs.map(run => <tr key={run.runId} className={selected.includes(run.runId) ? 'chosen' : ''}><td><input type="checkbox" aria-label={`${run.name} 선택`} checked={selected.includes(run.runId)} onChange={() => select(run.runId)}/></td><td><a className="run-link" title={run.name} href={`#/projects/${project.projectId}/runs/${run.runId}/metrics`}>{run.name}</a><small className="run-id">{run.runId}</small></td><td><Badge status={run.status} connectionStatus={run.connectionStatus}/></td><td className="num metric-number">{number(value(run))}</td><td className="num">{data.summaries[run.runId]?.lastStep ?? '—'}</td><td className="num">{duration(run.startedAt, run.endedAt)}</td><td className="muted">{date(run.startedAt)}</td></tr>)}</tbody></table>{!runs.length && <div className="empty"><h3>검색 결과가 없습니다</h3><button onClick={() => update({ query:'', filter:'ALL' })}>필터 초기화</button></div>}</RunsScroll> : <Empty title="첫 실험을 준비하세요"><p>프로젝트 설정에서 API 키를 발급하고 SDK로 첫 실험을 기록하세요.</p>{owner && <a href={`#/projects/${project.projectId}/settings`}>SDK 연결 설정 →</a>}</Empty>)}
      {data && data.totalPages > 1 && <nav className="pagination" aria-label="Run 목록 페이지"><span>{data.page + 1} / {data.totalPages} · {data.matched.toLocaleString()}개</span><div><button disabled={result.loading || data.page === 0} onClick={() => setPage(data.page - 1)}>이전 페이지</button><button disabled={result.loading || data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>다음 페이지</button></div></nav>}
      {!!selected.length && <div className="selection-bar"><strong>{selected.length}개 Run 선택</strong><button onClick={() => update({ selected: [] })}>선택 해제</button><button className="primary" disabled={selected.length < 2} onClick={() => { location.hash = `/projects/${project.projectId}/compare`; }}>결과 비교 →</button></div>}
    </>}
    {view === 'overview' && <><div className="stats">{[['전체 실험',data?.total],...(['RUNNING','COMPLETED','FAILED'] as const).map(status => [labels[status], data?.counts[status]])].map(([name,count]) => <div key={String(name)}><span>{name}</span><strong>{count ?? '—'}</strong></div>)}</div>{result.loading && <p role="status" className="live-empty">실험을 불러오는 중…</p>}{data && (runs.length ? <div className="overview-grid"><OverviewChart projectId={project.projectId} run={runs[0]} metrics={data.metrics} metric={metric} setMetric={value => update({ metric: value })} scope={scope}/><section className="panel prose"><h2>확인할 실험</h2>{data.attention.map(run => <a className="attention" key={run.runId} href={`#/projects/${project.projectId}/runs/${run.runId}/metrics`}><Badge status={run.status} connectionStatus={run.connectionStatus}/><strong>{run.name}</strong><small>{run.error ?? `마지막 Step ${data.summaries[run.runId]?.lastStep ?? '—'}`}</small></a>)}{!data.counts.RUNNING && !data.counts.FAILED && <p>모든 실험이 완료되었습니다.</p>}</section></div> : <Empty title="아직 기록된 실험이 없습니다"/>)}<div className="overview-runs-action"><a href={`#/projects/${project.projectId}/runs`}>실험 기록 열기 →</a></div></>}
    {view === 'compare' && !result.loading && <LiveComparison projectId={project.projectId} selected={selected} runs={runs} metric={metric} metrics={data?.metrics ?? []} setMetric={value => update({ metric: value })} baseline={state.baseline} setBaseline={value => update({ baseline: value })} remove={select} scope={scope}/>}
    {view === 'settings' && <ProjectSettings key={`${scope}/${project.projectId}`} project={project} owner={owner} scope={scope} edit={edit} saved={refreshProjects} deleted={deleted} reload={refreshProject}/>}
    {view === 'runs' && runId && <LiveRunDetail key={`${scope}/${project.projectId}/${runId}`} projectId={project.projectId} runId={runId} tab={tab} scope={scope} owner={owner} compare={() => { if (!selected.includes(runId)) { if (selected.length >= 4) { setNotice('최대 4개의 Run을 선택할 수 있습니다.'); return; } update({ selected: [...selected, runId] }); } location.hash = `/projects/${project.projectId}/runs`; }}/ >}
    {!['runs','overview','compare','settings'].includes(view) && <Empty title="페이지를 찾을 수 없습니다"><a href={`#/projects/${project.projectId}/runs`}>실험 기록으로 돌아가기</a></Empty>}
  </>;
}
function OverviewChart({ projectId, run, metrics, metric, setMetric, scope }: { projectId: string; run: LiveRun; metrics: string[]; metric: string; setMetric: (value:string)=>void; scope:string }) {
  const query = new URLSearchParams({ metricKey: metric, maxPoints: '500' });
  const result = useApi<{ latestMetric: Comparison['runs'][0]['latestMetric']; series: Comparison['runs'][0]['series'] }>(metric ? `${projectPath(projectId)}/runs/${run.runId}/metric-series?${query}` : null, scope, run.status === 'RUNNING' ? 5000 : 0);
  return <section className="panel overview-learning-curve"><div className="section-title"><div><h2>대표 학습 곡선</h2><p>{run.name}</p></div><label className="overview-metric-picker">표시 지표<select aria-label="대표 학습 곡선 표시 지표" value={metric} onChange={event => setMetric(event.target.value)} disabled={!metrics.length}>{!metrics.length && <option value="">지표 없음</option>}{metrics.map(key => <option key={key}>{key}</option>)}</select></label></div><ErrorBox error={result.error} retry={result.refresh}/><div className="overview-metric-summary"><span className="big-number">{number(result.data?.latestMetric?.value)}</span><span>{metric || '기록된 지표 없음'}</span></div>{result.loading && <p role="status" className="live-empty">곡선을 불러오는 중…</p>}{result.data && <MetricChart label={metric} curves={[{ id: run.runId, name: run.name, points: result.data.series.points, sampled: result.data.series.sampled }]}/>}</section>;
}
