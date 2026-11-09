export type Group = 'auth' | 'projects' | 'keys' | 'runs'
export type Context = { projectId: string; runId: string; version: string }
export type Field = {
  name: string
  label: string
  type?: 'text' | 'email' | 'password' | 'textarea' | 'json' | 'integer' | 'number' | 'select'
  initial?: string
  required?: boolean
  options?: string[]
  help?: string
  binding?: keyof Context
}
export type Endpoint = {
  id: string
  group: Group
  title: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  path: string
  status: number
  auth: 'public' | 'cookie' | 'key'
  csrf?: boolean
  description: string
  fields?: Field[]
  query?: boolean
  next?: string
}

const email: Field = { name: 'email', label: '이메일', type: 'email', required: true }
const password: Field = { name: 'password', label: '비밀번호', type: 'password', required: true, help: '8~72자' }
const name: Field = { name: 'name', label: '이름', required: true }
const description: Field = { name: 'description', label: '설명', type: 'textarea', initial: '브라우저 통합 테스트 프로젝트' }
const visibility: Field = { name: 'visibility', label: '공개 범위', type: 'select', initial: 'PRIVATE', options: ['PRIVATE', 'PUBLIC'] }
const version: Field = { name: 'version', label: '조회한 version', type: 'integer', required: true, binding: 'version', help: '프로젝트 상세 조회로 최신 version을 가져오세요.' }
const step: Field = { name: 'step', label: 'Step', type: 'integer', initial: '0' }
const elapsed: Field = { name: 'elapsedTime', label: '경과 시간 (초)', type: 'number', initial: '1.5' }
const recordedAt: Field = { name: 'recordedAt', label: '기록 시각 (선택)', help: '비우면 서버 시각. 예: 2026-11-01T00:00:00Z' }

export const groups: { id: Group; title: string; label: string }[] = [
  { id: 'auth', title: '회원 · 인증', label: '01' },
  { id: 'projects', title: '프로젝트', label: '02' },
  { id: 'keys', title: 'API key', label: '03' },
  { id: 'runs', title: '실험 실행', label: '04' },
]

