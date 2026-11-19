export type MetricAxis = 'step' | 'epoch' | 'elapsedTime'

export type MetricRecord = {
  step?: number
  epoch?: number
  elapsedTime?: number
  recordedAt?: string
  metrics: Record<string, number | null | undefined>
}

export type MetricPoint = { x: number; value: number; record: MetricRecord; recordIndex: number }

export const axisLabels: Record<MetricAxis, string> = {
  step: 'Step', epoch: 'Epoch', elapsedTime: 'Elapsed Time (s)',
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

export function getMetricNames(records: MetricRecord[]): string[] {
  return [...new Set(records.flatMap(record => Object.keys(record.metrics).filter(key => isFiniteNumber(record.metrics[key]))))]
}

export function getMetricSeries(records: MetricRecord[], name: string, axis: MetricAxis): (MetricPoint | null)[] {
  return records.map((record, recordIndex) => ({ record, recordIndex }))
    .filter(({ record }) => isFiniteNumber(record[axis]))
    .sort((a, b) => a.record[axis]! - b.record[axis]!)
    .map(({ record, recordIndex }) => {
      const value = record.metrics[name]
      if (!Object.hasOwn(record.metrics, name) || !isFiniteNumber(value)) return null
      return { x: record[axis]!, value, record, recordIndex }
    })
}

// A missing value breaks the line. No zero filling or interpolated tooltip values.
export function splitMetricSegments(series: (MetricPoint | null)[]): MetricPoint[][] {
  const segments: MetricPoint[][] = []
  let current: MetricPoint[] = []
  for (const point of series) {
    if (point) current.push(point)
    else if (current.length) { segments.push(current); current = [] }
  }
  if (current.length) segments.push(current)
  return segments
}

export function getNumericDomain(values: number[], padding = 0): [number, number] {
  if (!values.length) return [0, 1]
  const min = values.reduce((a, b) => Math.min(a, b))
  const max = values.reduce((a, b) => Math.max(a, b))
  const margin = min === max ? Math.abs(min) * .05 || 1 : (max - min) * padding
  return [min - margin, max + margin]
}

export function formatAxisNumber(value: number): string {
  if (value === 0) return '0'
  if (Math.abs(value) < .001 || Math.abs(value) >= 1_000_000) return value.toExponential(2)
  return Number(value.toPrecision(4)).toString()
}
