import { useRef } from 'react';
import type { AriaRole, PointerEvent, ReactNode } from 'react';

interface DragScrollProps {
  children: ReactNode;
  as?: 'div' | 'nav';
  className?: string;
  label?: string;
  role?: AriaRole;
}

interface Gesture {
  pointerId: number;
  x: number;
  y: number;
  top: number;
  left: number;
}

export default function DragScroll({ children, as: Element = 'div', className, label, role }: DragScrollProps) {
  const gesture = useRef<Gesture | null>(null);
  const dragged = useRef(false);

  function start(event: PointerEvent<HTMLElement>) {
    if (!event.isPrimary || event.button !== 0) return;
    dragged.current = false;
    if (event.pointerType !== 'mouse') return;
    if (event.target instanceof globalThis.Element && event.target.closest('input,button,select,textarea')) return;
    gesture.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      top: event.currentTarget.scrollTop,
      left: event.currentTarget.scrollLeft,
    };
  }

  function move(event: PointerEvent<HTMLElement>) {
    const origin = gesture.current;
    if (!origin || event.pointerId !== origin.pointerId) return;
    const dx = event.clientX - origin.x;
    const dy = event.clientY - origin.y;
    if (!dragged.current && Math.hypot(dx, dy) < 6) return;
    dragged.current = true;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    event.currentTarget.classList.add('is-dragging');
    event.currentTarget.scrollTop = origin.top - dy;
    event.currentTarget.scrollLeft = origin.left - dx;
  }

  function finish(event: PointerEvent<HTMLElement>) {
    gesture.current = null;
    event.currentTarget.classList.remove('is-dragging');
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  return <Element
    className={className}
    role={role}
    aria-label={label}
    onDragStart={event=>event.preventDefault()}
    tabIndex={0}
    onPointerDown={start}
    onPointerMove={move}
    onPointerUp={finish}
    onPointerCancel={finish}
    onLostPointerCapture={finish}
    onPointerLeave={event => {
      if (!event.currentTarget.hasPointerCapture(event.pointerId)) finish(event);
    }}
    onClickCapture={event => {
      if (dragged.current && event.detail > 0) {
        event.preventDefault();
        event.stopPropagation();
        dragged.current = false;
      }
    }}
  >{children}</Element>;
}
