import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { HttpError } from './productApi'
import type { Run } from './productApi'

export const go = (path: string) => { window.location.hash = path }
export const projectLink = (id: string, view = 'overview') => `#/projects/${id}/${view}`
export const date = (value?: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '—'
export const errorText = (error: unknown) => error instanceof Error ? error.message : '요청을 처리하지 못했습니다.'
export const duration = (run: Run) => {
  const seconds = Math.max(0, Math.floor(((run.endedAt ? new Date(run.endedAt).getTime() : Date.now()) - new Date(run.startedAt).getTime()) / 1000))
  return seconds >= 3600 ? `${Math.floor(seconds / 3600)}h ${Math.floor(seconds % 3600 / 60)}m` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}
export function Logo() { return <span className="p-logo"><span>⌁</span>LabFlow</span> }
export function Badge({ status }: { status: Run['status'] }) { return <span className={`status-badge ${status.toLowerCase()}`}><i />{{ RUNNING: '진행 중', COMPLETED: '완료', FAILED: '실패' }[status]}</span> }
export function Empty({ title, children }: { title: string; children?: ReactNode }) { return <div className="p-empty"><span>⌁</span><h3>{title}</h3>{children}</div> }
export function ErrorBox({ error, retry }: { error?: unknown; retry?: () => void }) { return error ? <div className="p-error" role="alert"><span>{errorText(error)}</span>{retry && <button onClick={retry}>다시 불러오기</button>}{error instanceof HttpError && error.status === 401 && <a href="#/login">로그인 →</a>}</div> : null }

export function useRemote<T>(key: string, loader: (signal: AbortSignal) => Promise<T>, interval = 0) {
  const [tick, setTick] = useState(0)
  const [state, setState] = useState<{ key: string; data?: T; error?: unknown; loading: boolean }>({ key, loading: true })
  useEffect(() => {
    const controller = new AbortController()
    let busy = false
    async function load() {
      if (busy) return
      busy = true
      try {
        const data = await loader(controller.signal)
        if (!controller.signal.aborted) setState({ key, data, loading: false })
      } catch (error) {
        if (!controller.signal.aborted) setState(previous => ({ key, data: previous.key === key ? previous.data : undefined, error, loading: false }))
      } finally { busy = false }
    }
    void load()
    const timer = interval ? setInterval(() => { if (!document.hidden) void load() }, interval) : undefined
    return () => { controller.abort(); if (timer) clearInterval(timer) }
  }, [key, loader, tick, interval])
  return { ...(state.key === key ? state : { key, loading: true, data: undefined, error: undefined }), refresh: () => setTick(value => value + 1) }
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="p-modal" onCancel={onClose}><div className="p-modal-heading"><h2>{title}</h2><button type="button" onClick={onClose} aria-label="닫기">×</button></div>{children}</dialog>
}
