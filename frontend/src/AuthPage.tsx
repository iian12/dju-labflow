import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import ThemeToggle from './ThemeToggle.tsx';
import { AuthError, submitAuth } from './authApi.ts';
import './auth.css';

export type AuthMode = 'login' | 'signup' | 'verify';

function Brand() {
  return <a className="brand" href="#/projects" aria-label="LabFlow 프로젝트"><span className="brand-mark" aria-hidden="true">∿</span>LabFlow<span className="studio">Studio</span></a>;
}

function Eye({ visible }: { visible: boolean }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>{visible && <path d="m3 3 18 18"/>}</svg>;
}

export default function AuthPage({ mode, email, setEmail, onLogin }: { mode: AuthMode; email: string; setEmail: (value: string) => void; onLogin: () => void }) {
  const signup = mode === 'signup';
  const verify = mode === 'verify';
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [code, setCode] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [resendAt, setResendAt] = useState(0);
  const [now, setNow] = useState(Date.now());
  const submitting = useRef(false);
  const verificationComplete = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const cooldown = Math.max(0, Math.ceil((resendAt - now) / 1000));

  useEffect(() => {
    setPassword(''); setConfirm(''); setCode(''); setVisible(false); setError('');
    setNotice(mode === 'login' && verificationComplete.current ? '이메일 인증이 완료되었습니다. 로그인해 주세요.' : '');
    verificationComplete.current = false;
    document.title = `LabFlow · ${signup ? '회원가입' : verify ? '이메일 인증' : '로그인'}`;
    heading.current?.focus();
  }, [mode, signup, verify]);

  useEffect(() => {
    if (!resendAt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [resendAt]);

  function startCooldown() { const time = Date.now(); setNow(time); setResendAt(time + 60000); }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    setError(''); setNotice('');
    if (signup && password !== confirm) { setError('비밀번호 확인이 일치하지 않습니다.'); return; }
    if (!verify && new TextEncoder().encode(password).length > 72) { setError('비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요.'); return; }
    submitting.current = true; setBusy(true);
    try {
      const address = email.trim();
      setEmail(address);
      if (signup) {
        await submitAuth('sign-up', { name: name.trim(), email: address, password, confirmPassword: confirm });
        setPassword(''); setConfirm(''); startCooldown(); location.hash = '/verify';
      } else if (verify) {
        await submitAuth('email-verifications/verify', { email: address, code: code.trim() });
        verificationComplete.current = true; location.hash = '/login';
      } else {
        await submitAuth('login', { email: address, password });
        setPassword(''); onLogin();
      }
    } catch (issue) {
      if (mode === 'login' && issue instanceof AuthError && issue.code === 'EMAIL_VERIFICATION_REQUIRED') {
        location.hash = '/verify';
      } else { setError(issue instanceof Error ? issue.message : '요청을 처리하지 못했습니다. 다시 시도해 주세요.'); }
    } finally { submitting.current = false; setBusy(false); }
  }

  async function resend() {
    if (submitting.current || cooldown) return;
    submitting.current = true; setBusy(true); setError(''); setNotice('');
    try {
      await submitAuth('email-verifications/resend', { email: email.trim() });
      startCooldown(); setNotice('인증 코드 재발송을 요청했습니다. 메일함과 스팸함을 확인해 주세요.');
    } catch (issue) { setError(issue instanceof Error ? issue.message : '인증 코드를 보내지 못했습니다.'); }
    finally { submitting.current = false; setBusy(false); }
  }

  return <div className="auth-page">
    <section className="auth-story" aria-label="LabFlow 소개">
      <Brand/>
      <div className="auth-story-content">
        <div className="auth-eyebrow">YOUR EXPERIMENTS, CONNECTED</div>
        <h2>학습의 모든 순간을<br/>하나의 흐름으로.</h2>
        <p>파라미터부터 마지막 예측까지.<br/>실험을 기록하고, 비교하고, 다음 발견으로 이어가세요.</p>
        <div className="auth-chart" aria-hidden="true">
          <div className="auth-chart-heading"><span><i/>실험의 변화를 한눈에</span><span>Training loss ↓</span></div>
          <svg viewBox="0 0 400 156" fill="none">
            {[24,64,104,144].map(y => <line key={y} x1="0" y1={y} x2="400" y2={y} stroke="currentColor" strokeDasharray="3 5" opacity=".15"/>)}
            <path d="M0 18 20 38 40 30 60 57 80 48 100 79 120 72 140 92 160 85 180 103 200 97 220 115 240 111 260 123 280 118 300 131 320 126 340 136 360 132 380 139 400 137" stroke="var(--curve-1)" strokeWidth="2.5" strokeLinejoin="round"/>
            <path d="M0 34 20 46 40 42 60 72 80 65 100 88 120 81 140 99 160 95 180 114 200 107 220 122 240 118 260 131 280 126 300 138 320 133 340 143 360 140 380 146 400 144" stroke="var(--curve-2)" strokeWidth="2" strokeLinejoin="round"/>
          </svg>
          <div className="auth-chart-footer"><span><i/>baseline <i/>experiment</span><span>Step →</span></div>
        </div>
        <div className="auth-story-note"><span>01</span> 기록이 쌓이면, 다음 실험이 선명해집니다.</div>
      </div>
      <div className="auth-story-footer">LabFlow Studio <span>Experiment workspace</span></div>
    </section>
    <div className="auth-form-side">
      <div className="auth-topbar"><div className="auth-mobile-brand"><Brand/></div><a href="#/projects" className="auth-back">← 프로젝트 둘러보기</a><ThemeToggle/></div>
      <main className="auth-main">
        <div className="auth-form-heading">
          <div className="auth-eyebrow">{signup ? 'START YOUR WORKSPACE' : verify ? 'ONE MORE STEP' : 'WELCOME BACK'}</div>
          <h1 ref={heading} tabIndex={-1}>{signup ? '회원가입' : verify ? '이메일을 확인해 주세요' : '다시 만나 반가워요'}</h1>
          <p>{signup ? '나만의 실험 공간에서 첫 기록을 시작하세요.' : verify ? '메일로 받은 인증 코드를 입력해 주세요.' : '로그인하고 실험의 흐름을 이어가세요.'}</p>
        </div>
        {!verify && <nav className="auth-tabs" aria-label="계정 메뉴"><a href="#/login" aria-current={!signup ? 'page' : undefined}>로그인</a><a href="#/signup" aria-current={signup ? 'page' : undefined}>회원가입</a></nav>}
        <form onSubmit={submit} aria-busy={busy}>
          <fieldset disabled={busy}>
            <legend className="auth-sr-only">{signup ? '회원가입 정보' : verify ? '이메일 인증 정보' : '로그인 정보'}</legend>
            {signup && <div className="auth-field"><label htmlFor="auth-name">이름</label><input id="auth-name" name="name" autoComplete="name" placeholder="홍길동" required maxLength={100} value={name} onChange={event => setName(event.target.value)} pattern=".*\S.*"/></div>}
            <div className="auth-field"><label htmlFor="auth-email">이메일</label><input id="auth-email" name="email" type="email" autoComplete="email" placeholder="name@example.com" required maxLength={254} value={email} onChange={event => setEmail(event.target.value)}/></div>
            {verify ? <div className="auth-field"><label htmlFor="auth-code">인증 코드</label><input id="auth-code" name="code" autoComplete="one-time-code" placeholder="메일로 받은 인증 코드" required maxLength={32} value={code} onChange={event => setCode(event.target.value.toUpperCase())}/><span className="auth-hint">메일이 보이지 않으면 스팸함도 확인해 주세요.</span></div>
              : <div className="auth-field"><label htmlFor="auth-password">비밀번호</label><div className="auth-password"><input id="auth-password" name="password" type={visible ? 'text' : 'password'} autoComplete={signup ? 'new-password' : 'current-password'} placeholder={signup ? '8자 이상 입력해 주세요' : '비밀번호를 입력해 주세요'} required minLength={signup ? 8 : undefined} maxLength={72} value={password} onChange={event => setPassword(event.target.value)} aria-describedby={signup ? 'auth-password-hint' : undefined}/><button type="button" onClick={() => setVisible(value => !value)} aria-label={visible ? '비밀번호 숨기기' : '비밀번호 보기'} aria-pressed={visible}><Eye visible={visible}/></button></div>{signup && <span className="auth-hint" id="auth-password-hint">8자 이상 · 최대 72바이트</span>}</div>}
            {signup && <div className="auth-field"><label htmlFor="auth-confirm">비밀번호 확인</label><input id="auth-confirm" name="confirmPassword" type={visible ? 'text' : 'password'} autoComplete="new-password" placeholder="비밀번호를 한 번 더 입력해 주세요" required minLength={8} maxLength={72} value={confirm} onChange={event => setConfirm(event.target.value)}/></div>}
            {error && <div className="auth-message auth-error" role="alert">{error}</div>}
            {notice && <div className="auth-message" role="status">{notice}</div>}
            <button type="submit" className="primary auth-submit">{busy ? '처리 중…' : signup ? '계정 만들기' : verify ? '이메일 인증하기' : '로그인'}<span aria-hidden="true">→</span></button>
          </fieldset>
        </form>
        {verify ? <div className="auth-bottom"><span>인증 메일을 받지 못하셨나요?</span><button type="button" className="auth-text-button" disabled={busy || cooldown > 0 || !email.trim()} onClick={resend}>{cooldown ? `${cooldown}초 후 재발송` : '인증 코드 다시 받기'}</button><a href="#/login">로그인으로 돌아가기</a></div>
          : <div className="auth-bottom"><span>{signup ? '이미 계정이 있으신가요?' : '아직 계정이 없으신가요?'}</span><a href={signup ? '#/login' : '#/signup'}>{signup ? '로그인' : '회원가입'}</a>{!signup && <a className="auth-verify-link" href="#/verify">이메일 인증</a>}</div>}
      </main>
      <footer className="auth-footer">실험의 모든 기록을 한곳에. <span>LabFlow</span></footer>
    </div>
  </div>;
}
