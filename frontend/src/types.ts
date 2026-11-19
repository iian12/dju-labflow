export interface Project {
  id: string;
  name: string;
  description: string;
  model?: string;
  count: number;
}

export type Metric = 'accuracy' | 'loss';
export type RunStatus = 'COMPLETED' | 'RUNNING' | 'FAILED';
export type ProjectView = 'overview' | 'runs' | 'compare' | 'settings';

export interface Failure {
  title: string;
  reason: string;
  log: string;
}

export interface Profile {
  model: string;
  dataset: string;
  metrics: Record<Metric, string>;
  names: string[];
  start: number;
  score: number;
  loss: number;
  environment: Record<string, string>;
  failure: Failure;
}

export interface Run {
  id: string;
  name: string;
  status: RunStatus;
  step: number;
  accuracy: number;
  loss: number;
  duration: number;
  lr: string;
  batch: number;
  optimizer: string;
  parameters: { learning_rate: string } & Record<string, string | number>;
  date: string;
  profile: Profile;
  failure: Failure | null;
  logs: { level: 'ERROR' | 'INFO'; text: string }[];
}

export interface ExplorationState {
  query: string;
  filter: RunStatus | 'ALL';
  sort: 'recent' | 'metric' | 'accuracy' | 'duration';
  selected: string[];
  metric: Metric;
  baseline: string;
}
