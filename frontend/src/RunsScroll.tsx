import type { ReactNode } from 'react';
import DragScroll from './DragScroll.tsx';

export default function RunsScroll({ children }: { children: ReactNode }) {
  return <DragScroll className="panel table-scroll runs-scroll" role="region" label="Run 실험 기록 목록">
    {children}
  </DragScroll>;
}
