import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { endpoints, groups, initialValues } from './apiCatalog'
import type { Context, Field } from './apiCatalog'
import { identifier, object, parseResponse, prepare, requestApi } from './apiClient'
import type { Exchange } from './apiClient'
import './workbench.css'

const emptyContext: Context = { projectId: '', runId: '', version: '' }
const text = (value: unknown) => typeof value === 'string' || typeof value === 'number' ? String(value) : ''

export default function ApiWorkbench({ selectedId }: { selectedId?: string }) {
  const endpoint = endpoints.find(item => item.id === selectedId) ?? endpoints[0]
  const [values, setValues] = useState(() => Object.fromEntries(endpoints.map(item => [item.id, initialValues(item)])))
  const [context, setContext] = useState<Context>(emptyContext)
  const [apiKey, setApiKey] = useState('')
  const [keyProject, setKeyProject] = useState('')
  const [showKey, setShowKey] = useState(false)
  const [anonymous, setAnonymous] = useState(false)
  const [autoCsrf, setAutoCsrf] = useState(true)
  const [session, setSession] = useState('아직 확인하지 않음')
  const [runState, setRunState] = useState('')
  const [history, setHistory] = useState<Exchange[]>([])
  const [successes, setSuccesses] = useState<string[]>([])
  const [selectedExchange, setSelectedExchange] = useState<Exchange>()
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  const [rawMode, setRawMode] = useState(false)
  const [raw, setRaw] = useState('')

  useEffect(() => { document.title = `LabFlow · ${endpoint.title} · API 테스트` }, [endpoint.title])
  useEffect(() => { setRawMode(false); setMessage('') }, [endpoint.id])

  const groupEndpoints = endpoints.filter(item => item.group === endpoint.group)
  const next = endpoints.find(item => item.id === endpoint.next)
  const go = (id: string) => { window.location.hash = `/api-test/${id}` }
  const updateContext = (key: keyof Context, value: string) => {
    setContext(previous => ({ ...previous, [key]: value, ...(key === 'projectId' ? { version: '' } : {}) }))
    if (key === 'runId') setRunState('')
  }
  const changeField = (field: Field, value: string) => {
    if (field.binding) { updateContext(field.binding, value); return }
    setValues(previous => {
      const updated = { ...previous, [endpoint.id]: { ...previous[endpoint.id], [field.name]: value } }
      if (field.name === 'email' || field.name === 'password') {
        for (const item of endpoints.filter(item => item.group === 'auth')) {
          if (item.fields?.some(other => other.name === field.name)) updated[item.id] = { ...updated[item.id], [field.name]: value }
        }
      }
      return updated
    })
  }
  const selectProject = (data: Record<string, unknown>) => {
    const id = identifier(data.projectId)
    if (!id) return
    setContext(previous => ({ ...previous, projectId: id, version: identifier(data.version) }))
    setValues(previous => ({ ...previous, 'project-update': {
      ...previous['project-update'], name: text(data.name), description: text(data.description), visibility: text(data.visibility) || 'PRIVATE',
    } }))
  }
  const record = (entry: Exchange) => {
    setHistory(previous => [entry, ...previous].slice(0, 40))
    setSelectedExchange(entry)
    if (entry.ok) setSuccesses(previous => previous.includes(entry.endpointId) ? previous : [...previous, entry.endpointId])
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setMessage('')
    if (pending) return
    try {
      const prepared = prepare(endpoint, values[endpoint.id], context, rawMode ? raw : undefined)
      if (endpoint.id === 'project-delete' && !window.confirm(`프로젝트 ${context.projectId}와 소속 키·Run·기록을 영구 삭제할까요?`)) return
      setPending(true)
      const result = await requestApi(endpoint, prepared, { apiKey, anonymous, autoCsrf, cookie: () => document.cookie, onExchange: record })
      const data = object(result.data)
      if (!result.exchange.ok) return
      if (endpoint.id === 'login') setSession('로그인 응답 확인')
      if (endpoint.id === 'refresh') setSession('토큰 갱신 응답 확인')
      if (endpoint.id === 'logout') setSession('로그아웃 응답 확인')
      if (endpoint.id === 'project-list' && !anonymous) setSession('사용자 인증 확인')
      if (endpoint.id === 'project-create' && data) setContext(previous => ({ ...previous, projectId: identifier(data.projectId), version: '' }))
      if (endpoint.id === 'project-get' && data) selectProject(data)
      if (endpoint.id === 'project-update') {
        setContext(previous => ({ ...previous, version: '' }))
        setMessage('수정했습니다. 상세를 다시 조회해 최신 version을 가져오세요.')
      }
      if (endpoint.id === 'project-delete') {
        if (keyProject === context.projectId) { setApiKey(''); setKeyProject('') }
        setContext(emptyContext)
        setRunState('')
      }
      if (endpoint.id === 'key-issue' && typeof data?.rawApiKey === 'string') {
        setApiKey(data.rawApiKey)
        setKeyProject(context.projectId)
        setMessage('발급한 키를 SDK 요청에 연결했습니다. 필요하면 아래에서 복사하세요.')
      }
      if (endpoint.id === 'run-create' && data) {
        setContext(previous => ({ ...previous, runId: identifier(data.runId) }))
        setRunState('RUNNING')
      }
      if (endpoint.id === 'run-get' && data) setRunState(text(data.status))
      if (endpoint.id === 'run-complete') setRunState('COMPLETED')
      if (endpoint.id === 'run-fail') setRunState('FAILED')
    } catch (error) { setMessage(error instanceof Error ? error.message : '요청을 준비하지 못했습니다.') }
    finally { setPending(false) }
  }

  const bodyPreview = () => {
    try { return prepare(endpoint, values[endpoint.id], context).body ?? '' } catch { return '{}' }
  }
  const previewPath = endpoint.path.replace('{projectId}', context.projectId || '{projectId}').replace('{runId}', context.runId || '{runId}')
  const fieldValue = (field: Field) => field.binding ? context[field.binding] : values[endpoint.id][field.name] ?? ''
  let projectRows: Record<string, unknown>[] = []
  if (selectedExchange?.endpointId === 'project-list') {
    try {
      const list = object(parseResponse(selectedExchange.responseBody))?.items
      if (Array.isArray(list)) projectRows = list.flatMap(item => object(item) ? [object(item)!] : [])
    } catch { /* Non-JSON responses remain visible in the response panel. */ }
  }

  return <div className="api-workbench">
    <a className="skip-link" href="#api-main" onClick={event => { event.preventDefault(); document.getElementById('api-main')?.focus() }}>본문으로 이동</a>
    <aside className="wb-sidebar">
      <a className="wb-brand" href="#/api-test"><span>⌁</span>LabFlow</a>
      <div className="wb-sidebar-caption">INTEGRATION WORKSPACE</div>
      <nav aria-label="API 도메인">{groups.map(group => <button key={group.id} type="button" className={`wb-group ${endpoint.group === group.id ? 'active' : ''}`} onClick={() => go(endpoints.find(item => item.group === group.id)!.id)}><span>{group.label}</span><strong>{group.title}</strong><small>{successes.filter(id => endpoints.find(item => item.id === id)?.group === group.id).length}/{endpoints.filter(item => item.group === group.id).length}</small></button>)}</nav>
      <div className="wb-progress"><span>성공 응답 확인</span><strong>{successes.length}<small> / {endpoints.length}</small></strong><progress max={endpoints.length} value={successes.length} aria-label="API 성공 응답 확인 진행률" /><p>이 화면에서 확인한 결과입니다.<br />페이지를 새로고침하면 초기화됩니다.</p></div>
      <div className="wb-side-links"><a href="#/projects">디자인 미리보기 ↗</a><a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">Swagger 명세 ↗</a></div>
    </aside>
    <div className="wb-layout">
      <header className="wb-topbar"><span>개발 도구 <span>/</span> 전체 API 테스트</span><span className="wb-live"><i /> 실제 API 모드</span></header>
      <main className="wb-main" id="api-main" tabIndex={-1}>
        <div className="wb-page-heading"><div><span className="wb-eyebrow">MANUAL INTEGRATION TEST</span><h1>직접 연결하고, 확인하세요.</h1><p>회원가입부터 실험 종료까지. 구현된 {endpoints.length}개 API를 순서대로 테스트할 수 있습니다.</p></div><div className="wb-session"><span>마지막 사용자 인증 결과</span><strong>{session}</strong></div></div>

        <section className="wb-context" aria-label="요청에 사용할 인증과 식별자">
          <div className="wb-context-heading"><h2>연결 컨텍스트</h2><span>/api → 로컬 개발 프록시 → 백엔드</span></div>
          <fieldset disabled={pending} className="wb-context-fields">
            <label>Project ID<input aria-label="Project ID" value={context.projectId} placeholder="생성·선택 시 자동 입력" onChange={event => updateContext('projectId', event.target.value)} spellCheck={false} /></label>
            <label>Version<input aria-label="Project version" value={context.version} placeholder="상세 조회 시 입력" onChange={event => updateContext('version', event.target.value)} inputMode="numeric" /></label>
            <label>Run ID<input aria-label="Run ID" value={context.runId} placeholder="Run 생성 시 자동 입력" onChange={event => updateContext('runId', event.target.value)} spellCheck={false} /></label>
            <div className="wb-run-state"><span>마지막 Run 상태</span><strong className={runState === 'FAILED' ? 'failed' : ''}>{runState || '확인 전'}</strong></div>
          </fieldset>
          <fieldset disabled={pending} className="wb-key-row"><label>SDK API key{keyProject && <small> · 발급 프로젝트 {keyProject}</small>}<input aria-label="SDK API key" type={showKey ? 'text' : 'password'} autoComplete="off" value={apiKey} placeholder="키 발급 시 자동 연결 / 기존 키 붙여넣기" onChange={event => { setApiKey(event.target.value); setKeyProject('') }} spellCheck={false} /></label><button type="button" className="wb-secondary" onClick={() => setShowKey(value => !value)}>{showKey ? '키 숨기기' : '키 표시'}</button><button type="button" className="wb-secondary" disabled={!apiKey} onClick={async () => { try { await navigator.clipboard.writeText(apiKey); setMessage('API key를 복사했습니다.') } catch { setMessage('클립보드 접근이 허용되지 않았습니다. 키 표시 후 직접 복사하세요.') } }}>키 복사</button></fieldset>
          <fieldset disabled={pending} className="wb-options"><label><input type="checkbox" checked={autoCsrf} onChange={event => setAutoCsrf(event.target.checked)} /> CSRF 자동 적용</label><label><input type="checkbox" checked={anonymous} onChange={event => setAnonymous(event.target.checked)} /> 사용자 API를 쿠키 없이 호출</label><span>SDK 요청은 항상 쿠키 없이 Bearer 키만 전송합니다.</span></fieldset>
        </section>

        <div className="wb-workspace">
          <section className="wb-request-panel" aria-labelledby="wb-request-heading">
            <div className="wb-panel-heading"><h2 id="wb-request-heading">{groups.find(group => group.id === endpoint.group)?.title}</h2><span>{groupEndpoints.length} endpoints</span></div>
            <nav className="wb-actions" aria-label="API 선택">{groupEndpoints.map(item => <button type="button" key={item.id} className={item.id === endpoint.id ? 'selected' : ''} onClick={() => go(item.id)}><span className={`wb-method ${item.method.toLowerCase()}`}>{item.method}</span><span>{item.title}</span>{successes.includes(item.id) && <span className="wb-checked" aria-label="성공 응답 확인됨">✓</span>}</button>)}</nav>
            <form onSubmit={submit} className="wb-form">
              <div className="wb-endpoint-title"><h2>{endpoint.title}</h2><span className="wb-expected">예상 {endpoint.status}</span></div>
              <p className="wb-description">{endpoint.description}</p>
              <code className="wb-route"><span className={`wb-method ${endpoint.method.toLowerCase()}`}>{endpoint.method}</span>{previewPath}</code>
              <div className="wb-auth-note">{endpoint.auth === 'key' ? '인증: 프로젝트 API key' : anonymous ? '인증: 쿠키 전송 안 함' : '인증: 브라우저 쿠키'}{endpoint.csrf ? ` · CSRF ${autoCsrf && !anonymous ? '자동' : '헤더 전송 안 함'}` : ' · CSRF 불필요'}</div>
              <fieldset disabled={pending} className="wb-body-fields">
                {!rawMode && endpoint.fields?.map(field => <label key={field.name} className={`wb-field ${field.type === 'json' || field.type === 'textarea' ? 'wide' : ''}`} htmlFor={`field-${field.name}`}><span>{field.label}{field.required && <b> *</b>}</span>
                  {field.type === 'select' ? <select id={`field-${field.name}`} value={fieldValue(field)} onChange={event => changeField(field, event.target.value)}>{field.options?.map(option => <option key={option}>{option}</option>)}</select>
                    : field.type === 'textarea' || field.type === 'json' ? <textarea id={`field-${field.name}`} rows={field.type === 'json' ? 5 : 3} value={fieldValue(field)} onChange={event => changeField(field, event.target.value)} required={field.required} spellCheck={field.type !== 'json'} className={field.type === 'json' ? 'wb-json' : ''} />
                    : <input id={`field-${field.name}`} type={field.type === 'email' || field.type === 'password' ? field.type : 'text'} inputMode={field.type === 'integer' ? 'numeric' : field.type === 'number' ? 'decimal' : undefined} autoComplete={field.type === 'password' ? 'off' : undefined} value={fieldValue(field)} required={field.required} onChange={event => changeField(field, event.target.value)} />}
                  {field.help && <small>{field.help}</small>}
                </label>)}
                {rawMode && <label className="wb-field wide" htmlFor="raw-body"><span>원본 JSON 요청 본문</span><textarea id="raw-body" className="wb-json" rows={12} value={raw} onChange={event => setRaw(event.target.value)} required /><small>입력한 JSON을 변환 없이 전송합니다. 서버의 입력 검증도 테스트할 수 있습니다.</small></label>}
                {!endpoint.fields && <div className="wb-no-body">이 요청은 본문을 전송하지 않습니다.</div>}
              </fieldset>
              {endpoint.fields && !endpoint.query && <label className="wb-raw-toggle"><input type="checkbox" checked={rawMode} disabled={pending} onChange={event => { if (event.target.checked) setRaw(bodyPreview()); setRawMode(event.target.checked) }} /> JSON 직접 편집</label>}
              {message && <div role="status" className="wb-message">{message}</div>}
              <div className="wb-submit-row"><button type="submit" disabled={pending} className={`wb-primary ${endpoint.method === 'DELETE' ? 'danger' : ''}`}>{pending ? '요청 중…' : endpoint.method === 'DELETE' ? '프로젝트 삭제' : '요청 보내기'}<span>↗</span></button>{next && <button type="button" className="wb-next" disabled={pending} onClick={() => go(next.id)}>다음: {next.title} →</button>}</div>
            </form>
          </section>

          <div className="wb-result-column">
            <section className="wb-response-panel" aria-label="요청과 응답 결과" aria-live="polite"><div className="wb-panel-heading"><h2>Response</h2>{selectedExchange && <span className={`wb-response-status ${selectedExchange.ok ? 'success' : 'error'}`}>{selectedExchange.status ?? '연결 실패'} · {selectedExchange.elapsed} ms</span>}</div>
              {selectedExchange ? <div className="wb-response-content"><div className="wb-response-meta"><strong>{selectedExchange.title}</strong><span>{selectedExchange.time}</span></div><p className="wb-response-path">{selectedExchange.method} {selectedExchange.path}</p>{selectedExchange.note && <p role="alert" className="wb-error-note">{selectedExchange.note}</p>}<pre className="wb-response-code">{selectedExchange.responseBody || `응답 본문 없음${selectedExchange.status ? ` · HTTP ${selectedExchange.status}` : ''}`}</pre>
                {projectRows.length > 0 && <div className="wb-project-results"><h3>프로젝트 선택</h3>{projectRows.map(row => <button type="button" key={identifier(row.projectId)} disabled={pending} onClick={() => { selectProject(row); go('project-get') }}><span><strong>{text(row.name)}</strong><small>{identifier(row.projectId)} · {text(row.visibility)}</small></span><span>선택 →</span></button>)}</div>}
                <details className="wb-request-details"><summary>전송한 요청 확인</summary><dl><div><dt>인증</dt><dd>{selectedExchange.auth}</dd></div><div><dt>CSRF 헤더</dt><dd>{selectedExchange.csrf ? '전송함' : '전송 안 함'}</dd></div></dl><pre>{selectedExchange.requestBody || '요청 본문 없음'}</pre></details>
              </div> : <div className="wb-response-empty"><span>↗</span><h3>첫 요청을 기다리고 있어요</h3><p>왼쪽에서 API를 선택하고 요청을 보내세요.<br />실제 상태 코드와 응답이 여기에 표시됩니다.</p></div>}
            </section>
            <section className="wb-history-panel" aria-label="요청 기록"><div className="wb-panel-heading"><h2>요청 기록 <small>{history.length}</small></h2><button type="button" className="wb-clear" disabled={pending || !history.length} onClick={() => { setHistory([]); setSuccesses([]); setSelectedExchange(undefined) }}>기록 지우기</button></div>{history.length ? <div className="wb-history-list">{history.map(entry => <button type="button" key={entry.id} className={selectedExchange?.id === entry.id ? 'selected' : ''} onClick={() => setSelectedExchange(entry)}><span className={`wb-history-dot ${entry.ok ? 'success' : 'error'}`} /><span><strong>{entry.title}</strong><small>{entry.method} · {entry.time}</small></span><b>{entry.status ?? 'ERR'}</b></button>)}</div> : <p className="wb-history-empty">최근 40개 요청을 이 화면에만 보관합니다.</p>}</section>
          </div>
        </div>
        <footer className="wb-footer"><span><strong>LabFlow</strong> / Local integration workspace</span><span>비밀번호·키는 저장소에 보관하지 않으며 요청 기록에서 마스킹합니다.</span></footer>
      </main>
    </div>
  </div>
}
