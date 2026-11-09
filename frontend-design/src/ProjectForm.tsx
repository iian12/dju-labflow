import { useState } from 'react'
import { api, projectPath } from './productApi'
import type { Project } from './productApi'
import { ErrorBox, Modal } from './ProductShared'

export default function ProjectForm({ project, close, saved }: { project?: Project; close: () => void; saved: (id: string) => Promise<void> }) {
  const [name, setName] = useState(project?.name ?? '')
  const [description, setDescription] = useState(project?.description ?? '')
  const [visibility, setVisibility] = useState(project?.visibility ?? 'PRIVATE')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  return <Modal title={project ? '프로젝트 설정' : '새 프로젝트'} onClose={() => { if (!busy) close() }}><form onSubmit={async event => {
    event.preventDefault(); setBusy(true); setError(undefined)
    try {
      const body = { name, description, visibility, ...(project ? { version: project.version } : {}) }
      const result = await api<{ projectId: string }>(project ? projectPath(project.projectId) : '/api/v1/projects', { method: project ? 'PUT' : 'POST', body })
      await saved(project?.projectId ?? String(result.projectId)); close()
    } catch (issue) { setError(issue) } finally { setBusy(false) }
  }}><fieldset disabled={busy}><label>프로젝트 이름<input name="projectName" value={name} onChange={event => setName(event.target.value)} required maxLength={100} autoFocus placeholder="예: 손글씨 숫자 분류" /></label><label>설명<textarea value={description} onChange={event => setDescription(event.target.value)} maxLength={2000} rows={3} placeholder="실험의 목적을 간단히 적어 주세요." /></label><label>공개 범위<select value={visibility} onChange={event => setVisibility(event.target.value as Project['visibility'])}><option value="PRIVATE">PRIVATE · 나만 보기</option><option value="PUBLIC">PUBLIC · 링크를 가진 누구나 보기</option></select></label><p className="p-field-help">개인 프로젝트입니다. PUBLIC 프로젝트의 실험과 기록은 로그인 없이 조회할 수 있습니다.</p><ErrorBox error={error} /><div className="p-modal-actions"><button type="button" className="p-secondary" onClick={close}>취소</button><button className="p-primary" type="submit">{busy ? '저장 중…' : project ? '변경사항 저장' : '프로젝트 만들기'}</button></div></fieldset></form></Modal>
}
