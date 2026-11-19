import RunArtifacts from './RunArtifacts.tsx';
import { useState } from 'react';
import { api, projectPath } from './api.ts';
import { useApi } from './useData.ts';
import type { LiveRun } from './liveTypes.ts';
import { RecordViewer } from './RunRecordsBrowser.tsx';
import { recordKind, recordKinds, recordPageUrl } from './runRecordsApi.ts';
import type { RecordPage } from './runRecordsApi.ts';
import { Badge, date, duration, ErrorBox } from './LiveShared.tsx';
const tabs = [['metrics','Metrics'],['parameters','Parameters'],['logs','Logs'],['texts','Texts'],['system-metrics','System'],['artifacts','Artifacts'],['environment','Environment']];
export default function LiveRunDetail({ projectId, runId, tab, scope, compare }: { projectId: string; runId: string; tab: string; scope: string; compare: () => void }) {
  const path = `${projectPath(projectId)}/runs/${encodeURIComponent(runId)}`;
  const run = useApi<LiveRun>(path, scope, 5000);
  const parameters = useApi<{ parameters: { key: string; value: string }[] }>(['parameters','artifacts'].includes(tab) ? `${path}/records?limit=1` : null, scope);
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>();
  async function exportRecords() {
    if (busy) return; setBusy(true); setError(undefined);
    try {
      const output: Record<string, unknown> = { run: run.data, parameters: parameters.data?.parameters ?? [] };
      for (const kind of recordKinds) {
        const items: RecordPage['items'] = []; let next: string | null = null;
        do { const url: string = next ? recordPageUrl(projectId, runId, kind.id, { direction: 'after', cursor: next }, 1000) : `${path}/records/${kind.id}?limit=1000`;
          const page: RecordPage = await api<RecordPage>(url); items.push(...page.items); next = page.hasMore ? page.nextCursor : null;
        } while (next);
        output[kind.id] = items;
      }
      const url = URL.createObjectURL(new Blob([JSON.stringify(output, null, 2)], { type: 'application/json' }));
      const link = document.createElement('a'); link.href = url; link.download = `run-${runId}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (issue) { setError(issue); } finally { setBusy(false); }
  }
  return <><ErrorBox error={run.error} retry={run.refresh}/>{run.loading && <p role="status" className="live-empty">실험을 불러오는 중…</p>}{run.data && <><div className="page-heading run-heading"><div><a className="back" href={`#/projects/${projectId}/runs`}>← 실험 기록으로</a><div className="run-name"><h1>{run.data.name}</h1><Badge status={run.data.status}/></div><p>{runId} · {duration(run.data.startedAt, run.data.endedAt)} · 시작 {date(run.data.startedAt)}</p></div><button onClick={compare}>비교에 추가 →</button></div>{run.data.error && <div className="failure"><div><strong>실험이 실패했습니다</strong><p>{run.data.error}</p></div><a href={`#/projects/${projectId}/runs/${runId}/logs`}>오류 로그 보기 →</a></div>}
    <nav className="tabs live-run-tabs" aria-label="실험 상세">{tabs.map(([id, label]) => <a key={id} className={tab === id ? 'active' : ''} href={`#/projects/${projectId}/runs/${runId}/${id}`} aria-current={tab === id ? 'page' : undefined}>{label}</a>)}</nav>
    {['metrics','logs','texts','system-metrics'].includes(tab) && <RecordViewer key={`${runId}/${tab}`} embedded projectId={projectId} runId={runId} kind={recordKind(tab)}/>}
    {tab === 'parameters' && <><ErrorBox error={parameters.error} retry={parameters.refresh}/><section className="panel"><div className="section-title"><h2>학습 설정</h2></div><div className="live-table-scroll"><table className="keyvalues"><tbody>{parameters.data?.parameters.map(item => <tr key={item.key}><th>{item.key}</th><td>{item.value === '' ? '(빈 문자열)' : item.value}</td></tr>)}</tbody></table></div>{parameters.data && !parameters.data.parameters.length && <p className="live-empty">기록된 파라미터가 없습니다.</p>}</section></>}
    {tab === 'environment' && <section className="panel"><div className="section-title"><h2>실행 환경 및 메타데이터</h2></div><div className="live-table-scroll"><table className="keyvalues"><tbody>{Object.entries(run.data.metadata).map(([key, value]) => <tr key={key}><th>{key}</th><td>{value}</td></tr>)}</tbody></table></div>{!Object.keys(run.data.metadata).length && <p className="live-empty">기록된 메타데이터가 없습니다.</p>}<p className="live-chart-note">마지막 Heartbeat · {date(run.data.lastHeartbeatAt)}</p></section>}
    {tab === 'artifacts' && <RunArtifacts key={path + scope} path={path} scope={scope}/>}
    {tab === 'artifacts' && <section className="panel prose"><h2>실험 기록 내보내기</h2><p>실제 저장된 파라미터, 지표, 로그, 텍스트, 시스템 지표를 JSON 파일로 내보냅니다.</p><ErrorBox error={error} retry={() => void exportRecords()}/><button disabled={busy} onClick={() => void exportRecords()}>{busy ? '기록을 모으는 중…' : '전체 기록 다운로드'}</button></section>}
  </>}</>;
}