export const endpoints: Endpoint[] = [
  { id: 'csrf', group: 'auth', title: 'CSRF 초기화', method: 'GET', path: '/api/v1/auth/csrf', status: 200, auth: 'public', description: '변경 요청에 필요한 XSRF-TOKEN 쿠키를 받습니다. 아래의 CSRF 자동 적용으로도 준비할 수 있습니다.', next: 'signup' },
  { id: 'signup', group: 'auth', title: '회원가입', method: 'POST', path: '/api/v1/auth/sign-up', status: 201, auth: 'public', description: '미인증 계정을 만들고 인증 코드를 이메일로 발송합니다. 백엔드 메일 설정이 필요합니다.', fields: [name, email, password, { ...password, name: 'confirmPassword', label: '비밀번호 확인' }], next: 'verify' },
  { id: 'verify', group: 'auth', title: '이메일 인증', method: 'POST', path: '/api/v1/auth/email-verifications/verify', status: 204, auth: 'public', csrf: true, description: '메일로 받은 코드를 입력하세요. 코드는 10분간 유효합니다.', fields: [email, { name: 'code', label: '인증 코드', required: true }], next: 'login' },
  { id: 'resend', group: 'auth', title: '인증 코드 재발송', method: 'POST', path: '/api/v1/auth/email-verifications/resend', status: 202, auth: 'public', csrf: true, description: '60초 후 재요청할 수 있습니다. 202 응답은 실제 발송이나 계정 존재를 보장하지 않습니다.', fields: [email], next: 'verify' },
  { id: 'login', group: 'auth', title: '로그인', method: 'POST', path: '/api/v1/auth/login', status: 204, auth: 'public', description: '인증 완료된 계정으로 로그인합니다. HttpOnly access/refresh 쿠키는 브라우저가 관리합니다.', fields: [email, password], next: 'project-create' },
  { id: 'refresh', group: 'auth', title: '토큰 갱신', method: 'POST', path: '/api/v1/auth/refresh', status: 204, auth: 'cookie', csrf: true, description: 'refresh 쿠키와 CSRF로 토큰을 회전합니다. 401 응답에서 자동 갱신하지 않아 실제 결과를 확인할 수 있습니다.', next: 'project-list' },
  { id: 'logout', group: 'auth', title: '로그아웃', method: 'POST', path: '/api/v1/auth/logout', status: 204, auth: 'cookie', csrf: true, description: '현재 refresh 토큰을 폐기하고 인증 쿠키를 삭제합니다. 쿠키가 없어도 204를 반환할 수 있습니다.', next: 'project-list' },
  { id: 'project-create', group: 'projects', title: '프로젝트 생성', method: 'POST', path: '/api/v1/projects', status: 201, auth: 'cookie', csrf: true, description: '현재 사용자가 소유하는 개인 프로젝트를 생성합니다.', fields: [{ ...name, initial: '브라우저 테스트' }, description, visibility], next: 'project-get' },
  { id: 'project-list', group: 'projects', title: '내 프로젝트 목록', method: 'GET', path: '/api/v1/projects', status: 200, auth: 'cookie', query: true, description: '로그인한 사용자의 프로젝트 목록입니다. 응답의 선택 버튼으로 프로젝트를 전환하세요.', fields: [{ name: 'page', label: '페이지 (0부터)', type: 'integer', initial: '0', required: true }, { name: 'size', label: '페이지 크기 (1~100)', type: 'integer', initial: '20', required: true }], next: 'project-get' },
  { id: 'project-get', group: 'projects', title: '프로젝트 상세', method: 'GET', path: '/api/v1/projects/{projectId}', status: 200, auth: 'public', description: 'PUBLIC은 비로그인 조회가 가능합니다. PRIVATE은 소유자만 조회할 수 있습니다. 조회한 version과 수정 폼을 자동으로 채웁니다.', next: 'key-issue' },
  { id: 'project-update', group: 'projects', title: '프로젝트 수정', method: 'PUT', path: '/api/v1/projects/{projectId}', status: 204, auth: 'cookie', csrf: true, description: '소유자만 수정할 수 있습니다. 성공 후 상세를 다시 조회해 새 version을 받으세요.', fields: [{ ...name, initial: '수정한 테스트 프로젝트' }, description, visibility, version], next: 'project-get' },
  { id: 'project-delete', group: 'projects', title: '프로젝트 삭제', method: 'DELETE', path: '/api/v1/projects/{projectId}', status: 204, auth: 'cookie', csrf: true, query: true, description: '프로젝트와 소속 키·Run·기록을 영구 삭제합니다. 모든 기록 테스트를 마친 후 실행하세요.', fields: [version], next: 'project-list' },
  { id: 'key-issue', group: 'keys', title: '프로젝트 API key 발급', method: 'POST', path: '/api/v1/project-api-keys/issue/{projectId}', status: 200, auth: 'cookie', csrf: true, description: '발급된 키를 SDK 요청에 자동 연결합니다. 키 원문은 현재 화면 메모리에만 보관하며 요청 기록에는 마스킹합니다.', fields: [{ ...name, label: '키 이름', initial: 'browser-test' }], next: 'run-create' },
  { id: 'run-create', group: 'runs', title: 'Run 생성', method: 'POST', path: '/api/v1/sdk/runs', status: 201, auth: 'key', description: '프로젝트는 Bearer API key로 결정됩니다. 위 project ID를 바꿔도 키의 소속은 바뀌지 않습니다.', fields: [{ ...name, initial: 'browser-training' }, { name: 'metadata', label: '메타데이터 (JSON)', type: 'json', initial: '{\n  "framework": "browser",\n  "seed": "42"\n}', help: '문자열 key/value 객체' }], next: 'run-params' },
  { id: 'run-get', group: 'runs', title: 'Run 상태 조회', method: 'GET', path: '/api/v1/sdk/runs/{runId}', status: 200, auth: 'key', description: '상태·메타데이터·시작/종료 시각·실패 사유를 확인합니다. 기록별 조회 API는 아직 없습니다.', next: 'run-create' },
  { id: 'run-params', group: 'runs', title: '파라미터 기록', method: 'PUT', path: '/api/v1/sdk/runs/{runId}/parameters', status: 204, auth: 'key', description: '서버는 문자열 값을 받습니다. 같은 키/값은 멱등적이고 값 변경은 409입니다. 최대 100개입니다.', fields: [{ name: 'parameters', label: '파라미터 (JSON)', type: 'json', required: true, initial: '{\n  "optimizer": "adam",\n  "batch_size": "32"\n}' }], next: 'run-metrics' },
  { id: 'run-metrics', group: 'runs', title: '메트릭 기록', method: 'POST', path: '/api/v1/sdk/runs/{runId}/metrics', status: 204, auth: 'key', description: 'JSON 숫자만 허용합니다. 문자열·bool·null은 서버에서 400으로 거부합니다.', fields: [{ name: 'metrics', label: '메트릭 (JSON)', type: 'json', required: true, initial: '{\n  "loss": 0.25,\n  "accuracy": 0.9\n}' }, step, { name: 'epoch', label: 'Epoch', type: 'integer', initial: '0' }, elapsed, recordedAt], next: 'run-logs' },
  { id: 'run-logs', group: 'runs', title: '로그 기록', method: 'POST', path: '/api/v1/sdk/runs/{runId}/logs', status: 204, auth: 'key', description: '실행 메시지와 수집 출처를 기록합니다. 빈 메시지도 보존합니다.', fields: [{ name: 'message', label: '로그 메시지', type: 'textarea', initial: '학습을 시작했습니다.' }, { name: 'level', label: '레벨', type: 'select', initial: 'INFO', options: ['DEBUG', 'INFO', 'WARN', 'ERROR'] }, { name: 'source', label: '출처', type: 'select', initial: 'USER', options: ['USER', 'STDOUT', 'STDERR', 'SDK'] }, recordedAt], next: 'run-texts' },
  { id: 'run-texts', group: 'runs', title: '텍스트 기록', method: 'POST', path: '/api/v1/sdk/runs/{runId}/texts', status: 204, auth: 'key', description: '모델 입력·출력·정답 등 실험 텍스트를 로그와 분리해 기록합니다.', fields: [{ name: 'key', label: '텍스트 이름', initial: 'prediction', required: true }, { name: 'text', label: '텍스트 본문', type: 'textarea', initial: '고양이가 창가에 앉아 있다. 🐈' }, step, recordedAt], next: 'run-system' },
  { id: 'run-system', group: 'runs', title: '시스템 지표 기록', method: 'POST', path: '/api/v1/sdk/runs/{runId}/system-metrics', status: 204, auth: 'key', description: 'CPU/GPU 사용률은 0~100, 메모리는 바이트 단위입니다. GPU가 없으면 gpus를 빈 배열로 전송합니다.', fields: [{ name: 'system', label: '시스템 지표 (JSON)', type: 'json', required: true, initial: '{\n  "cpuUtilization": 25,\n  "ramUsed": 1073741824,\n  "ramTotal": 8589934592,\n  "gpus": []\n}' }, elapsed, recordedAt], next: 'run-complete' },
  { id: 'run-complete', group: 'runs', title: 'Run 완료', method: 'POST', path: '/api/v1/sdk/runs/{runId}/complete', status: 204, auth: 'key', description: '본문 없이 완료합니다. 같은 완료 재호출은 멱등적입니다. 종료 후 기록 요청은 409입니다.', next: 'run-get' },
  { id: 'run-fail', group: 'runs', title: 'Run 실패', method: 'POST', path: '/api/v1/sdk/runs/{runId}/fail', status: 204, auth: 'key', description: '실패 테스트는 새 Run으로 진행하세요. 같은 사유의 실패 재호출은 멱등적이고 완료된 Run의 실패 전환은 409입니다.', fields: [{ name: 'error', label: '실패 사유', type: 'textarea', initial: 'Out of memory during training', required: true }], next: 'run-get' },
  { id: 'web-run-list', group: 'runs', title: '웹 실험 목록', method: 'GET', path: '/api/v1/projects/{projectId}/runs', status: 200, auth: 'public', query: true, description: '프로젝트 공개 범위에 따라 목록과 상태 집계를 조회합니다.', fields: [{ name: 'page', label: '페이지', type: 'integer', initial: '0' }, { name: 'size', label: '크기', type: 'integer', initial: '20' }] },
  { id: 'web-run-get', group: 'runs', title: '웹 실험 상세', method: 'GET', path: '/api/v1/projects/{projectId}/runs/{runId}', status: 200, auth: 'public', description: '웹 로그인 또는 PUBLIC 공개 정책으로 실험을 조회합니다.' },
  { id: 'web-run-records', group: 'runs', title: '웹 실험 기록', method: 'GET', path: '/api/v1/projects/{projectId}/runs/{runId}/records', status: 200, auth: 'public', description: '메트릭, 파라미터, 로그, 텍스트, 시스템 지표를 조회합니다.' },
]

export function initialValues(endpoint: Endpoint): Record<string, string> {
  return Object.fromEntries((endpoint.fields ?? []).map(field => [field.name, field.initial ?? '']))
}
