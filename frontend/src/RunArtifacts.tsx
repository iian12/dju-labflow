import { useState } from 'react';
import { useApi } from './useData.ts';
import { ErrorBox } from './LiveShared.tsx';
import type { Page } from './api.ts';

type Artifact = { artifactId: string; name: string; sizeBytes: number };

export default function RunArtifacts({ path, scope }: { path: string; scope: string }) {
  const [page, setPage] = useState(0);
  const { data, loading, error, refresh } = useApi<Page<Artifact>>(
    `${path}/artifacts?page=${page}&size=50`, scope
  );
  return <section className="panel">
    <div className="section-title"><h2>업로드된 파일</h2><button onClick={refresh}>새로고침</button></div>
    <ErrorBox error={error} retry={refresh}/>
    {loading && <p role="status" className="live-empty">파일 목록을 불러오는 중…</p>}
    {data && (data.items.length ? <div className="live-table-scroll"><table>
      <thead><tr><th>파일명</th><th>크기</th><th>다운로드</th></tr></thead>
      <tbody>{data.items.map(file => <tr key={file.artifactId}>
        <td>{file.name}</td><td>{file.sizeBytes.toLocaleString()} B</td>
        <td><a href={`${path}/artifacts/${file.artifactId}`} download aria-label={`${file.name} 다운로드`}>다운로드</a></td>
      </tr>)}</tbody>
    </table></div> : <p className="live-empty">업로드된 파일이 없습니다.</p>)}
    {data && data.totalPages > 1 && <nav aria-label="파일 목록 페이지">
      <button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
      <span>{page + 1} / {data.totalPages}</span>
      <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>다음</button>
    </nav>}
  </section>;
}
