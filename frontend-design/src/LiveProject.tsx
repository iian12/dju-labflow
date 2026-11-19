import { useCallback, useState } from 'react'
import { api, projectPath } from './productApi'
import type { Project, Run, RunPage } from './productApi'
import ProjectForm from './ProjectForm'
import LiveRun from './LiveRun'
import { Badge, Empty, ErrorBox, date, duration, go, projectLink, useRemote } from './ProductShared'

export type IssuedKey = { name: string; key: string }
function RunsTable({ runs, projectId }: { runs: Run[]; projectId: string }) {
  if (!runs.length) return <Empty title="아직 실행한 실험이 없어요"><p>API key를 발급하고 Python SDK로 첫 학습을 시작해 보세요.</p><a className="p-primary" href={projectLink(projectId, 'api-keys')}>SDK 연결하기 →</a></Empty>
  return <div className="p-table-scroll"><table className="p-runs-table"><thead><tr><th>Run</th><th>Status</th><th>Duration</th><th>Started</th><th /></tr></thead><tbody>{runs.map(run => <tr key={run.runId}><td><a href={`#/projects/${projectId}/runs/${run.runId}/metrics`}><strong>{run.name}</strong><small>{run.runId}</small></a></td><td><Badge status={run.status} /></td><td>{duration(run)}</td><td>{date(run.startedAt)}</td><td><a aria-label={`${run.name} 상세 보기`} href={`#/projects/${projectId}/runs/${run.runId}/metrics`}>↗</a></td></tr>)}</tbody></table></div>
}

function SdkSetup({ projectId, issued, setIssued, canManage, notify }: { projectId: string; issued?: IssuedKey; setIssued: (value: IssuedKey) => void; canManage: boolean; notify: (message: string) => void }) {
  const [name, setName] = useState('local-training')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [show, setShow] = useState(false)
  return <div className="p-setup"><section className="panel"><div className="section-heading"><h2>프로젝트 API key</h2><span className="count-badge">SDK 연결</span></div><div className="p-panel-body"><p>학습 코드가 이 프로젝트에 실험을 기록할 수 있도록 키를 발급하세요.</p>{canManage ? <form className="p-key-form" onSubmit={async event => { event.preventDefault(); setBusy(true); setError(undefined); try { const result = await api<{ rawApiKey: string }>(`/api/v1/project-api-keys/issue/${projectId}`, { method: 'POST', body: { name } }); setIssued({ name, key: result.rawApiKey }); setShow(false) } catch (issue) { setError(issue) } finally { setBusy(false) } }}><label>키 이름<input value={name} onChange={event => setName(event.target.value)} required maxLength={100} disabled={busy} /></label><button type="submit" className="p-primary" disabled={busy}>{busy ? '발급 중…' : 'API key 발급'}</button></form> : <p className="p-notice">키 발급은 프로젝트 소유자만 할 수 있습니다.</p>}<ErrorBox error={error} />{issued && <div className="p-issued-key"><span>{issued.name} · 이번 화면에서 발급한 키</span><input aria-label="발급된 API key" value={issued.key} readOnly type={show ? 'text' : 'password'} /><div><button type="button" className="p-secondary" onClick={() => setShow(!show)}>{show ? '숨기기' : '키 표시'}</button><button type="button" className="p-primary" onClick={async () => { try { await navigator.clipboard.writeText(issued.key); notify('키를 복사했습니다.') } catch { notify('키 표시 후 직접 복사해 주세요.') } }}>키 복사</button></div></div>}<small>키 원문은 발급할 때만 확인할 수 있습니다. 안전한 곳에 보관해 주세요. 화면을 새로고침하면 여기에서 사라집니다.</small></div></section>
    <section className="panel"><div className="section-heading"><h2>첫 모델 학습 시작하기</h2><span className="count-badge">Python</span></div><div className="p-panel-body"><ol><li>프로젝트 키를 복사합니다.</li><li>저장소 루트에서 아래 명령을 실행합니다.</li><li>키 입력 창에 붙여넣으면 학습이 시작됩니다.</li></ol><pre className="p-command">{'.\\sdk\\.venv\\Scripts\\python.exe .\\sdk\\examples\\train_digits.py'}</pre><p>손글씨 숫자 분류 모델이 학습하면서 실제 loss·accuracy, 파라미터, 로그와 예측 결과를 기록합니다.</p><a className="p-text-link" href={projectLink(projectId, 'runs')}>실험 목록에서 확인하기 →</a></div></section>
  </div>
}

