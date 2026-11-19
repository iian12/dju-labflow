import type { Metric, Profile, Project, Run, RunStatus } from './types.ts';
import {randomName} from './demoProjects.ts';
const profiles: Record<string, Profile> = {
  vision: {
    model: 'ResNet50',
    dataset: 'CIFAR-10',
    metrics: { accuracy: 'val_accuracy', loss: 'val_loss' },
    names: ['resnet50-cosine', 'resnet50-baseline', 'resnet50-augmix', 'resnet50-lr-sweep'],
    start: 0.43,
    score: 0.942,
    loss: 0.185,
    environment: { OS: 'Ubuntu 24.04', Python: '3.11.9', PyTorch: '2.5.1', GPU: 'RTX 4090 · 24 GB', CUDA: '12.4', RAM: '64 GB' },
    failure: { title: '체크포인트 저장 실패', reason: '디스크 공간 부족으로 모델 저장을 중단했습니다', log: 'Checkpoint save failed: no space left on device' },
  },
  language: {
    model: 'BGE-small-en-v1.5',
    dataset: 'Research papers · 12k passages',
    metrics: { accuracy: 'recall_at_10', loss: 'contrastive_loss' },
    names: ['bge-hard-negatives', 'bge-baseline', 'bge-warmup', 'bge-batch-sweep'],
    start: 0.24,
    score: 0.876,
    loss: 0.264,
    environment: { OS: 'Ubuntu 22.04', Python: '3.11.8', Transformers: '4.46.3', GPU: 'A100 · 40 GB', CUDA: '12.1', RAM: '128 GB' },
    failure: { title: '임베딩 배치 메모리 초과', reason: '평가 배치가 GPU 메모리 한도를 초과했습니다', log: 'CUDA out of memory while encoding evaluation batch' },
  },
  audio: {
    model: 'AST-small',
    dataset: 'ESC-50',
    metrics: { accuracy: 'macro_f1', loss: 'cross_entropy' },
    names: ['ast-mel128', 'ast-baseline', 'ast-specaugment', 'ast-lr-sweep'],
    start: 0.3,
    score: 0.913,
    loss: 0.231,
    environment: { OS: 'Ubuntu 24.04', Python: '3.11.9', Torchaudio: '2.5.1', GPU: 'RTX 3090 · 24 GB', CUDA: '12.4', RAM: '64 GB' },
    failure: { title: '음성 샘플 읽기 실패', reason: '손상된 WAV 파일이 있어 데이터 로더가 중단되었습니다', log: 'Audio decode failed: invalid WAV header in fold3/sample-014.wav' },
  },
  generic: {
    model: 'Baseline classifier',
    dataset: 'Demo classification dataset',
    metrics: { accuracy: 'val_accuracy', loss: 'val_loss' },
    names: ['baseline-small', 'baseline-medium', 'baseline-regularized', 'baseline-lr-sweep'],
    start: 0.35,
    score: 0.891,
    loss: 0.294,
    environment: { OS: 'Ubuntu 24.04', Python: '3.11.9', PyTorch: '2.5.1', CPU: '16 cores', RAM: '32 GB' },
    failure: { title: '학습 데이터 검증 실패', reason: '입력 데이터의 feature 수가 모델 설정과 다릅니다', log: 'Input validation failed: expected 32 features, received 31' },
  },
};

export function profileFor(project?: Project): Profile {
  if (project?.id === 'vision') return profiles.vision;
  if (project?.id === 'language') return profiles.language;
  if (project?.id === 'empty') return profiles.audio;
  return profiles.generic;
}

const states: RunStatus[] = ['COMPLETED', 'COMPLETED', 'RUNNING', 'FAILED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'RUNNING', 'COMPLETED', 'FAILED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED'];
export function getRuns(project?: Project): Run[] {
  if (!project?.count) return [];
  const profile = profileFor(project);
  return Array.from({ length: project.count }, (_, index) => {
    const status = states[index % states.length];
    const step = status === 'RUNNING' ? 42 : status === 'FAILED' ? 28 : 100;
    const lr = ['0.001', '0.01', '0.0005'][index % 3];
    const batch = index % 2 ? 32 : 64;
    const optimizer = index % 3 ? 'AdamW' : 'SGD';
    let parameters: Run['parameters'] = { learning_rate: lr, batch_size: batch, optimizer, epochs: 100 };
    if (project.id === 'language') parameters = { learning_rate: ['0.00002', '0.00005', '0.00001'][index % 3], batch_size: batch, max_length: index % 2 ? 256 : 512, negatives_per_query: index % 2 ? 4 : 8 };
    if (project.id === 'empty') parameters = { learning_rate: lr, batch_size: batch, mel_bins: index % 2 ? 64 : 128, specaugment: index % 2 ? 'off' : 'on' };
    const run: Run = {
      id: project.id + '-run-' + (1042 - index),
      name: project.id.startsWith('stress-demo-')
        ? randomName(Number(project.id.slice(12)) * 997 + index * 7919, 1 + (index % 32))
        : profile.names[index % 4] + '-' + String(index + 1).padStart(2, '0'),
      status, step,
      accuracy: +(profile.score - index * 0.003 + (index % 3) * 0.002).toFixed(3),
      loss: +(profile.loss + index * 0.014).toFixed(3),
      duration: (project.id === 'language' ? 38 : 72) + index * 3,
      lr: parameters.learning_rate, batch, optimizer, parameters,
      date: '11월 ' + (10 - Math.floor(index / 5)) + '일 · ' + (14 - index % 5) + ':32',
      logs: [], profile, failure: status === 'FAILED' ? profile.failure : null,
    };
    run.logs = Array.from({ length: 18 }, (_, line) => ({
      level: run.failure && line === 17 ? 'ERROR' : 'INFO',
      text: run.failure && line === 17
        ? run.failure.log
        : profile.model + ' · Step ' + Math.round((line + 1) / 18 * step) + ' · ' + profile.metrics.loss + ' ' + metricValue(run, 'loss', Math.round((line + 1) / 18 * step)).toFixed(3),
    }));
    return run;
  });
}

// Deterministic sample curves end at exactly the value shown in the table.
export function metricValue(run: Run, metric: Metric, step: number): number {
  const t = Math.max(0, Math.min(step, run.step));
  const decay = metric === 'accuracy' ? 16 : 18;
  const fraction = (Math.exp(-t / decay) - Math.exp(-run.step / decay)) / (1 - Math.exp(-run.step / decay));
  return metric === 'accuracy'
    ? run.accuracy - (run.accuracy - run.profile.start) * fraction
    : run.loss + 1.1 * fraction;
}

