import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import execution from 'k6/execution';
import { loadProfile } from './load-profile.mjs';

const context = JSON.parse(open(__ENV.LABFLOW_LOAD_CONTEXT));
const profile = loadProfile(__ENV.LABFLOW_LOAD_PROFILE || 'smoke');
const rotatingSession = profile.name !== 'smoke';
const refreshes = new Counter('session_refreshes');
let authenticatedAt = 0;
let sessionCookie = context.cookie;
let failureSamples = 0;
const metricBatches = new Counter('sdk_metric_batches');
const logRecords = new Counter('sdk_log_records');
const textRecords = new Counter('sdk_text_records');
const systemRecords = new Counter('sdk_system_records');
const artifacts = new Counter('sdk_artifacts');
const duplicateRetries = new Counter('sdk_duplicate_retries');
const bytes = new Uint8Array(64 * 1024);
for (let i = 0; i < bytes.length; i++) bytes[i] = i % 256;
const payload = bytes.buffer;
const payloadHash = crypto.sha256(payload, 'hex');

export const options = {
  noCookiesReset: true,
  tags: { target: 'labflow' },
  scenarios: {
    web_and_sdk: {
      executor: 'ramping-vus', startVUs: 1,
      stages: profile.stages,
      gracefulRampDown: '10s', gracefulStop: '10s',
    },
  },
  thresholds: {
    // Endurance runs retain transient outages for the entire observation window.
    // The same error-rate threshold still fails the final result.
    'http_req_failed{target:labflow}': [{ threshold: 'rate<0.01', abortOnFail: profile.name !== 'soak', delayAbortEval: '30s' }],
    checks: ['rate>0.99'],
    'http_req_duration{target:labflow}': ['p(95)<1000', 'p(99)<2000'],
    'http_reqs{target:labflow}': ['count>0'],
    'http_req_duration{kind:read}': ['p(95)<500'],
    'http_req_duration{kind:write}': ['p(95)<1000'],
    'http_req_duration{kind:file}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

function tags(endpoint, kind) {
  const elapsed = (Date.now() - context.loadStartedAtMs) / 1000;
  const phase = elapsed < profile.warmupSeconds ? 'warmup' :
    elapsed < profile.warmupSeconds + profile.holdSeconds ? 'steady' : 'ramp-down';
  return { name: endpoint, endpoint, kind, phase };
}
function ensureSession() {
  if (!rotatingSession) return true;
  if (authenticatedAt && Date.now() - authenticatedAt < profile.refreshSeconds * 1000) return true;
  // A single coordinator rotates the shared test session; VUs fetch its current
  // cookie over an authenticated loopback channel. No production limiter bypass.
  const response = http.get(context.sessionUrl, {
    headers: { 'X-LabFlow-Test-Token': context.sessionToken },
    tags: { name: 'local-session-refresh', target: 'session-relay' }, timeout: '5s',
  });
  if (response.status !== 200) execution.test.abort('Private session relay unavailable');
  sessionCookie = response.json('cookie');
  if (!sessionCookie) execution.test.abort('Private session relay returned no cookie');
  if (authenticatedAt) refreshes.add(1, { client: String(__VU) });
  authenticatedAt = Date.now();
  return true;
}
function webHeaders() { return { Cookie: sessionCookie }; }
function observeFailure(response, endpoint) {
  if (response.status !== 0 && response.status < 400 || failureSamples >= 3) return;
  failureSamples++;
  const body = typeof response.body === 'string' ? response.body.replace(/<[^>]*>/g, ' ') : '';
  const match = /(?:error\s*code|error)\s*:?\s*(1\d{3})/i.exec(body);
  const headers = response.headers || {};
  console.warn(JSON.stringify({ type: 'api-failure', time: new Date().toISOString(), endpoint,
    status: response.status, milliseconds: response.timings.duration,
    transportErrorCode: response.error_code || null,
    cloudflareRay: headers['Cf-Ray'] || headers['CF-RAY'] || null,
    cloudflareErrorCode: match ? match[1] : null }));
}

function extractId(response, key) {
  const match = response.body && new RegExp('"' + key + '"\\s*:\\s*"?([0-9]+)"?').exec(response.body);
  return match ? match[1] : null;
}
function sdk(operation, path, body, expected = 204, method = 'POST') {
  const requestId = `${context.cycleId}-${__VU}-${__ITER}-${operation}`;
  const response = http.request(method, context.base + '/api/v1/sdk' + path,
    body === undefined ? null : JSON.stringify(body), {
      headers: { Authorization: 'Bearer ' + context.apiKey, 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
      tags: tags(operation, 'write'), timeout: '20s',
    });
  check(response, { [operation + ' status']: value => value.status === expected });
  observeFailure(response, operation);
  return response;
}
function web(name, path) {
  const response = http.get(context.base + path, {
    headers: webHeaders(),
    tags: tags(name, 'read'), timeout: '20s',
  });
  check(response, { [name + ' status']: value => value.status === 200 });
  observeFailure(response, name);
  return response;
}

export default function () {
  if (!ensureSession()) { sleep(1); return; }
  const runId = context.runIds[(__VU - 1) % context.runIds.length];
  const run = '/runs/' + runId;
  const step = __ITER + 1;
  const metrics = { metrics: { loss: 1 / (step + 1), accuracy: 0.9 }, step, elapsedTime: step };
  if (sdk('metrics', run + '/metrics', metrics).status === 204) metricBatches.add(1);
  if (__ITER % 10 === 0) {
    if (sdk('metrics', run + '/metrics', metrics).status === 204) duplicateRetries.add(1);
  }
  if (sdk('logs', run + '/logs', { message: `Load cycle ${context.cycleId} VU ${__VU} step ${step}`, level: 'INFO', source: 'USER' }).status === 204) logRecords.add(1);
  if (__ITER % 5 === 0) sdk('heartbeat', run + '/heartbeat');
  if (__ITER % 10 === 0) {
    if (sdk('texts', run + '/texts', { key: 'prediction', text: 'API load verification', step }).status === 204) textRecords.add(1);
    if (sdk('system-metrics', run + '/system-metrics', { system: { cpuUtilization: 25, ramUsed: 1024, ramTotal: 2048, gpus: [] }, elapsedTime: step }).status === 204) systemRecords.add(1);
  }
  const project = '/api/v1/projects/' + context.projectId;
  web('projects-list', '/api/v1/projects?page=0&size=20');
  web('runs-list', project + '/runs?page=0&size=20');
  web('run-detail', project + run);
  if (__ITER % 3 === 0) {
    web('metric-series', project + run + '/metric-series?metricKey=loss');
    web('record-cursor', project + run + '/records/metrics?limit=200&latest=true');
    web('run-summaries', project + '/runs/summaries?runIds=' + context.runIds.join(','));
  }
  if (__ITER % 10 === 0) {
    web('run-comparison', project + '/runs/compare?runIds=' + context.runIds.slice(0, 2).join(',') + '&metricKey=loss');
    web('api-keys-list', project + '/api-keys?page=0&size=20');
  }
  if (__ITER === 0) {
    const response = http.post(context.base + '/api/v1/sdk' + run + '/artifacts?name=k6-' + __VU + '.bin', payload, {
      headers: { Authorization: 'Bearer ' + context.apiKey, 'Content-Type': 'application/octet-stream', 'Idempotency-Key': `${context.cycleId}-${__VU}-artifact` },
      tags: tags('artifact-upload', 'file'), timeout: '30s',
    });
    check(response, { 'artifact upload status': value => value.status === 201 });
    observeFailure(response, 'artifact-upload');
    const artifactId = extractId(response, 'artifactId');
    if (response.status === 201 && artifactId) {
      artifacts.add(1);
      const downloaded = http.get(context.base + project + run + '/artifacts/' + artifactId, {
        headers: webHeaders(), responseType: 'binary', timeout: '30s',
        tags: tags('artifact-download', 'file'),
      });
      check(downloaded, { 'artifact bytes match': value => value.status === 200 && crypto.sha256(value.body, 'hex') === payloadHash });
      observeFailure(downloaded, 'artifact-download');
    }
  }
  sleep(1);
}

export function handleSummary(data) {
  const metrics = { ...data.metrics };
  for (const name of ['http_req_duration', 'http_req_failed', 'http_reqs']) {
    metrics[name] = data.metrics[name + '{target:labflow}'];
  }
  const summary = { metrics, state: data.state, root_group: data.root_group };
  return {
    [__ENV.LABFLOW_K6_SUMMARY]: JSON.stringify(summary, null, 2),
    stdout: 'k6 completed; request metrics and threshold results saved to the cycle summary.\n',
  };
}
