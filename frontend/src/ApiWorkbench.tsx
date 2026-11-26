import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from './api.ts';
import { useApi } from './useData.ts';
import { Empty, ErrorBox } from './LiveShared.tsx';
import { endpoints, groups, initialValues } from './apiCatalog.ts';
import type { Context, Field } from './apiCatalog.ts';
import { identifier, object, parseResponse, prepare, requestApi } from './apiClient.ts';
import type { Exchange } from './apiClient.ts';
import './apiWorkbench.css';

type Operation = { method: string; path: string };
const accessPath = '/api/v1/admin/api-test';
const emptyContext: Context = { projectId: '', runId: '', version: '', keyId: '', artifactId: '' };
const text = (value: unknown) => typeof value === 'string' || typeof value === 'number' ? String(value) : '';

export default function AdminApiPage({ selectedId, scope, sessionChanged }: { selectedId?: string; scope: string; sessionChanged: () => void }) {
  const access = useApi<Operation[]>(accessPath, scope, 15000);
  const [blocked, setBlocked] = useState<unknown>();
  async function verifyAccess(signal?: AbortSignal) {
    try { await api(accessPath, { signal }); }
    catch (error) { setBlocked(error); throw error; }
  }
  if (access.loading) return <p role="status" className="live-empty">관리자 권한을 확인하는 중…</p>;
  const denied = blocked ?? access.error;
  if (denied || !access.data) return <><ErrorBox error={denied}/><Empty title="ADMIN 전용 페이지입니다"><p>관리자 계정으로 로그인해 주세요.</p><a href="#/login">로그인 →</a></Empty></>;
  return <ApiWorkbench selectedId={selectedId} operations={access.data} verifyAccess={verifyAccess} sessionChanged={sessionChanged}/>;
}

