import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, HttpError } from './productApi'
import { ErrorBox, Logo, go } from './ProductShared'

export default function AuthScreen({ mode, email, setEmail, onLogin }: { mode: string; email: string; setEmail: (value: string) => void; onLogin: () => Promise<void> }) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<unknown>()
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  useEffect(() => { setError(undefined); setNotice('') }, [mode])
  useEffect(() => { if (!cooldown) return; const timer = setTimeout(() => setCooldown(value => value - 1), 1000); return () => clearTimeout(timer) }, [cooldown])
  const signup = mode === 'signup'
  const verify = mode === 'verify'
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(undefined)
    try {
      if (signup) {
        if (password !== confirm) throw new Error('비밀번호 확인이 일치하지 않습니다.')
        await api('/api/v1/auth/sign-up', { method: 'POST', body: { name, email, password, confirmPassword: confirm }, retryAuth: false })
        setPassword(''); setConfirm(''); setCooldown(60); go('/verify')
      } else if (verify) {
        await api('/api/v1/auth/email-verifications/verify', { method: 'POST', body: { email, code }, retryAuth: false })
        setCode(''); go('/login')
      } else {
        await api('/api/v1/auth/login', { method: 'POST', body: { email, password }, retryAuth: false })
        setPassword(''); await onLogin()
      }
    } catch (issue) {
      if (!signup && !verify && issue instanceof HttpError && issue.code === 'EMAIL_VERIFICATION_REQUIRED') {
        setPassword(''); setCode(''); go('/verify')
      } else { setError(issue) }
    } finally { setBusy(false) }
  }
  return <div className="p-auth"><div className="p-auth-story"><Logo /><div><span>YOUR EXPERIMENTS, CONNECTED</span><h1>학습의 모든 순간을<br />하나의 흐름으로.</h1><p>파라미터부터 마지막 예측까지.<br />실험을 기록하고, 비교하고, 다음 발견으로 이어가세요.</p><div className="p-auth-chart" aria-hidden="true"><div><span>Training loss</span><strong>실험의 변화를 한눈에</strong></div><svg viewBox="0 0 360 120"><path d="M5 12 35 35 65 29 95 58 125 65 155 61 185 85 215 87 245 98 275 95 305 105 355 109" fill="none" stroke="#b7a3e3" strokeWidth="3" /><path d="M5 27 35 46 65 41 95 70 125 76 155 72 185 91 215 93 245 100 275 103 305 108 355 112" fill="none" stroke="#76a594" strokeWidth="2" /></svg></div></div><small>LabFlow · Experiment workspace</small></div>
    <div className="p-auth-form"><div className="p-auth-mobile-brand"><Logo /></div><span className="p-eyebrow">{signup ? 'START YOUR WORKSPACE' : verify ? 'ONE MORE STEP' : 'WELCOME BACK'}</span><h2>{signup ? '회원가입' : verify ? '이메일을 확인해 주세요' : '다시 만나 반가워요'}</h2><p>{signup ? '개인 실험 공간을 만들어 보세요.' : verify ? '받은 인증 코드를 입력하면 준비가 끝납니다.' : '로그인하고 실험을 이어가세요.'}</p><form onSubmit={submit}><fieldset disabled={busy}>
      {signup && <label>이름<input name="name" value={name} onChange={event => setName(event.target.value)} autoComplete="name" required maxLength={100} /></label>}
      <label>이메일<input name="email" type="email" value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" required /></label>
      {verify ? <label>인증 코드<input name="code" value={code} onChange={event => setCode(event.target.value.toUpperCase())} autoComplete="one-time-code" required maxLength={32} placeholder="메일로 받은 6자리 코드" /></label> : <label>비밀번호<input name="password" type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete={signup ? 'new-password' : 'current-password'} required minLength={8} maxLength={72} /></label>}
      {signup && <label>비밀번호 확인<input name="confirmPassword" type="password" value={confirm} onChange={event => setConfirm(event.target.value)} autoComplete="new-password" required minLength={8} /></label>}
      <ErrorBox error={error} />{notice && <p className="p-notice" role="status">{notice}</p>}<button className="p-primary p-full" type="submit">{busy ? '처리 중…' : signup ? '계정 만들기' : verify ? '이메일 인증하기' : '로그인'} <span>→</span></button>
      {verify && <button type="button" className="p-link-button" disabled={cooldown > 0} onClick={async () => { setBusy(true); setError(undefined); try { await api('/api/v1/auth/email-verifications/resend', { method: 'POST', body: { email }, retryAuth: false }); setCooldown(60); setNotice('재발송 요청을 접수했습니다. 메일함과 스팸함을 확인해 주세요.') } catch (issue) { setError(issue) } finally { setBusy(false) } }}>{cooldown ? `${cooldown}초 후 재발송` : '인증 코드 다시 받기'}</button>}
    </fieldset></form><div className="p-auth-links">{signup || verify ? <a href="#/login">로그인으로 돌아가기</a> : <><span>아직 계정이 없으신가요?</span><a href="#/signup">회원가입</a><a href="#/verify">이메일 인증</a></>}</div><a className="p-api-link" href="#/api-test">API 테스트 도구 ↗</a></div>
  </div>
}
