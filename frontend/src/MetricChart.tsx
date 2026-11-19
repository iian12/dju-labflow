import { useState } from 'react';
import type { Point } from './liveTypes.ts';
import { number } from './LiveShared.tsx';
export type Curve = { id: string; name: string; points: Point[]; sampled?: boolean };
const colors = ['var(--curve-1)', 'var(--curve-2)', 'var(--curve-3)', 'var(--curve-4)'];
export default function MetricChart({ curves, label }: { curves: Curve[]; label: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const points = curves.flatMap(curve => curve.points).filter(point => point.step != null && Number.isFinite(point.value));
  const steps = [...new Set(points.map(point => String(point.step)))].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0);
  if (!points.length) return <p className="live-empty">Step이 있는 지표 기록이 없습니다.</p>;
  const min = BigInt(steps[0]), span = BigInt(steps.at(-1)!) - min;
  const x = (step: string | number | null) => 60 + (span === 0n ? .5 : Number(BigInt(step!) - min) / Number(span)) * 810;
  const ticks = [...new Set([0, 1, 2, 3, 4].map(t => String(min + span * BigInt(t) / 4n)))];
  const scale = Math.max(...points.map(point => Math.abs(point.value)), 1);
  const lo = Math.min(...points.map(point => point.value / scale)), hi = Math.max(...points.map(point => point.value / scale));
  const range = hi - lo || .1;
  const y = (value: number) => 230 - (value / scale - lo) / range * 190;
  const index = hover == null ? null : Math.min(hover, steps.length - 1);
  return <div className="chart-wrap"><svg viewBox="0 0 900 280" role="img" aria-label={`${label} 학습 곡선`}>
    {[0, 1, 2, 3, 4].map(t => <g key={t}><line x1="60" x2="870" y1={40 + 47.5 * t} y2={40 + 47.5 * t} stroke="var(--chart-grid)" strokeDasharray="3 4"/><text x="50" y={44 + 47.5 * t} textAnchor="end">{number((lo + range * (1 - t / 4)) * scale)}</text></g>)}
    {ticks.map(step => <text key={step} x={x(step)} y="262" textAnchor="middle">{step}</text>)}
    {curves.map((curve, i) => { const ordered = curve.points.filter(point => point.step != null); return <g key={curve.id}>{ordered.length > 1 && <polyline points={ordered.map(point => `${x(point.step)},${y(point.value)}`).join(' ')} fill="none" stroke={colors[i % 4]} strokeWidth="2.5"/>}{ordered.length === 1 && <circle cx={x(ordered[0].step)} cy={y(ordered[0].value)} r="4" fill={colors[i % 4]}/>}</g>; })}
    <rect x="60" y="25" width="810" height="215" fill="transparent" tabIndex={0} role="slider" aria-label="차트 기록 위치" aria-valuemin={0} aria-valuemax={steps.length - 1} aria-valuenow={index ?? 0} aria-valuetext={`Step ${steps[index ?? 0]}`} onFocus={() => setHover(0)} onBlur={() => setHover(null)} onMouseLeave={() => setHover(null)} onKeyDown={event => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); setHover(previous => event.key === 'Home' ? 0 : event.key === 'End' ? steps.length - 1 : Math.max(0, Math.min(steps.length - 1, (previous ?? 0) + (event.key === 'ArrowRight' ? 1 : -1)))); } }} onMouseMove={event => { const rect = event.currentTarget.getBoundingClientRect(); const ratio = (event.clientX - rect.left) / rect.width; let closest = 0; for (let i = 1; i < steps.length; i++) if (Math.abs((x(steps[i]) - 60) / 810 - ratio) < Math.abs((x(steps[closest]) - 60) / 810 - ratio)) closest = i; setHover(closest); }}/>
    {index != null && <line x1={x(steps[index])} x2={x(steps[index])} y1="25" y2="240" stroke="var(--muted)" strokeDasharray="4 4"/>}
  </svg><div className="chart-foot"><span>Step</span><span>{index == null ? '기록 위에서 실제 측정값을 확인하세요' : `Step ${steps[index]} · ${curves.map(curve => `${curve.name}: ${number(curve.points.find(point => String(point.step) === steps[index])?.value)}`).join(' / ')}`}</span></div>{curves.some(curve => curve.sampled) && <p className="live-chart-note">전체 구간에서 일부 실제 기록을 선택해 표시합니다. 비교 수치는 전체 기록에서 계산합니다.</p>}</div>;
}