export default function LiveProject({ projectId, view, runId, tab, ownProjects, refreshProjects, issued, setIssued, notify }: { projectId: string; view: string; runId?: string; tab: string; ownProjects: Project[]; refreshProjects: () => Promise<void>; issued?: IssuedKey; setIssued: (value: IssuedKey) => void; notify: (message: string) => void }) {
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState(false)
  const [actionError, setActionError] = useState<unknown>()
  const [deleting, setDeleting] = useState(false)
  const path = projectPath(projectId)
  const loadProject = useCallback((signal: AbortSignal) => api<Project>(path, { signal }), [path])
  const project = useRemote(path, loadProject)
  const loadRuns = useCallback((signal: AbortSignal) => api<RunPage>(`${path}/runs?page=${page}&size=20`, { signal }), [path, page])
  const runs = useRemote(`${path}/runs/${page}`, loadRuns, 5000)
  const canManage = ownProjects.some(item => String(item.projectId) === projectId)
  if (!project.data) return <><ErrorBox error={project.error} retry={project.refresh} />{project.loading && <div className="p-loading">프로젝트를 불러오는 중…</div>}</>
  const data = project.data
  if (runId) return <LiveRun key={runId} projectId={projectId} runId={runId} tab={tab} />
  const counts = runs.data?.counts
  return <><div className="p-heading"><div><span className="p-eyebrow">PROJECT WORKSPACE</span><h1>{view === 'runs' ? '실험 기록' : view === 'api-keys' ? 'SDK 연결' : data.name}</h1><p>{data.description || '이 프로젝트의 학습과 결과를 한눈에 확인하세요.'}</p></div><div className="p-heading-actions"><span className={`p-visibility ${data.visibility.toLowerCase()}`}>{data.visibility}</span>{canManage && <button className="p-secondary" onClick={() => setEditing(true)}>프로젝트 설정</button>}</div></div><ErrorBox error={actionError ?? project.error} retry={project.refresh} />
    {view === 'api-keys' ? <SdkSetup key={projectId} projectId={projectId} issued={issued} setIssued={setIssued} canManage={canManage} notify={notify} /> : <><ErrorBox error={runs.error} retry={runs.refresh} />
      {view !== 'runs' && <><div className="p-stats">{[['전체 실험', runs.data?.totalElements], ['진행 중', counts?.RUNNING], ['완료', counts?.COMPLETED], ['실패', counts?.FAILED]].map(([label, value]) => <article className="panel" key={label}><span>{label}</span><strong>{value ?? '—'}</strong><i /></article>)}</div><div className="p-overview-grid"><section className="panel"><div className="section-heading"><h2>프로젝트 정보</h2></div><div className="p-info-list"><div><span>Project ID</span><strong>{data.projectId}</strong></div><div><span>공개 범위</span><strong>{data.visibility}</strong></div><div><span>생성일</span><strong>{date(data.createdAt)}</strong></div><div><span>최근 수정</span><strong>{date(data.updatedAt)}</strong></div></div></section><section className="panel p-connect-card"><span className="p-eyebrow">NEXT EXPERIMENT</span><h2>학습 코드를 연결하세요.</h2><p>Python SDK로 기록한 실제 학습 결과가<br />이 프로젝트의 실험 목록에 표시됩니다.</p><a className="p-primary" href={projectLink(projectId, 'api-keys')}>API key · SDK 연결 →</a></section></div></>}
      <section className="panel"><div className="section-heading"><h2>{view === 'runs' ? 'All Runs' : 'Recent Runs'}</h2><div className="p-heading-actions"><span className="count-badge">{runs.data?.totalElements ?? '—'} runs</span><button className="p-link-button" onClick={runs.refresh}>새로고침</button></div></div>{runs.loading ? <div className="p-loading">실험을 불러오는 중…</div> : runs.data && <RunsTable projectId={projectId} runs={view === 'runs' ? runs.data.items : runs.data.items.slice(0, 3)} />}{view === 'runs' && runs.data && runs.data.totalPages > 1 && <div className="p-pagination"><button disabled={page === 0} onClick={() => setPage(value => value - 1)}>이전</button><span>{page + 1} / {runs.data.totalPages}</span><button disabled={page + 1 >= runs.data.totalPages} onClick={() => setPage(value => value + 1)}>다음</button></div>}{view !== 'runs' && !!runs.data?.totalElements && <a className="p-view-all" href={projectLink(projectId, 'runs')}>전체 실험 보기 →</a>}</section>
    </>}
    {editing && <ProjectForm project={data} close={() => setEditing(false)} saved={async () => { await refreshProjects(); project.refresh() }} />}
    {canManage && view === 'overview' && <details className="p-danger-zone"><summary>프로젝트 삭제</summary><p>이 프로젝트의 API key, 실험과 모든 기록을 영구 삭제합니다.</p><button className="p-danger" disabled={deleting} onClick={async () => { if (!confirm(`"${data.name}" 프로젝트와 모든 실험 기록을 영구 삭제할까요?`)) return; setDeleting(true); setActionError(undefined); try { await api(`${path}?version=${encodeURIComponent(String(data.version))}`, { method: 'DELETE' }); await refreshProjects(); go('/projects'); notify('프로젝트를 삭제했습니다.') } catch (issue) { setActionError(issue) } finally { setDeleting(false) } }}>프로젝트와 기록 삭제</button></details>}
  </>
}
