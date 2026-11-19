import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import ThemeToggle from './ThemeToggle.tsx';
import { readRecordsApi, recordKind, recordKinds, recordNavigation, recordPageUrl, RecordReadError } from './runRecordsApi.ts';
import type { ListPage, RecordItem, RecordKind, RecordLocation, RecordPage, SavedProject, SavedRun } from './runRecordsApi.ts';
import './records.css';

function useRead<T>(key: string, loader: (signal: AbortSignal) => Promise<T>, interval = 0) {
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<{ key: string; data?: T; error?: unknown; loading: boolean }>({ key, loading: true });
  useEffect(() => {
    const controller = new AbortController();
    let busy = false;
    async function load() {
      if (busy) return;
      busy = true;
      try {
        const data = await loader(controller.signal);
        if (!controller.signal.aborted) setState({ key, data, loading: false });
      } catch (error) {
        if (!controller.signal.aborted) setState(previous => ({ key, data: previous.key === key ? previous.data : undefined, loading: false, error }));
      } finally { busy = false; }
    }
    void load();
    const timer = interval ? setInterval(() => { if (!document.hidden) void load(); }, interval) : undefined;
    return () => { controller.abort(); if (timer) clearInterval(timer); };
  }, [key, loader, interval, version]);
  return { ...(state.key === key ? state : { key, loading: true, data: undefined, error: undefined }), refresh: () => setVersion(value => value + 1) };
}

