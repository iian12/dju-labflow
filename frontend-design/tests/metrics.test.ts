import { test } from 'node:test'
import assert from 'node:assert/strict'
import { getMetricNames, getMetricSeries, getNumericDomain, splitMetricSegments } from '../src/metrics.ts'
import type { MetricRecord } from '../src/metrics.ts'

const input: MetricRecord[] = [
  { step: 100, metrics: { train_loss: .52, validation_accuracy: .88 } },
  { step: 200, metrics: { train_loss: .38, validation_accuracy: .91, custom_score: .75 } },
]

test('discovers custom keys first recorded in later events without renaming', () => {
  assert.deepEqual(getMetricNames(input), ['train_loss', 'validation_accuracy', 'custom_score'])
  const series = getMetricSeries(input, 'custom_score', 'step')
  assert.equal(series[0], null)
  assert.equal(series[1]?.value, .75)
  assert.equal(series[1]?.x, 200)
})

test('missing, null and non-finite values are gaps; recorded zero is retained', () => {
  const records: MetricRecord[] = [
    { step: 1, metrics: { arbitrary: 0 } },
    { step: 2, metrics: {} },
    { step: 3, metrics: { arbitrary: null } },
    { step: 4, metrics: { arbitrary: NaN } },
    { step: 5, metrics: { arbitrary: -12.123456789 } },
  ]
  const series = getMetricSeries(records, 'arbitrary', 'step')
  assert.deepEqual(series.map(point => point?.value ?? null), [0, null, null, null, -12.123456789])
  assert.deepEqual(splitMetricSegments(series).map(segment => segment.length), [1, 1])
})

test('uses numeric coordinates and preserves duplicate epoch records', () => {
  const records: MetricRecord[] = [
    { step: 900, epoch: 2, elapsedTime: 7.5, metrics: { value: 3 } },
    { step: 100, epoch: 1, elapsedTime: 0, metrics: { value: 1 } },
    { step: 200, epoch: 1, metrics: { value: 2 } },
  ]
  assert.deepEqual(getMetricSeries(records, 'value', 'step').map(point => point?.x), [100, 200, 900])
  assert.deepEqual(getMetricSeries(records, 'value', 'epoch').map(point => point?.value), [1, 2, 3])
  assert.deepEqual(getMetricSeries(records, 'value', 'elapsedTime').map(point => point?.x), [0, 7.5])
})

test('does not infer epoch or elapsed time from step or array position', () => {
  assert.deepEqual(getMetricSeries(input, 'train_loss', 'epoch'), [])
  assert.deepEqual(getMetricSeries(input, 'train_loss', 'elapsedTime'), [])
})

test('line segments do not bridge missing measurements', () => {
  const records: MetricRecord[] = [1, 2, 3, 4, 5].map(step => ({ step, metrics: step === 3 ? {} : { metric: step } }))
  assert.deepEqual(splitMetricSegments(getMetricSeries(records, 'metric', 'step')).map(segment => segment.map(point => point.x)), [[1, 2], [4, 5]])
})

test('different metric ranges and constant or singleton values have finite independent domains', () => {
  const probability = getNumericDomain([.88, .91], .1)
  const latency = getNumericDomain([1000, 2500], .1)
  assert.ok(probability[0] > .8 && probability[1] < 1)
  assert.ok(latency[0] > 500 && latency[1] > 2500)
  for (const values of [[0], [.75], [-30, -20], [.000001, .000002]]) {
    const [min, max] = getNumericDomain(values, .1)
    assert.ok(Number.isFinite(min) && Number.isFinite(max) && max > min)
  }
})

test('user-defined keys that match object properties are handled as recorded keys only', () => {
  const records: MetricRecord[] = JSON.parse('[{"step":100,"metrics":{"__proto__":0.12,"constructor":0.34}},{"step":200,"metrics":{}}]')
  assert.deepEqual(getMetricNames(records), ['__proto__', 'constructor'])
  assert.deepEqual(getMetricSeries(records, 'constructor', 'step').map(point => point?.value ?? null), [.34, null])
})