function ApiWorkbench({ selectedId, operations, verifyAccess, sessionChanged }: {
  selectedId?: string; operations: Operation[]; verifyAccess: (signal?: AbortSignal) => Promise<void>; sessionChanged: () => void;
}) {
  const endpoint = endpoints.find(item => item.id === selectedId) ?? endpoints[0];
  const [values, setValues] = useState(() => Object.fromEntries(endpoints.map(item => [item.id, initialValues(item)])));
  const [context, setContext] = useState<Context>(emptyContext);
  const [apiKey, setApiKey] = useState(''), [showKey, setShowKey] = useState(false);
  const [anonymous, setAnonymous] = useState(false), [autoCsrf, setAutoCsrf] = useState(true);
  const [idempotencyKey, setIdempotencyKey] = useState(''), [file, setFile] = useState<File>();
  const [history, setHistory] = useState<Exchange[]>([]), [selectedExchange, setSelectedExchange] = useState<Exchange>();
  const [pending, setPending] = useState(false), [message, setMessage] = useState('');
  const [rawMode, setRawMode] = useState(false), [raw, setRaw] = useState('');
  const [search, setSearch] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => { document.title = 'API 테스트 · LabFlow Studio'; setRawMode(false); setMessage(''); setFile(undefined); }, [endpoint.id]);
  const available = new Set(operations.map(item => `${item.method} ${item.path}`));
  const supported = available.has(`${endpoint.method} ${endpoint.path}`);
  const missing = operations.filter(item => !endpoints.some(entry => entry.method === item.method && entry.path === item.path));
  const go = (id: string) => { location.hash = `/api-test/${id}`; };
  const updateContext = (key: keyof Context, value: string) => setContext(previous => ({ ...previous, [key]: value, ...(key === 'projectId' ? { version: '' } : {}) }));
  const fieldValue = (field: Field) => (field.binding ? context[field.binding] : values[endpoint.id][field.name]) ?? '';
  const changeField = (field: Field, value: string) => {
    if (field.binding) { updateContext(field.binding, value); return; }
    setValues(previous => ({ ...previous, [endpoint.id]: { ...previous[endpoint.id], [field.name]: value } }));
  };
  function selectProject(data: Record<string, unknown>) {
    setContext({ ...emptyContext, projectId: identifier(data.projectId), version: identifier(data.version) });
    setValues(previous => ({ ...previous, 'project-update': { ...previous['project-update'], name: text(data.name), description: text(data.description), visibility: text(data.visibility) || 'PRIVATE' } }));
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || !supported) return;
    setMessage('');
    const current = new AbortController(); controller.current = current;
    try {
      const input = endpoint.file ? { ...values[endpoint.id], name: values[endpoint.id].name || file?.name || '' } : values[endpoint.id];
      const prepared = prepare(endpoint, input, context, rawMode ? raw : undefined);
      if (endpoint.file && !file) throw new Error('업로드할 파일을 선택하세요.');
      if ((endpoint.method === 'DELETE' || endpoint.id === 'run-stop') && !window.confirm(`${endpoint.title} 요청을 실행할까요? ${endpoint.id === 'project-delete' ? '프로젝트와 소속 기록이 영구 삭제됩니다.' : ''}`)) return;
      setPending(true);
      // The separate ADMIN check is never controlled by anonymous/CSRF test options.
      await verifyAccess(current.signal);
      current.signal.throwIfAborted();
      const stableKey = endpoint.auth === 'key' && endpoint.method !== 'GET' ? idempotencyKey || crypto.randomUUID() : undefined;
      if (stableKey && !idempotencyKey) setIdempotencyKey(stableKey);
      const result = await requestApi(endpoint, prepared, {
        apiKey, anonymous, autoCsrf, file, idempotencyKey: stableKey, signal: current.signal, cookie: () => document.cookie,
        onExchange: entry => { if (!current.signal.aborted) { setHistory(previous => [entry, ...previous].slice(0, 40)); setSelectedExchange(entry); } },
        onDownload: (blob, name) => { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); },
      });
      if (current.signal.aborted) return;
      const data = object(result.data);
      if (result.exchange.ok && data) {
        if (endpoint.id === 'project-create') setContext({ ...emptyContext, projectId: identifier(data.projectId) });
        if (endpoint.id === 'project-get') selectProject(data);
        if (endpoint.id === 'run-create') updateContext('runId', identifier(data.runId));
        if (endpoint.id === 'artifact-upload') updateContext('artifactId', identifier(data.artifactId));
        if (endpoint.id === 'key-issue' && typeof data.rawApiKey === 'string') { setApiKey(data.rawApiKey); updateContext('keyId', identifier(data.apiKeyId)); setMessage('발급한 키를 SDK 요청에 연결했습니다. 원문은 이 화면에만 보관됩니다.'); }
      }
      if (result.exchange.ok && endpoint.id === 'project-delete') { setContext(emptyContext); setApiKey(''); }
      if (result.exchange.ok && endpoint.id === 'project-update') { updateContext('version', ''); setMessage('상세를 다시 조회해 최신 version을 가져오세요.'); }
      if (['login', 'logout', 'refresh'].includes(endpoint.id)) sessionChanged();
      // Drop the entire tool (including in-memory secrets) if this request changed identity.
      await verifyAccess(current.signal);
    } catch (error) {
      if (!current.signal.aborted && !(error instanceof ApiError && [401, 403].includes(error.status))) setMessage(error instanceof Error ? error.message : '요청을 처리하지 못했습니다.');
    } finally { if (!current.signal.aborted) setPending(false); }
  }
  let projectRows: Record<string, unknown>[] = [];
  if (selectedExchange?.endpointId === 'project-list') {
    try { const items = object(parseResponse(selectedExchange.responseBody))?.items; if (Array.isArray(items)) projectRows = items.flatMap(item => object(item) ? [object(item)!] : []); } catch { /* Keep the original response visible. */ }
  }
  return <div className="api-tools">
    <div className="page-heading"><div><div className="eyebrow">Administration</div><h1>API 테스트</h1><p>요청을 구성하고 실제 서버의 응답을 확인하세요.</p></div><span className="api-admin-badge">ADMIN · {operations.length} APIs</span></div>
    {missing.length > 0 && <div className="live-error" role="alert">현재 서버의 새 API {missing.length}개가 폼에 없습니다: {missing.map(item => `${item.method} ${item.path}`).join(', ')}</div>}
    <section className="panel api-context" aria-label="요청 연결 설정"><div className="section-title"><h2>연결 컨텍스트</h2><small>선택·생성한 식별자를 다음 요청에 사용합니다</small></div>
      <fieldset disabled={pending} className="api-context-grid">{([['projectId','Project ID'],['runId','Run ID'],['version','Version'],['keyId','Key ID'],['artifactId','Artifact ID']] as const).map(([key,label]) => <label key={key}>{label}<input aria-label={label} value={context[key] ?? ''} onChange={event => updateContext(key,event.target.value)} spellCheck={false}/></label>)}</fieldset>
      <fieldset disabled={pending} className="api-key-controls"><label>SDK API key<input aria-label="SDK API key" type={showKey ? 'text' : 'password'} autoComplete="off" value={apiKey} onChange={event => setApiKey(event.target.value)} spellCheck={false}/></label><button type="button" aria-pressed={showKey} onClick={() => setShowKey(value => !value)}>{showKey ? '키 숨기기' : '키 표시'}</button><button type="button" disabled={!apiKey} onClick={() => { void navigator.clipboard.writeText(apiKey).then(() => setMessage('키를 복사했습니다.')).catch(() => setMessage('키 표시 후 직접 복사해 주세요.')); }}>키 복사</button></fieldset>
      <fieldset disabled={pending} className="api-options"><label><input type="checkbox" checked={autoCsrf} onChange={event => setAutoCsrf(event.target.checked)}/>CSRF 자동 적용</label><label><input type="checkbox" checked={anonymous} onChange={event => setAnonymous(event.target.checked)}/>테스트 요청에 쿠키 제외</label><span>SDK는 Bearer 키로 호출합니다.</span></fieldset>
    </section>
    <div className="api-workspace"><section className="panel api-endpoints" aria-label="API 목록"><div className="section-title"><h2>Endpoints</h2></div><label className="api-search">API 검색<input aria-label="API 검색" value={search} onChange={event => setSearch(event.target.value)} placeholder="이름 또는 경로 검색"/></label><nav aria-label="API 선택">{groups.map(group => {
      const items = endpoints.filter(item => item.group === group.id && `${item.title} ${item.path} ${item.method}`.toLowerCase().includes(search.toLowerCase()));
      return items.length > 0 && <div className="api-group" key={group.id}><h3>{group.title}</h3>{items.map(item => <a key={item.id} href={`#/api-test/${item.id}`} aria-current={endpoint.id === item.id ? 'page' : undefined} className={endpoint.id === item.id ? 'active' : ''}><span className={`api-method method-${item.method.toLowerCase()}`}>{item.method}</span><span>{item.title}</span></a>)}</div>;
    })}</nav></section>
      <section className="panel api-request" aria-label="API 요청"><div className="section-title"><h2>{endpoint.title}</h2><span className="api-expected">예상 {endpoint.status}</span></div><form onSubmit={event => void submit(event)}><p>{endpoint.description}</p><div className="api-route"><span className={`api-method method-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span><code>{endpoint.path}</code></div><small className="api-auth-summary">{endpoint.auth === 'key' ? '프로젝트 API key' : anonymous ? '쿠키 제외' : '브라우저 쿠키'} · {endpoint.csrf ? `CSRF ${autoCsrf && !anonymous ? '자동 적용' : '제외'}` : 'CSRF 불필요'}</small>
        <fieldset disabled={pending} className="api-fields">
          {!rawMode && endpoint.fields?.map(field => <label key={field.name}>{field.label}{field.required ? ' *' : ''}{field.type === 'select' ? <select value={fieldValue(field)} onChange={event => changeField(field,event.target.value)}>{field.options?.map(option => <option key={option}>{option}</option>)}</select> : ['json','textarea'].includes(field.type ?? '') ? <textarea rows={field.type === 'json' ? 5 : 3} value={fieldValue(field)} onChange={event => changeField(field,event.target.value)} required={field.required} spellCheck={false}/> : <input type={['email','password'].includes(field.type ?? '') ? field.type : 'text'} autoComplete="off" value={fieldValue(field)} required={field.required} onChange={event => changeField(field,event.target.value)}/>} {field.help && <small>{field.help}</small>}</label>)}
          {rawMode && <label>원본 JSON 요청 본문<textarea rows={10} value={raw} onChange={event => setRaw(event.target.value)} required spellCheck={false}/></label>}
          {endpoint.file && <label>업로드할 파일<input key={endpoint.id} type="file" onChange={event => setFile(event.target.files?.[0])} required/></label>}
          {endpoint.auth === 'key' && endpoint.method !== 'GET' && <><label>Idempotency-Key (비우면 자동 생성)<input value={idempotencyKey} maxLength={300} pattern="[!-~]+" onChange={event => setIdempotencyKey(event.target.value)} spellCheck={false}/></label><button type="button" onClick={() => setIdempotencyKey(crypto.randomUUID())}>새 멱등키</button><small>같은 요청을 재시도할 때는 키와 본문을 유지하세요.</small></>}
          {!endpoint.fields && <p className="api-no-body">요청 본문이 없습니다.</p>}
          {endpoint.fields && !endpoint.query && <label className="api-check"><input type="checkbox" checked={rawMode} onChange={event => { try { if (event.target.checked) setRaw(prepare(endpoint,values[endpoint.id],context).body ?? '{}'); setRawMode(event.target.checked); } catch (error) { setMessage(error instanceof Error ? error.message : '식별자를 입력하세요.'); } }}/>JSON 직접 편집</label>}
        </fieldset>
        {message && <div className="api-message" role="status">{message}</div>}
        {!supported && <p role="alert" className="api-message">현재 서버에서 지원하지 않는 API입니다. 백엔드 버전을 확인해 주세요.</p>}
        <div className="api-submit"><button className="primary" disabled={pending || !supported} type="submit">{pending ? '요청 중…' : '요청 보내기'} ↗</button>{endpoint.next && <button type="button" disabled={pending} onClick={() => go(endpoint.next!)}>다음 단계 →</button>}</div>
      </form></section>
      <div className="api-results"><section className="panel" aria-label="요청과 응답 결과"><div className="section-title"><h2>Response</h2>{selectedExchange && <span className={`api-status ${selectedExchange.ok ? 'success' : 'error'}`}>{selectedExchange.status ?? 'ERR'} · {selectedExchange.elapsed} ms</span>}</div>{selectedExchange ? <div className="api-response-body"><strong>{selectedExchange.title}</strong><small>{selectedExchange.method} {selectedExchange.path}</small>{selectedExchange.note && <p role="alert">{selectedExchange.note}</p>}<pre tabIndex={0}>{selectedExchange.responseBody || '응답 본문 없음'}</pre>{projectRows.map(row => <button key={identifier(row.projectId)} type="button" disabled={pending} onClick={() => { selectProject(row); go('project-get'); }}>{text(row.name)} · {identifier(row.projectId)} 선택 →</button>)}<details><summary>전송한 요청</summary><p>{selectedExchange.auth} · CSRF {selectedExchange.csrf ? '전송' : '없음'}</p><pre tabIndex={0}>{selectedExchange.requestBody || '요청 본문 없음'}</pre></details></div> : <div className="api-response-empty"><span>↗</span><h3>요청 결과가 여기에 표시됩니다</h3><p>API를 선택하고 요청을 보내세요.</p></div>}</section>
      <section className="panel api-history" aria-label="요청 기록"><div className="section-title"><h2>요청 기록 <small>{history.length} / 40</small></h2><button type="button" disabled={pending || !history.length} onClick={() => { setHistory([]); setSelectedExchange(undefined); }}>지우기</button></div>{history.map(entry => <button key={entry.id} type="button" className={entry.id === selectedExchange?.id ? 'active' : ''} onClick={() => setSelectedExchange(entry)}><span className={`api-status ${entry.ok ? 'success' : 'error'}`}>{entry.status ?? 'ERR'}</span><span>{entry.title}<small>{entry.method} · {entry.time}</small></span></button>)}{!history.length && <p>최근 요청은 이 화면의 메모리에만 보관됩니다.</p>}</section></div>
    </div><p className="api-footnote">자동 재시도 없이 실제 응답을 표시합니다. 비밀번호·키·인증 코드는 요청 기록에서 마스킹합니다. 로그인·로그아웃으로 ADMIN 세션이 바뀌면 이 화면을 닫습니다.</p>
  </div>;
}
