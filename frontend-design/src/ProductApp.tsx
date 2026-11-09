import { useCallback, useEffect, useState } from 'react'
import { allProjects, api, HttpError } from './productApi'
import type { Project } from './productApi'
import AuthScreen from './AuthScreen'
import ProjectForm from './ProjectForm'
import LiveProject from './LiveProject'
import type { IssuedKey } from './LiveProject'
import { date, Empty, ErrorBox, errorText, go, Logo, projectLink } from './ProductShared'
import './product.css'

export default function ProductApp({ hash }: { hash: string }) {
  const parts = (hash.replace(/^#/, '') || '/projects').split('/')
  const mode = parts[1]
  const projectId = parts[1] === 'projects' ? parts[2] : undefined
  const view = parts[3] || 'overview'
  const runId = view === 'runs' ? parts[4] : undefined
  const [projects, setProjects] = useState<Project[]>([])
  const [authenticated, setAuthenticated] = useState(false)
  const [booting, setBooting] = useState(true)
  const [email, setEmail] = useState('')
  const [search, setSearch] = useState('')
  const [creating, setCreating] = useState(false)
  const [toast, setToast] = useState('')
  const [error, setError] = useState<unknown>()
  const [keys, setKeys] = useState<Record<string, IssuedKey>>({})
  const [logoutBusy, setLogoutBusy] = useState(false)
  const refreshProjects = useCallback(async () => { const rows = await allProjects(); setProjects(rows); setAuthenticated(true) }, [])
  useEffect(() => { const c = new AbortController(); allProjects(c.signal).then(rows => { if (!c.signal.aborted) { setProjects(rows); setAuthenticated(true) } }).catch(issue => { if (!c.signal.aborted && !(issue instanceof HttpError && issue.status === 401)) setError(issue) }).finally(() => { if (!c.signal.aborted) setBooting(false) }); return () => c.abort() }, [])
  useEffect(() => { document.title = `LabFlow · ${projectId ? projects.find(project => String(project.projectId) === projectId)?.name ?? '실험 공간' : '프로젝트'}` }, [projectId, projects])
  useEffect(() => { if (!toast) return; const timer = setTimeout(() => setToast(''), 4000); return () => clearTimeout(timer) }, [toast])
  if (booting) return <div className="p-boot"><Logo /><p>실험 공간에 연결하는 중…</p></div>
  if (['login', 'signup', 'verify'].includes(mode) || (!authenticated && !projectId)) return <div className="product-app"><ErrorBox error={error} /><AuthScreen mode={['signup', 'verify'].includes(mode) ? mode : 'login'} email={email} setEmail={setEmail} onLogin={async () => { await refreshProjects(); setKeys({}); setError(undefined); go('/projects') }} /></div>
  const current = projects.find(project => String(project.projectId) === projectId)
  const filtered = projects.filter(project => `${project.name} ${project.description ?? ''}`.toLowerCase().includes(search.toLowerCase()))
  return <div className="product-app p-shell"><a className="skip-link" href="#product-main" onClick={event => { event.preventDefault(); document.getElementById('product-main')?.focus() }}>본문으로 이동</a><aside className="p-sidebar"><a href="#/projects"><Logo /></a><div className="p-nav-caption">WORKSPACE</div><a href="#/projects" className={`p-nav-item ${!projectId ? 'active' : ''}`}><span>▦</span> Projects <small>{projects.length}</small></a><div className="p-nav-caption">SELECTED PROJECT</div><label className="p-project-picker"><select aria-label="프로젝트 선택" value={current?.projectId ?? ''} onChange={event => go(`/projects/${event.target.value}/overview`)}><option value="" disabled>{projectId ? '공유 프로젝트' : '프로젝트 선택'}</option>{projects.map(project => <option value={project.projectId} key={project.projectId}>{project.name}</option>)}</select></label>{projectId && <nav aria-label="프로젝트 메뉴">{[['overview', '◫', 'Overview'], ['runs', '⌁', 'Runs'], ['api-keys', '⚿', 'API Keys']].map(([id, icon, label]) => <a key={id} href={projectLink(projectId, id)} className={`p-nav-item ${view === id ? 'active' : ''}`}><span aria-hidden="true">{icon}</span>{label}</a>)}</nav>}<div className="p-sidebar-bottom"><a className="p-nav-item" href="#/api-test">↗ API 테스트 도구</a>{authenticated ? <button className="p-nav-item" disabled={logoutBusy} onClick={async () => { setLogoutBusy(true); try { await api('/api/v1/auth/logout', { method: 'POST', retryAuth: false }); setProjects([]); setKeys({}); setAuthenticated(false); setError(undefined); go('/login') } catch (issue) { setToast(errorText(issue)) } finally { setLogoutBusy(false) } }}>{logoutBusy ? '로그아웃 중…' : '로그아웃'}</button> : <a href="#/login" className="p-nav-item">로그인</a>}</div></aside>
    <div className="p-main-layout"><header className="p-topbar"><nav aria-label="현재 위치"><a href="#/projects">Projects</a>{projectId && <><span>/</span><a href={projectLink(projectId)}>{current?.name ?? 'Project'}</a><span>/</span><strong>{runId ? 'Run details' : view === 'runs' ? 'Runs' : view === 'api-keys' ? 'API Keys' : 'Overview'}</strong></>}</nav><span className="p-backend-indicator"><i />실제 실험 데이터</span></header><main className="p-main" id="product-main" tabIndex={-1}>
      {!projectId ? <><div className="p-heading"><div><span className="p-eyebrow">YOUR EXPERIMENTS, ORGANIZED</span><h1>Projects</h1><p>프로젝트를 선택하고 실험의 흐름을 이어가세요.</p></div><button className="p-primary" onClick={() => setCreating(true)}>+ 새 프로젝트</button></div><ErrorBox error={error} retry={() => { void refreshProjects().then(() => setError(undefined)).catch(setError) }} /><div className="p-project-list-toolbar"><h2>내 프로젝트 <span className="count-badge">{projects.length}</span></h2><input aria-label="프로젝트 검색" value={search} onChange={event => setSearch(event.target.value)} placeholder="프로젝트 검색…" /></div>{filtered.length ? <div className="p-project-grid">{filtered.map((project, index) => <a className="panel p-project-card" key={project.projectId} href={projectLink(String(project.projectId))}><div><span className={`p-project-mark color-${index % 4}`}>{project.name.slice(0, 2).toUpperCase()}</span><span className="p-visibility">{project.visibility}</span></div><h3>{project.name}</h3><p>{project.description || '새로운 실험을 기록해 보세요.'}</p><small>{date(project.createdAt)}</small><div className="p-card-footer">프로젝트 열기 <span>→</span></div></a>)}</div> : <section className="panel"><Empty title={search ? '검색 결과가 없어요' : '첫 프로젝트를 만들어 보세요'}><p>{search ? '다른 이름으로 검색해 보세요.' : '실험의 파라미터, 학습 지표와 결과를 한곳에 모으세요.'}</p>{!search && <button className="p-primary" onClick={() => setCreating(true)}>프로젝트 만들기</button>}</Empty></section>}</>
        : <LiveProject key={projectId} projectId={projectId} view={view} runId={runId} tab={parts[5] || 'metrics'} ownProjects={projects} refreshProjects={refreshProjects} issued={keys[projectId]} setIssued={value => setKeys(previous => ({ ...previous, [projectId]: value }))} notify={setToast} />}
      <footer className="p-footer"><span><strong>LabFlow</strong> / 실험의 모든 기록을 한곳에.</span><span>개인 실험 공간 · SDK connected</span></footer></main></div>{creating && <ProjectForm close={() => setCreating(false)} saved={async id => { await refreshProjects(); go(`/projects/${id}/overview`) }} />}{toast && <div role="status" className="p-toast">{toast}</div>}
  </div>
}
