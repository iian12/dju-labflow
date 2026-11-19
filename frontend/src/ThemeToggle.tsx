import { useEffect, useState } from 'react';

export default function ThemeToggle() {
  const [theme, setTheme] = useState(document.documentElement.dataset.theme);
  const dark = theme === 'dark';

  function apply(next: 'light' | 'dark') {
    document.documentElement.dataset.theme = next;
    setTheme(next);
  }

  useEffect(() => {
    const system = matchMedia('(prefers-color-scheme: dark)');
    function sync() {
      let saved;
      try { saved = localStorage.getItem('lf-theme'); } catch {}
      apply(saved === 'light' || saved === 'dark' ? saved : system.matches ? 'dark' : 'light');
    }
    system.addEventListener('change', sync);
    window.addEventListener('storage', sync);
    return () => {
      system.removeEventListener('change', sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  function toggle() {
    const next = dark ? 'light' : 'dark';
    try { localStorage.setItem('lf-theme', next); } catch {}
    apply(next);
  }

  return <button type="button" className="theme-toggle" aria-label="다크 모드" aria-pressed={dark}
    title={dark ? '라이트 모드로 전환' : '다크 모드로 전환'} onClick={toggle}>
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
      {dark ? <><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></> :
        <path d="M20.5 14A8.8 8.8 0 0 1 10 3.5 9 9 0 1 0 20.5 14Z"/>}
    </svg>
  </button>;
}
