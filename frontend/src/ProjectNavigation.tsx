import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import DragScroll from './DragScroll.tsx';

type NavigationProject = { id: string; name: string; count?: number };
export default function ProjectNavigation({ projects, project, onCreate }: { projects: NavigationProject[]; project?: NavigationProject; onCreate: () => void }) {
  return <>
    <a className={'nav ' + (!project ? 'active' : '')} href="#/projects">
      <span>모든 프로젝트</span>
    </a>
    <div className="project-nav-heading">
      <span>프로젝트</span>
      <button type="button" aria-label="새 프로젝트" onClick={onCreate}>+</button>
    </div>
    <DragScroll as="nav" className="project-nav" label="프로젝트 바로가기">
      {projects.map(item => <a
        key={item.id}
        className={'project-nav-item ' + (item.id === project?.id ? 'active' : '')}
        href={'#/projects/' + item.id + '/overview'}
        aria-label={item.name}
        aria-current={item.id === project?.id ? 'page' : undefined}
        draggable={false}
        title={item.name}
      >
        <span className="project-initial" aria-hidden="true">
          {item.name.slice(0, 1).toUpperCase()}
        </span>
        <ProjectName name={item.name}/>
        {item.count != null && <span className="project-nav-count">{item.count}</span>}
      </a>)}
    </DragScroll>
  </>;
}

function ProjectName({ name }: { name: string }) {
  const containerRef = useRef<HTMLSpanElement>(null);
  const textRef = useRef<HTMLSpanElement>(null);
  const [textWidth, setTextWidth] = useState(0);
  const [overflowing, setOverflowing] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    const text = textRef.current;
    if (!container || !text) return;
    const measure = () => {
      const width = text.getBoundingClientRect().width;
      setTextWidth(width);
      setOverflowing(width > container.clientWidth + 1);
    };
    const observer = new ResizeObserver(measure);
    observer.observe(container);
    observer.observe(text);
    measure();
    return () => observer.disconnect();
  }, [name]);

  return <span ref={containerRef} className={'project-nav-name' + (overflowing ? ' is-overflowing' : '')}>
    <span className="project-name-track" style={{
      '--name-distance': (textWidth + 24) + 'px',
      '--name-duration': Math.max(8, (textWidth + 24) / 28) + 's',
    } as CSSProperties}>
      <span ref={textRef}>{name}</span>
      {overflowing && <span aria-hidden="true">{name}</span>}
    </span>
  </span>;
}
