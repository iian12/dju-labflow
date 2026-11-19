import { useCallback, useState } from 'react'
import { api, metricEvents, projectPath } from './productApi'
import type { Records, Run } from './productApi'
import MetricsPanel from './MetricCharts'
import { Badge, Empty, ErrorBox, date, duration, projectLink, useRemote } from './ProductShared'

export default function LiveRun({ projectId, runId, tab }: { projectId: string; runId: string; tab: string }) {
  const path = `${projectPath(projectId)}/runs/${encodeURIComponent(runId)}`
  const loadRun = useCallback((signal: AbortSignal) => api<Run>(path, { signal }), [path])
  const run = useRemote(path, loadRun, 5000)
  const loadRecords = useCallback((signal: AbortSignal) => api<Records>(`${path}/records`, { signal }), [path])
  const records = useRemote(`${path}/records`, loadRecords, run.data?.status === 'RUNNING' ? 5000 : 0)
  const [search, setSearch] = useState('')
  const [source, setSource] = useState('ALL')
  if (!run.data) return <><ErrorBox error={run.error} retry={run.refresh} />{run.loading && <div className="p-loading">실험을 불러오는 중…</div>}</>
  const data = run.data
  const rows = records.data
  const tabs = [['metrics', 'Metrics'], ['parameters', 'Parameters'], ['logs', 'Logs'], ['texts', 'Texts'], ['system', 'System'], ['overview', 'Overview']]
  const selected = tabs.some(([id]) => id === tab) ? tab : 'metrics'
  return <><div className="p-run-title"><div><a className="p-text-link" href={projectLink(projectId, 'runs')}>← 전체 실험</a><h1>{data.name}</h1><p>{data.runId}</p></div><Badge status={data.status} /></div><section className="p-run-summary"><div><span>Duration</span><strong>{duration(data)}</strong></div><div><span>Started</span><strong>{date(data.startedAt)}</strong></div><div><span>Ended</span><strong>{date(data.endedAt)}</strong></div><button className="p-secondary" onClick={() => { run.refresh(); records.refresh() }}>새로고침</button></section><ErrorBox error={run.error ?? records.error} retry={() => { run.refresh(); records.refresh() }} />{data.error && <div className="p-error p-run-error"><strong>실행 실패</strong><pre>{data.error}</pre></div>}<nav className="run-tabs" aria-label="실험 기록 탭">{tabs.map(([id, label]) => <a href={`#/projects/${projectId}/runs/${runId}/${id}`} className={selected === id ? 'active' : ''} aria-current={selected === id ? 'page' : undefined} key={id}>{label}</a>)}</nav>
    {records.loading && <div className="p-loading">기록을 불러오는 중…</div>}
    {rows && <div className="p-records">
      {[rows.metrics, rows.logs, rows.texts, rows.systemMetrics].some(items => items.length >= rows.limit) && <p className="p-notice">일부 기록이 조회 상한({rows.limit}개)에 도달했습니다. 각 종류의 처음 {rows.limit}개까지 표시합니다.</p>}
      {selected === 'metrics' && <MetricsPanel records={metricEvents(rows.metrics)} />}
      {selected === 'parameters' && <section className="panel">{rows.parameters.length ? <div className="p-table-scroll"><table><thead><tr><th>Parameter</th><th>Value</th></tr></thead><tbody>{rows.parameters.map(item => <tr key={item.key}><td><code>{item.key}</code></td><td>{item.value}</td></tr>)}</tbody></table></div> : <Empty title="기록된 파라미터가 없어요" />}</section>}
      {selected === 'logs' && <section className="panel"><div className="p-log-toolbar"><label>로그 검색<input value={search} onChange={event => setSearch(event.target.value)} placeholder="메시지 검색" /></label><label>출처<select value={source} onChange={event => setSource(event.target.value)}>{['ALL', 'USER', 'SDK', 'STDOUT', 'STDERR'].map(value => <option key={value} value={value}>{value === 'ALL' ? '모든 출처' : value}</option>)}</select></label></div>{rows.logs.length ? <div className="p-real-logs">{rows.logs.filter(log => (source === 'ALL' || log.source === source) && log.message.toLowerCase().includes(search.toLowerCase())).map(log => <div key={log.id} className={log.level === 'ERROR' ? 'error' : ''}><time>{date(log.recordedAt)}</time><span>{log.level} / {log.source}</span><pre>{log.message || '(빈 메시지)'}</pre></div>)}</div> : <Empty title="기록된 로그가 없어요" />}</section>}
      {selected === 'texts' && (rows.texts.length ? <div className="p-text-grid">{rows.texts.map(item => <article className="panel" key={item.id}><div className="section-heading"><h2>{item.key}</h2><span className="count-badge">Step {item.step ?? '—'}</span></div><pre className="p-text-content">{item.text || '(빈 텍스트)'}</pre><small className="p-text-time">{date(item.recordedAt)}</small></article>)}</div> : <section className="panel"><Empty title="기록된 텍스트가 없어요" /></section>)}
      {selected === 'system' && <section className="panel">{rows.systemMetrics.length ? <div className="p-table-scroll"><table><thead><tr><th>Recorded</th><th>CPU</th><th>RAM (used / total bytes)</th><th>GPU</th></tr></thead><tbody>{rows.systemMetrics.map(item => <tr key={item.id}><td>{date(item.recordedAt)}</td><td>{item.cpuUtilization == null ? '—' : `${item.cpuUtilization}%`}</td><td>{item.ramUsed ?? '—'} / {item.ramTotal ?? '—'}</td><td>{item.gpus.length ? item.gpus.map(gpu => <div key={gpu.index}>GPU {gpu.index}: {gpu.utilization ?? '—'}% · {gpu.memoryUsed ?? '—'} / {gpu.memoryTotal ?? '—'} bytes</div>) : '—'}</td></tr>)}</tbody></table></div> : <Empty title="수집된 시스템 지표가 없어요"><p>현재 최소 Python SDK는 CPU/GPU 지표를 자동 수집하지 않습니다.</p></Empty>}</section>}
      {selected === 'overview' && <section className="panel"><div className="section-heading"><h2>Run metadata</h2></div><div className="p-info-list">{Object.entries(data.metadata).map(([key, value]) => <div key={key}><span>{key}</span><strong>{value}</strong></div>)}{!Object.keys(data.metadata).length && <p>메타데이터가 없습니다.</p>}</div></section>}
    </div>}
  </>
}
