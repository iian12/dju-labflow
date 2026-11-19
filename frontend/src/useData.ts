import { useEffect, useRef, useState } from 'react';
import { api } from './api.ts';
export function useData<T>(key: string | null, loader: (signal: AbortSignal) => Promise<T>, interval = 0) {
  const current = useRef(loader); current.current = loader;
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<{ key: string | null; data?: T; error?: unknown; loading: boolean }>({ key: null, loading: false });
  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController(); let running = false;
    async function load() {
      if (running) return; running = true;
      try { const data = await current.current(controller.signal); if (!controller.signal.aborted) setState({ key, data, loading: false }); }
      catch (error) { if (!controller.signal.aborted) setState(previous => ({ key, data: previous.key === key ? previous.data : undefined, error, loading: false })); }
      finally { running = false; }
    }
    void load();
    const timer = interval ? setInterval(() => { if (!document.hidden) void load(); }, interval) : undefined;
    return () => { controller.abort(); if (timer) clearInterval(timer); };
  }, [key, version, interval]);
  return { ...(key === null ? { loading: false, data: undefined, error: undefined } : state.key === key ? state : { loading: true, data: undefined, error: undefined }), refresh: () => setVersion(value => value + 1) };
}
export function useApi<T>(path: string | null, scope = '', interval = 0) {
  return useData<T>(path === null ? null : `${scope}:${path}`, signal => api<T>(path!, { signal }), interval);
}
