import type { MetricRecord } from './metrics'

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED'
export type Project = { id: string; name: string; description: string; code: string; color: string }
export type Run = { id: string; projectId: string; name: string; status: RunStatus; duration: string; started: string }

export const projects: Project[] = [
  { id: 'image-classification', name: 'Image Classification', description: 'Image classification experiments and model evaluations', code: 'IC', color: 'violet' },
  { id: 'sentiment-analysis', name: 'Sentiment Analysis', description: 'Korean text sentiment analysis and language model fine-tuning', code: 'SA', color: 'blue' },
  { id: 'time-series-forecast', name: 'Time Series Forecast', description: 'Time series forecasting and model benchmarking', code: 'TS', color: 'orange' },
]

export const statusLabels: Record<RunStatus, string> = {
  RUNNING: 'Running', COMPLETED: 'Completed', FAILED: 'Failed',
}

// Each run belongs to exactly one project; summaries are derived from these rows.
export const runs: Run[] = [
  { id: 'RUN-024', projectId: 'image-classification', name: 'resnet50-lr-0.001', status: 'RUNNING', duration: '32m 18s', started: 'Today 14:32' },
  { id: 'RUN-023', projectId: 'image-classification', name: 'efficientnet-b0-aug', status: 'COMPLETED', duration: '1h 12m', started: 'Today 13:52' },
  { id: 'RUN-022', projectId: 'image-classification', name: 'resnet50-batch-64', status: 'FAILED', duration: '8m 42s', started: 'Yesterday' },
  ...Array.from({ length: 21 }, (_, index): Run => ({
    id: `RUN-${String(21 - index).padStart(3, '0')}`,
    projectId: 'image-classification',
    name: ['resnet50-baseline', 'vit-small-baseline', 'resnet18-augmentation', 'efficientnet-b1'][index % 4] + `-${21 - index}`,
    status: index < 2 ? 'RUNNING' : index < 4 ? 'FAILED' : 'COMPLETED',
    duration: index < 2 ? '46m 12s' : '1h 24m',
    started: index < 5 ? 'Yesterday' : `Oct ${30 - Math.floor(index / 3)}, 09:15`,
  })),
  ...Array.from({ length: 8 }, (_, index): Run => ({
    id: `NLP-${String(8 - index).padStart(3, '0')}`,
    projectId: 'sentiment-analysis',
    name: ['bert-base-finetune', 'bert-lr-scheduler', 'roberta-baseline'][index % 3] + `-${8 - index}`,
    status: index === 0 ? 'RUNNING' : index === 3 ? 'FAILED' : 'COMPLETED',
    duration: '52m 06s',
    started: index === 0 ? 'Today 13:10' : index < 3 ? 'Yesterday' : 'Oct 29, 10:30',
  })),
]

export function getProjectRuns(projectId: string) {
  return runs.filter(run => run.projectId === projectId)
}

// Every event uses the user's { step, metrics: { [userDefinedKey]: value } } shape.
// Epoch, elapsedTime (seconds in this mock), and recordedAt are optional context.
export const sampleMetricRecords: MetricRecord[] = [
  { step: 100, epoch: 1, elapsedTime: 0, recordedAt: '2026-11-01T14:32:01+09:00', metrics: { train_loss: 0.52, validation_accuracy: 0.88 } },
  { step: 200, epoch: 1, elapsedTime: 200, recordedAt: '2026-11-01T14:36:12+09:00', metrics: { train_loss: 0.38, validation_accuracy: 0.91, custom_score: 0.75 } },
  { step: 300, epoch: 2, elapsedTime: 370, recordedAt: '2026-11-01T14:38:12+09:00', metrics: { train_loss: 0.31, validation_accuracy: 0.925 } },
  { step: 400, epoch: 2, elapsedTime: 610, recordedAt: '2026-11-01T14:42:18+09:00', metrics: { validation_accuracy: 0.934, custom_score: 0.79 } },
  { step: 500, epoch: 3, elapsedTime: 750, recordedAt: '2026-11-01T14:44:30+09:00', metrics: { train_loss: 0.26, validation_accuracy: 0.941, custom_score: 0.82 } },
  { step: 600, epoch: 3, elapsedTime: 1090, recordedAt: '2026-11-01T14:50:11+09:00', metrics: { train_loss: 0.23, custom_score: 0.84 } },
  { step: 700, epoch: 4, elapsedTime: 1563, recordedAt: '2026-11-01T14:58:04+09:00', metrics: { train_loss: 0.2, validation_accuracy: 0.952 } },
  { step: 800, epoch: 4, elapsedTime: 1938, recordedAt: '2026-11-01T15:04:19+09:00', metrics: { train_loss: 0.18, validation_accuracy: 0.958, custom_score: 0.89 } },
]

export function getRunDetails(run: Run) {
  const records = run.status === 'FAILED' ? sampleMetricRecords.slice(0, 4) : sampleMetricRecords
  const error = run.status === 'FAILED' ? 'RuntimeError: training stopped before completion.' : null
  return {
    parameters: {
      model_name: run.projectId === 'sentiment-analysis' ? 'BERT' : run.name.includes('efficientnet') ? 'EfficientNet' : run.name.includes('vit') ? 'ViT' : 'ResNet50',
      learning_rate: '0.001',
      batch_size: run.name.includes('batch-64') ? '64' : '32',
      optimizer: 'Adam',
    },
    records,
    logs: [
      { time: '14:32:01', level: 'INFO', message: 'Training started' },
      { time: '14:38:12', level: 'INFO', message: 'Epoch 1 completed' },
      { time: '14:44:30', level: 'INFO', message: 'Epoch 2 completed' },
      { time: run.status === 'FAILED' ? '14:42:18' : '15:04:19', level: error ? 'ERROR' : 'INFO', message: error ?? (run.status === 'RUNNING' ? 'Training in progress...' : 'Training completed') },
    ].filter((_, index) => run.status !== 'FAILED' || index !== 2),
    error,
  }
}