const route = (project: string, run = '', kind: RecordKind = 'metrics') => `#/records/${encodeURIComponent(project)}${run ? `/${encodeURIComponent(run)}/${kind}` : ''}`;
const date = (value?: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '—';
function ReadError({ error, retry }: { error?: unknown; retry: () => void }) {
  return error ? <div className="record-error" role="alert"><span>{error instanceof Error ? error.message : '기록을 불러오지 못했습니다.'}</span><button onClick={retry}>다시 시도</button>{error instanceof RecordReadError && error.status === 401 && <a href="#/login">로그인</a>}</div> : null;
}

export default function RunRecordsBrowser({ projectId, runId, tab }: { projectId: string; runId: string; tab: string }) {
  const [projectInput, setProjectInput] = useState(projectId);
  const [runInput, setRunInput] = useState(runId);
  const [listPage, setListPage] = useState({ project: projectId, page: 0 });
  const pageNumber = listPage.project === projectId ? listPage.page : 0;
  useEffect(() => { setProjectInput(projectId); setRunInput(runId); }, [projectId, runId]);
  useEffect(() => { document.title = '실험 기록 · LabFlow Studio'; }, []);
  const loadProjects = useCallback(async (signal: AbortSignal) => {
    const first = await readRecordsApi<ListPage<SavedProject>>('/api/v1/projects?page=0&size=100', signal);
    const items = [...first.items];
    for (let page = 1; page < first.totalPages; page++) items.push(...(await readRecordsApi<ListPage<SavedProject>>(`/api/v1/projects?page=${page}&size=100`, signal)).items);
    return items;
  }, []);
  const projects = useRead('saved-projects', loadProjects);
  const runListPath = projectId ? `/api/v1/projects/${encodeURIComponent(projectId)}/runs?page=${pageNumber}&size=20` : '';
  const loadRuns = useCallback((signal: AbortSignal) => runListPath ? readRecordsApi<ListPage<SavedRun>>(runListPath, signal) : Promise.resolve(null), [runListPath]);
  const runs = useRead(runListPath, loadRuns, projectId && !runId ? 5000 : 0);
  const project = projects.data?.find(value => String(value.projectId) === projectId);
  return <div className="app records-app"><aside><a className="brand" href="#/projects"><span className="brand-mark">∿</span>LabFlow<span className="studio">Studio</span></a><a className="nav" href="#/projects">프로젝트 화면</a><a className="nav active" href="#/records">실험 기록 조회</a>{project && <div className="record-project-name">{project.name}</div>}</aside>
    <div className="body"><header><div className="breadcrumb"><a href="#/records">실험 기록</a>{project && <><span>/</span><a href={route(projectId)}>{project.name}</a></>}</div><div className="account-actions"><a href="#/login">로그인</a><ThemeToggle /></div></header><main>
      <div className="page-heading"><div><div className="eyebrow">Experiments</div><h1>실험 기록</h1><p>프로젝트와 실험을 선택해 학습 기록을 확인하세요.</p></div></div>
      <section className="panel record-picker"><label>내 프로젝트<select aria-label="내 프로젝트" value={projectId} onChange={event => { location.hash = route(event.target.value); }}><option value="">프로젝트 선택</option>{projectId && !project && <option value={projectId}>{projectId}</option>}{projects.data?.map(value => <option key={value.projectId} value={String(value.projectId)}>{value.name}</option>)}</select></label><details><summary>ID로 실험 열기</summary><form onSubmit={event => { event.preventDefault(); location.hash = route(projectInput.trim(), runInput.trim()); }}><label>프로젝트 ID<input name="projectId" value={projectInput} onChange={event => setProjectInput(event.target.value)} required pattern="[1-9][0-9]*" maxLength={19} /></label><label>Run ID<input name="runId" value={runInput} onChange={event => setRunInput(event.target.value)} required pattern="[1-9][0-9]*" maxLength={19} /></label><button className="primary">실험 열기</button></form></details></section>
      {!runId && <ReadError error={projects.error} retry={projects.refresh} />}
      {projectId && !runId && <><ReadError error={runs.error} retry={runs.refresh} /><section className="panel"><div className="section-title"><h2>{project?.name ?? '프로젝트'}의 실험</h2><button onClick={runs.refresh}>새로고침</button></div>{runs.loading ? <p className="record-empty" role="status">실험을 불러오는 중…</p> : runs.data?.items.length ? <div className="record-table-scroll"><table><thead><tr><th>Run</th><th>상태</th><th>시작 시각</th></tr></thead><tbody>{runs.data.items.map(run => <tr key={run.runId}><td><a className="run-link" href={route(projectId, String(run.runId))}>{run.name}</a><small>{String(run.runId)}</small></td><td>{run.status}</td><td>{date(run.startedAt)}</td></tr>)}</tbody></table></div> : !runs.error && <p className="record-empty">기록된 실험이 없습니다.</p>}{runs.data && runs.data.totalPages > 1 && <div className="pagination"><span>{pageNumber + 1} / {runs.data.totalPages}</span><div><button disabled={pageNumber === 0} onClick={() => setListPage({ project: projectId, page: pageNumber - 1 })}>이전 실험</button><button disabled={pageNumber + 1 >= runs.data.totalPages} onClick={() => setListPage({ project: projectId, page: pageNumber + 1 })}>다음 실험</button></div></div>}</section></>}
      {projectId && runId && <RecordViewer key={`${projectId}/${runId}/${tab}`} projectId={projectId} runId={runId} kind={recordKind(tab)} />}
      {!projectId && !projects.loading && !projects.error && <p className="record-empty">프로젝트를 선택하거나 ID로 실험을 열어 주세요.</p>}
    </main></div></div>;
}

export function RecordViewer({ projectId, runId, kind, embedded = false }: { projectId: string; runId: string; kind: RecordKind; embedded?: boolean }) {
  const [position, setPosition] = useState<RecordLocation>({ direction: 'latest' });
  const [search, setSearch] = useState('');
  const [source, setSource] = useState('ALL');
  const [level, setLevel] = useState('ALL');
  const [jumpVersion, setJumpVersion] = useState(0);
  const runPath = `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}`;
  const loadRun = useCallback((signal: AbortSignal) => readRecordsApi<SavedRun>(runPath, signal), [runPath]);
  const run = useRead(runPath, loadRun, position.direction === 'latest' ? 5000 : 0);
  const path = recordPageUrl(projectId, runId, kind, position);
  const loadPage = useCallback((signal: AbortSignal) => readRecordsApi<RecordPage>(path, signal), [path]);
  // Historical pages stay still. Latest keeps polling even after completion to catch the final records.
  const page = useRead(path, loadPage, position.direction === 'latest' ? 5000 : 0);
  const data = page.data;
  const navigation = data ? recordNavigation(data, position) : { older: false, newer: false };
  const retry = () => { run.refresh(); page.refresh(); };
  return <>{!embedded && <><a className="back" href={route(projectId)}>← 실험 목록</a><div className="page-heading run-heading"><div><div className="run-name"><h2>{run.data?.name ?? '실험 기록'}</h2>{run.data && <span className={`badge ${run.data.status}`}>{run.data.status}</span>}</div><p>{runId} · 시작 {date(run.data?.startedAt)}</p></div><button onClick={retry}>새로고침</button></div>{run.data?.error && <div className="record-error" role="alert">{run.data.error}</div>}</>}
    {!embedded && <nav className="tabs" aria-label="기록 종류">{recordKinds.map(value => <a key={value.id} href={route(projectId, runId, value.id)} className={kind === value.id ? 'active' : ''} aria-current={kind === value.id ? 'page' : undefined}>{value.label}</a>)}</nav>}
    <div className="record-controls"><div><strong>{position.direction === 'latest' ? '최신 기록' : '이전 기록 탐색'}</strong><small>{position.direction === 'latest' ? '5초마다 갱신' : '과거 기록을 보는 동안 자동 갱신을 멈춥니다.'} · 한 번에 최대 200개, 기록 시각 순</small></div><div><button disabled={page.loading || !navigation.older} onClick={() => setPosition({ direction: 'before', cursor: data?.beforeCursor ?? undefined })}>이전 기록</button><button disabled={page.loading || !navigation.newer} onClick={() => setPosition({ direction: 'after', cursor: data?.afterCursor ?? undefined })}>다음 기록</button><button className="primary" onClick={() => { setPosition({ direction: 'latest' }); setJumpVersion(value => value + 1); page.refresh(); }}>최신 기록 보기</button></div></div>
    <ReadError error={run.error ?? page.error} retry={retry} />
    {page.loading && <p className="record-empty" role="status">기록을 불러오는 중…</p>}
    {data && <><p className="record-range" role="status">{data.items.length}개 기록{data.items.length > 0 && ` · ${date(data.items[0].recordedAt)} ~ ${date(data.items.at(-1)?.recordedAt)}`}</p>{kind === 'logs' && <div className="record-log-filter"><label>로그 검색<input value={search} onChange={event => setSearch(event.target.value)} placeholder="현재 페이지에서 메시지 검색" /></label><label>로그 레벨<select aria-label="로그 레벨" value={level} onChange={event => setLevel(event.target.value)}>{['ALL', 'DEBUG', 'INFO', 'WARN', 'ERROR'].map(value => <option key={value}>{value}</option>)}</select></label><label>로그 출처<select aria-label="로그 출처" value={source} onChange={event => setSource(event.target.value)}>{['ALL', 'USER', 'SDK', 'STDOUT', 'STDERR'].map(value => <option key={value}>{value}</option>)}</select></label></div>}{!data.items.length ? <section className="panel record-empty">조회할 기록이 없습니다.</section> : <RecordRows kind={kind} followLatest={position.direction === 'latest'} jumpVersion={jumpVersion} items={kind === 'logs' ? data.items.filter(item => (source === 'ALL' || item.source === source) && (level === 'ALL' || item.level === level) && item.message?.toLowerCase().includes(search.toLowerCase())) : data.items} />}</>}
  </>;
}

function RecordRows({ kind, items, followLatest, jumpVersion }: { kind: RecordKind; items: RecordItem[]; followLatest: boolean; jumpVersion: number }) {
  const consoleRef = useRef<HTMLElement>(null);
  const follow = useRef(true);
  const previousJump = useRef(jumpVersion);
  useLayoutEffect(() => {
    if (previousJump.current !== jumpVersion) { follow.current = true; previousJump.current = jumpVersion; }
    if (followLatest && follow.current && consoleRef.current) consoleRef.current.scrollTop = consoleRef.current.scrollHeight;
  }, [items, followLatest, jumpVersion]);
  if (!items.length) return <section className="panel record-empty">현재 페이지에 조건에 맞는 기록이 없습니다.</section>;
  if (kind === 'logs') return <section className="panel console record-console" aria-label="로그 기록" ref={consoleRef} onScroll={() => { const element = consoleRef.current; if (element) follow.current = element.scrollHeight - element.scrollTop - element.clientHeight < 40; }}>{items.map(item => <div key={item.id} className={item.level}><time>{date(item.recordedAt)}</time><b>{item.level} / {item.source}</b><pre>{item.message || '(빈 메시지)'}</pre></div>)}</section>;
  if (kind === 'texts') return <section className="record-texts">{items.map(item => <article className="panel prose" key={item.id}><h2>{item.key}</h2><small>Step {item.step ?? '—'} · {date(item.recordedAt)}</small><pre>{item.text || '(빈 텍스트)'}</pre></article>)}</section>;
  return <>{kind === 'metrics' && <MetricPlots items={items} />}<section className="panel record-table-scroll"><table><thead><tr><th>기록 시각</th>{kind === 'metrics' ? <><th>지표</th><th>값</th><th>Step</th><th>Epoch</th></> : <><th>CPU</th><th>RAM (사용 / 전체 bytes)</th><th>GPU</th></>}</tr></thead><tbody>{items.map(item => <tr key={item.id}><td>{date(item.recordedAt)}</td>{kind === 'metrics' ? <><td>{item.key}</td><td>{item.value}</td><td>{item.step ?? '—'}</td><td>{item.epoch ?? '—'}</td></> : <><td>{item.cpuUtilization == null ? '—' : `${item.cpuUtilization}%`}</td><td>{item.ramUsed ?? '—'} / {item.ramTotal ?? '—'}</td><td>{item.gpus?.length ? item.gpus.map(gpu => <div key={gpu.index}>GPU {gpu.index}: {gpu.utilization ?? '—'}% · {gpu.memoryUsed ?? '—'} / {gpu.memoryTotal ?? '—'} bytes</div>) : '—'}</td></>}</tr>)}</tbody></table></section></>;
}

function MetricPlots({ items }: { items: RecordItem[] }) {
  const keys = [...new Set(items.map(item => item.key))];
  return <div className="record-plots">{keys.map(key => {
    const points = items.filter(item => item.key === key && typeof item.value === 'number' && Number.isFinite(item.value));
    if (!points.length) return null;
    const values = points.map(item => item.value!);
    const low = Math.min(...values), high = Math.max(...values);
    const scale = Math.max(Math.abs(low), Math.abs(high)) || 1;
    const range = high / scale - low / scale || 1;
    return <section className="panel" key={key}><div className="section-title"><h2>{key}</h2><small>{points.length}개 기록 · 마지막 값 {values.at(-1)}</small></div><svg viewBox="0 0 600 180" role="img" aria-label={`${key} 현재 페이지 학습 곡선`}><line x1="45" y1="145" x2="580" y2="145" stroke="var(--border)" /><text x="8" y="30">{high.toPrecision(3)}</text><text x="8" y="145">{low.toPrecision(3)}</text><polyline points={points.map((item, index) => `${45 + index / Math.max(1, points.length - 1) * 535},${high === low ? 85 : 145 - (item.value! / scale - low / scale) / range * 120}`).join(' ')} fill="none" stroke="var(--accent)" strokeWidth="2" />{points.length === 1 && <circle cx="45" cy="85" r="4" fill="var(--accent)" />}<text x="280" y="173">기록 순서 →</text></svg></section>;
  })}</div>;
}
