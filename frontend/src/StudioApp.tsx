import { lazy, Suspense, useEffect, useState } from 'react';
import { allPages, ApiError, api, logout, projectPath } from './api.ts';
import { useApi, useData } from './useData.ts';
import type { LiveProject, Session } from './liveTypes.ts';
import AuthPage from './AuthPage.tsx';
import type { AuthMode } from './AuthPage.tsx';
import ThemeToggle from './ThemeToggle.tsx';
import ProjectNavigation from './ProjectNavigation.tsx';
import LiveProjectForm from './LiveProjectForm.tsx';
import ProjectWorkspace from './ProjectWorkspace.tsx';
import RunRecordsBrowser from './RunRecordsBrowser.tsx';
import { date, Empty, ErrorBox } from './LiveShared.tsx';
import './live.css';
const readRoute = () => ['', '#', '#/'].includes(location.hash) ? '#/login' : location.hash;
const AdminApiPage = lazy(() => import('./ApiWorkbench.tsx'));
export default function StudioApp() {
  const [hash, setHash] = useState(readRoute), [email, setEmail] = useState(''), [epoch,setEpoch] = useState(0);
  const [expired, setExpired] = useState(false), [busy, setBusy] = useState(false), [error,setError] = useState<unknown>();
  const [form, setForm] = useState<'create'|'edit'|null>(null);
  const parts = hash.replace(/^#\/?/,'').split('/'), mode = parts[0], id = mode === 'projects' ? parts[1] ?? '' : '';
  const auth = ['login','signup','verify'].includes(mode), standalone = mode === 'records', workbench = mode === 'api-test';
  useEffect(() => { const change = () => { setHash(readRoute()); setForm(null); setError(undefined); window.scrollTo(0,0); }; addEventListener('hashchange',change); return () => removeEventListener('hashchange',change); }, []);
  useEffect(() => { const expire = () => setExpired(true); addEventListener('labflow-session-expired',expire); return () => removeEventListener('labflow-session-expired',expire); }, []);
  useEffect(() => {
    const changed = (event: Event) => {
      const kind = (event as CustomEvent<{ kind: string }>).detail.kind;
      setExpired(kind !== 'login'); setEpoch(value => value + 1); setForm(null); setError(undefined);
    };
    addEventListener('labflow-auth-state', changed);
    return () => removeEventListener('labflow-auth-state', changed);
  }, []);
  useEffect(() => { if (!auth && !standalone) document.title = 'LabFlow Studio'; }, [auth,standalone,hash]);
  const session = useApi<Session>(!auth && !standalone ? '/api/v1/auth/me' : null, String(epoch));
  const user = !expired && !(session.error instanceof ApiError && session.error.status === 401) ? session.data : undefined;
  const scope = `${epoch}:${user?.userId ?? 'public'}`;
  const projects = useData(user && !workbench ? `${scope}:projects` : null, signal => allPages<LiveProject>('/api/v1/projects?page=0&size=100',signal));
  const project = useApi<LiveProject>(id ? projectPath(id) : null, scope);
  const list = projects.data?.items ?? [];
  const links = list.map(value => ({id:value.projectId,name:value.name}));
  const selected = project.data ? {id:project.data.projectId,name:project.data.name} : undefined;
  const create = () => { if (user) setForm('create'); else location.hash='/login'; };
  if (auth) return <AuthPage mode={mode as AuthMode} email={email} setEmail={setEmail} onLogin={() => {setExpired(false);setEpoch(value=>value+1);location.hash='/projects';}}/>;
  if (standalone) return <RunRecordsBrowser key={`${epoch}:${expired}`} projectId={parts[1]??''} runId={parts[2]??''} tab={parts[3]??'metrics'}/>;
  return <div className="app live-app"><aside><a className="brand" href="#/projects"><span className="brand-mark">∿</span>LabFlow<span className="studio">Studio</span></a><div className="workspace"><div className="avatar">{user?.name.slice(0,2).toUpperCase() ?? 'LF'}</div><div><strong>{user?.name ?? '공개 워크스페이스'}</strong><small>{user ? '개인 워크스페이스' : '공유 프로젝트 조회'}</small></div></div><ProjectNavigation projects={links} project={selected} onCreate={create}/></aside>
    <div className="body"><header><div className="breadcrumb"><a href="#/projects">프로젝트</a>{project.data && <><span>/</span><a href={`#/projects/${id}/runs`}>{project.data.name}</a></>}</div><div className="account-actions"><a href="#/records">실험 기록</a>{user?.role === 'ADMIN' && <a href="#/api-test" aria-current={workbench ? 'page' : undefined}>API 테스트</a>}{user ? <button disabled={busy} onClick={async () => { if(busy)return;setBusy(true);setError(undefined);try{await logout();setExpired(true);setEpoch(value=>value+1);setForm(null);location.hash='/login';}catch(issue){setError(issue);}finally{setBusy(false);} }}>{busy?'로그아웃 중…':'로그아웃'}</button> : <><a href="#/login">로그인</a><a className="account-signup" href="#/signup">회원가입</a></>}<ThemeToggle/></div></header><main>
      <ErrorBox error={error}/>{workbench && <><ErrorBox error={session.error} retry={session.refresh}/>{session.loading ? <p role="status" className="live-empty">관리자 권한을 확인하는 중…</p> : user?.role === 'ADMIN' ? <Suspense fallback={<p role="status" className="live-empty">API 테스트를 불러오는 중…</p>}><AdminApiPage key={scope} selectedId={parts[1]} scope={scope} sessionChanged={session.refresh}/></Suspense> : <Empty title="ADMIN 전용 페이지입니다"><p>관리자 계정으로 로그인해 주세요.</p><a href="#/login">로그인 →</a></Empty>}</>}{!id && !workbench && <><div className="page-heading"><div><div className="eyebrow">Workspace</div><h1>프로젝트</h1><p>실험을 정리하고, 다음 학습의 근거를 찾으세요</p></div><button className="primary" onClick={create}>+ 새 프로젝트</button></div>
        {(session.loading || projects.loading) && <p role="status" className="live-empty">워크스페이스를 불러오는 중…</p>}
        <ErrorBox error={session.error} retry={session.refresh}/><ErrorBox error={projects.error} retry={projects.refresh}/>
        {user && projects.data && <><div className="project-summary"><strong>{projects.data.totalElements}</strong> 프로젝트</div>{list.length ? <div className="projects-grid">{list.map(value => <a className="project-card" key={value.projectId} href={`#/projects/${value.projectId}/runs`}><div className="project-card-top"><span className="project-icon">∿</span><span>→</span></div><span className="eyebrow">{value.visibility}</span><h2>{value.name}</h2><p>{value.description || '설명이 없는 프로젝트'}</p><div className="project-card-foot"><span>실험 기록 열기</span><span>{date(value.updatedAt ?? value.createdAt)}</span></div></a>)}</div> : <Empty title="첫 프로젝트를 만들어 보세요"><p>실험의 설정과 학습 기록을 한곳에 모으세요.</p><button className="primary" onClick={create}>새 프로젝트</button></Empty>}</>}
        {!user && !session.loading && !session.error && <Empty title="로그인하고 프로젝트를 확인하세요"><a href="#/login">로그인 →</a></Empty>}
      </>}
      {id && <><ErrorBox error={project.error} retry={project.refresh}/>{project.loading && <p role="status" className="live-empty">프로젝트를 불러오는 중…</p>}{project.data && <ProjectWorkspace key={`${scope}/${id}`} project={project.data} scope={scope} owner={project.data.createdBy === user?.userId} view={parts[2]||'runs'} runId={parts[2]==='runs'?parts[3]??'':''} tab={parts[4]||'metrics'} edit={() => setForm('edit')} refreshProject={project.refresh} refreshProjects={projects.refresh} deleted={() => { projects.refresh();location.hash='/projects'; }}/>}</>}
      {form && (form === 'create' || project.data) && <LiveProjectForm key={`${form}/${id}`} project={form==='edit'?project.data:undefined} close={() => setForm(null)} reload={project.refresh} saved={newId => {projects.refresh();project.refresh();location.hash=`/projects/${newId}/${form==='edit'?'settings':'runs'}`;}}/>}
    </main></div>
  </div>;
}
