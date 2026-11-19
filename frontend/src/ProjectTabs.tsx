import type { CSSProperties } from 'react';

const tabs = [
  ['overview', '개요'],
  ['runs', '실험 기록'],
  ['compare', '비교'],
  ['settings', '설정'],
];

export default function ProjectTabs({ projectId, view, runCount }: { projectId: string; view: string; runCount: number }) {
  const activeIndex = tabs.findIndex(([id]) => id === view);

  return <nav className="project-tabs" aria-label="프로젝트 화면">
    {activeIndex >= 0 && <span className="project-tab-indicator" aria-hidden="true"
      style={{ '--tab-index': activeIndex } as CSSProperties}/>}
    {tabs.map(([id, label]) => <a
      key={id}
      data-view={id}
      href={'#/projects/' + projectId + '/' + id}
      aria-current={view === id ? 'page' : undefined}
      className={view === id ? 'active' : ''}
    >{label}{id === 'runs' && <span>{runCount}</span>}</a>)}
  </nav>;
}
