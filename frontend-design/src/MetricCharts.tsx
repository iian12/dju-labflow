import { useState } from 'react'
import { axisLabels, formatAxisNumber, getMetricNames, getMetricSeries, getNumericDomain, isFiniteNumber, splitMetricSegments } from './metrics'
import type { MetricAxis, MetricPoint, MetricRecord } from './metrics'
const colors = ['#8b79c5', '#6d9d88', '#668dbd', '#bd8d69']
const chart = { width: 680, height: 320, left: 68, right: 654, top: 28, bottom: 260 }

function timeLabel(recordedAt?: string) {
  if (!recordedAt) return '—'
  return new Date(recordedAt).toLocaleTimeString('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function DynamicMetricChart({ name, records, axis, color }: { name: string; records: MetricRecord[]; axis: MetricAxis; color: string }) {
  const [hovered, setHovered] = useState<MetricPoint | null>(null)
  const series = getMetricSeries(records, name, axis)
  const points = series.filter((point): point is MetricPoint => point !== null)
  const segments = splitMetricSegments(series)
  const axisValues = [...new Set(records.map(record => record[axis]).filter(isFiniteNumber))].sort((a, b) => a - b)
  const [xMin, xMax] = getNumericDomain(axisValues)
  const [yMin, yMax] = getNumericDomain(points.map(point => point.value), .1)
  const x = (value: number) => chart.left + (value - xMin) / (xMax - xMin) * (chart.right - chart.left)
  const y = (value: number) => chart.bottom - (value - yMin) / (yMax - yMin) * (chart.bottom - chart.top)
  const xTicks = axisValues.length <= 8 ? axisValues : Array.from({ length: 5 }, (_, index) => axisValues[Math.round(index / 4 * (axisValues.length - 1))])
  const latest = points.at(-1)
  const active = hovered && points.find(point => point.recordIndex === hovered.recordIndex)
  const missing = records.length - points.length

  return <article className="panel dynamic-metric-chart" data-metric={name}>
    <div className="section-heading"><div><h3>{name}</h3><span className="metric-record-count">{points.length} recorded values</span></div><strong style={{ color }}>{latest ? String(latest.value) : '—'}</strong></div>
    <div className="metric-axis-caption"><span>X-axis: {axisLabels[axis]}</span><span>Y-axis: Metric Value</span></div>
    {points.length ? <div className="metric-plot" onPointerLeave={() => setHovered(null)}>
      <svg viewBox={`0 0 ${chart.width} ${chart.height}`} role="group" aria-label={`${name} line chart. X-axis: ${axisLabels[axis]}. Y-axis: Metric Value.`}
        onPointerMove={event => {
          const bounds = event.currentTarget.getBoundingClientRect()
          const mouseX = (event.clientX - bounds.left) / bounds.width * chart.width
          const mouseY = (event.clientY - bounds.top) / bounds.height * chart.height
          if (mouseX < chart.left || mouseX > chart.right || mouseY < chart.top || mouseY > chart.bottom) { setHovered(null); return }
          const nearest = points.reduce((best, point) => {
            const distance = (item: MetricPoint) => (x(item.x) - mouseX) ** 2 + (y(item.value) - mouseY) ** 2
            return distance(point) < distance(best) ? point : best
          })
          setHovered(nearest)
        }}>
        {[0, .25, .5, .75, 1].map(fraction => {
          const value = yMin + fraction * (yMax - yMin)
          return <g key={fraction}><line x1={chart.left} x2={chart.right} y1={y(value)} y2={y(value)} stroke="#ebe8f2" strokeDasharray="3 5" /><text x={chart.left - 12} y={y(value) + 4} textAnchor="end">{formatAxisNumber(value)}</text></g>
        })}
        {xTicks.map(value => <text key={value} x={x(value)} y={chart.bottom + 23} textAnchor="middle">{formatAxisNumber(value)}</text>)}
        <text x={(chart.left + chart.right) / 2} y="311" textAnchor="middle">{axisLabels[axis]}</text>
        {segments.map((segment, index) => <polyline className="metric-line" key={index} points={segment.map(point => `${x(point.x)},${y(point.value)}`).join(' ')} fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />)}
        {active && <line x1={x(active.x)} x2={x(active.x)} y1={chart.top} y2={chart.bottom} stroke={color} strokeOpacity=".4" strokeDasharray="4 4" />}
        {points.map(point => <circle key={point.recordIndex} className="metric-point" cx={x(point.x)} cy={y(point.value)} r={active?.recordIndex === point.recordIndex ? 5.5 : 4} fill={color} stroke="white" strokeWidth="1.5" tabIndex={0} role="img" aria-label={`${name}: ${String(point.value)}, ${axisLabels[axis]} ${point.x}`}
          onPointerEnter={() => setHovered(point)} onFocus={() => setHovered(point)} onBlur={() => setHovered(null)} onKeyDown={event => { if (event.key === 'Escape') setHovered(null) }} />)}
      </svg>
      {active && <div className="metric-tooltip" role="tooltip" style={{ left: `clamp(92px, ${x(active.x) / chart.width * 100}%, calc(100% - 92px))`, top: `${Math.max(3, Math.min(53, y(active.value) / chart.height * 100 - 28))}%` }}>
        <strong>{name}</strong><div><span>Metric Value</span><b>{String(active.value)}</b></div>
        {(['step', 'epoch', 'elapsedTime'] as MetricAxis[]).map(key => isFiniteNumber(active.record[key]) && <div key={key}><span>{axisLabels[key]}</span><b>{String(active.record[key])}</b></div>)}
        {active.record.recordedAt && <div><span>Recorded</span><b>{timeLabel(active.record.recordedAt)}</b></div>}
      </div>}
    </div> : <div className="metric-empty">이 메트릭에 기록된 {axisLabels[axis]} 값이 없습니다.</div>}
    <div className="metric-chart-footer"><span><i style={{ background: color }} />{name}</span>{missing > 0 && <span>{missing} records without a value or selected axis</span>}</div>
  </article>
}

export default function MetricsPanel({ records }: { records: MetricRecord[] }) {
  const [requestedAxis, setAxis] = useState<MetricAxis>('step')
  const axis = records.some(record => isFiniteNumber(record[requestedAxis])) ? requestedAxis
    : (Object.keys(axisLabels) as MetricAxis[]).find(key => records.some(record => isFiniteNumber(record[key]))) ?? requestedAxis
  const names = getMetricNames(records)
  return <section className="training-metrics" aria-labelledby="training-metrics-heading">
    <div className="metrics-panel-heading"><div><h2 id="training-metrics-heading">Training Metrics</h2><p>기록된 메트릭 {names.length}개 · 각 차트는 독립된 Y축 범위를 사용합니다.</p></div><label className="axis-picker">X-axis<select aria-label="Metrics X-axis" value={axis} onChange={event => setAxis(event.target.value as MetricAxis)}>{(Object.keys(axisLabels) as MetricAxis[]).map(key => <option key={key} value={key} disabled={!records.some(record => isFiniteNumber(record[key]))}>{axisLabels[key]}</option>)}</select></label></div>
    {names.length ? <div className="dynamic-metrics-grid">{names.map((name, index) => <DynamicMetricChart key={`${name}-${axis}`} name={name} records={records} axis={axis} color={colors[index % colors.length]} />)}</div> : <div className="panel empty-state">아직 기록된 메트릭이 없습니다.</div>}
    <p className="metric-explanation">점에 마우스를 올리거나 키보드로 선택하면 실제 기록값을 확인할 수 있습니다. 누락된 값은 0으로 채우거나 보간하지 않습니다.</p>
  </section>
}
