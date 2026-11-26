# LabFlow Backend

Java 21 / Spring Boot 4.1 기반 백엔드입니다. 도메인과 JPA 엔티티를 분리하고 서비스에서 트랜잭션을 관리합니다.

현재 DB 기준은 Flyway의 `V1__initial_schema.sql`입니다. 새 DB는 자동 생성하고, 기존 DB는 구조 검사 후 명시적으로 편입합니다. 전체 16개 테이블, 전환 명령 및 운영 절차는 [DB 스키마 관리](database/README.md)를 참고하세요. 이 문서의 과거 개별 SQL은 최초 편입 전 누락 사항 보정용으로만 남겨 두었습니다.

## 검증

```powershell
.\gradlew.bat build
```

기본 회귀 테스트는 `test` 프로필의 메모리 H2를 사용하므로 별도 MariaDB가 필요하지 않습니다. `LABFLOW_MARIADB_BIN`을 지정한 선택 검사는 별도 임시 MariaDB 프로세스로 실제 마이그레이션과 JPA 검증을 수행합니다. 운영 DB 설정은 변경하지 않습니다. H2 회귀 테스트는 인증, MVC, 영속성 변경을 확인하며 MariaDB 고유의 잠금/동시성 동작까지 보장하지는 않습니다.

## Java 코드 포맷

Spotless와 Google Java Format의 AOSP 스타일(들여쓰기 4칸)을 사용합니다. 적용 범위는 `src/**/*.java`로, main/test 소스 모두 포함합니다.

```powershell
# backend 폴더에서 전체 Java 소스 자동 정리
.\gradlew.bat spotlessApply
# 수정 없이 포맷 규칙 검사
.\gradlew.bat spotlessCheck
# 포맷 검사와 테스트를 포함한 빌드
.\gradlew.bat build
```

`build`는 규칙 위반을 검사하며 파일을 자동 수정하지 않습니다. IDE의 별도 포맷터를 사용할 경우에도 커밋 전 `spotlessApply`를 실행해 프로젝트 규칙과 맞춥니다. 네이밍이나 패키지 설계 규칙은 포맷터의 적용 범위에 포함되지 않습니다.

## 계정 및 인증 정책

| 계정 상태 | 비밀번호 로그인 | 토큰 갱신 | 기존 JWT / SDK 키 |
| --- | --- | --- | --- |
| `PENDING_VERIFICATION` | 거부 | 거부 | 거부 |
| `ACTIVE` | 허용 | 허용 | 검증 후 허용 |
| `BLOCKED` | 거부 | 거부 | 다음 요청부터 거부 |

- JWT 요청마다 사용자 DB를 조회합니다. 토큰의 역할 대신 현재 저장된 역할을 사용하므로 관리자 권한 회수도 다음 요청부터 반영됩니다.
- 차단 전에 이미 처리를 시작한 요청을 취소하지는 않습니다. 토큰을 물리적으로 삭제하는 방식이 아니므로 향후 계정을 다시 활성화하면 만료되지 않은 토큰은 다시 유효할 수 있습니다.
- SDK 키 인증도 소유자 계정 상태를 확인합니다.
- 회원가입 비밀번호는 8자 이상, UTF-8 기준 72바이트 이하이며 공백 제거 등 원문 변형을 하지 않습니다.
- 사용자 이름, 프로젝트 이름, API 키 이름은 공백만으로 구성할 수 없으며 최대 100자입니다. 이메일은 최대 254자, 프로젝트 설명은 최대 2,000자입니다.
- 사용자 역할은 도메인 변경 후 `UserRepository.update()`로 저장합니다. 프로젝트는 `updateDetails(..., now)` 후 `ProjectRepository.update()`로 저장합니다.
- MVP의 프로젝트는 개인 소유이며 공개 범위는 `PUBLIC`과 `PRIVATE`만 지원합니다. 팀 관리와 `TEAM` 공개 범위는 추후 구현합니다. `PUBLIC` 상세는 익명 조회가 가능하고 `PRIVATE`는 소유자만 조회할 수 있습니다. 개인 목록은 로그인한 사용자의 프로젝트만 반환합니다.
- 기존 DB에 `TEAM` 프로젝트가 있다면 배포 전에 공개 범위를 `PRIVATE`으로 변경하고 DB enum/check 제약도 두 값에 맞춰 마이그레이션해야 합니다. 이 데이터 변경은 자동 실행하지 않습니다.

## Swagger / OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

Spring Boot 4.1과 호환되는 `springdoc-openapi-starter-webmvc-ui:3.2.0`을 사용합니다. [호환성 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)

현재 HTTP로 구현된 API만 Swagger에 표시됩니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | CSRF 쿠키 초기화. 로그인 불필요 |
| GET | `/api/v1/auth/me` | 현재 로그인한 사용자의 ID·이름·이메일·역할 (200) |
| POST | `/api/v1/auth/sign-up` | 미인증 회원 생성 및 코드 발송 요청 (201) |
| POST | `/api/v1/auth/login` | 이메일/비밀번호 인증 후 HttpOnly 인증 쿠키 발급 (204) |
| POST | `/api/v1/auth/refresh` | refresh 쿠키와 CSRF로 토큰 회전 (204) |
| POST | `/api/v1/auth/logout` | 현재 refresh 토큰 폐기 및 인증 쿠키 삭제, CSRF 필요 (204) |
| POST | `/api/v1/auth/email-verifications/verify` | 코드 검증 후 계정 활성화 (204) |
| POST | `/api/v1/auth/email-verifications/resend` | 코드 재발송 요청 (202) |
| POST | `/api/v1/project-api-keys/issue/{projectId}` | 프로젝트 소유자의 API 키 발급 |
| GET | `/api/v1/projects/{projectId}/api-keys?page=0&size=20` | 소유자의 API 키 목록. 원문/해시 제외 (200) |
| DELETE | `/api/v1/projects/{projectId}/api-keys/{keyId}` | 소유자의 API 키 폐기. 반복 폐기 204 |
| POST | `/api/v1/projects` | 개인 프로젝트 생성 (201) |
| GET | `/api/v1/projects?page=0&size=20` | 내 프로젝트 페이지 (200) |
| GET | `/api/v1/projects/{projectId}` | 공개 범위를 적용한 프로젝트 상세 (200) |
| PUT | `/api/v1/projects/{projectId}` | 소유자의 전체 정보 수정, 조회 version 필수 (204) |
| DELETE | `/api/v1/projects/{projectId}?version=0` | 소유자의 프로젝트 및 종속 데이터 삭제 (204) |
| GET | `/api/v1/projects/{projectId}/runs?page=0&size=20` | 웹 실행 목록 및 프로젝트 전체 상태 집계 (200) |
| GET | `/api/v1/projects/{projectId}/runs/compare?runIds=123,456&metricKey=val_accuracy` | Run 2~4개의 곡선·공통 Step·기준 대비 차이·파라미터 비교 (200) |
| GET | `/api/v1/projects/{projectId}/runs/summaries?runIds=123,456` | 최대 100개 Run의 최신 지표·마지막 Step 일괄 조회 (200) |
| GET | `/api/v1/projects/{projectId}/runs/{runId}/metric-series?metricKey=loss` | 단일 Run의 전체 구간 지표 곡선과 최신값 (200) |
| GET | `/api/v1/projects/{projectId}/runs/{runId}` | 웹 실행 상세 (200) |
| GET | `/api/v1/projects/{projectId}/runs/{runId}/records?limit=1000` | 파라미터·메트릭·로그·텍스트·시스템 지표 조회 (200) |
| GET | `/api/v1/projects/{projectId}/runs/{runId}/records/{kind}?limit=200&latest=true` | 기록 종류별 최신·이전·이후 커서 페이지 (200) |
| POST | `/api/v1/sdk/runs` | 실행 생성. ID와 Location 반환 (201) |
| GET | `/api/v1/sdk/runs/{runId}` | 실행 상태·메타데이터 조회 (200) |
| PUT | `/api/v1/sdk/runs/{runId}/parameters` | 불변 파라미터 추가, 같은 키/값 재전송 허용 (204) |
| POST | `/api/v1/sdk/runs/{runId}/metrics` | 숫자 메트릭 배치 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/logs` | 출처·레벨별 실행 메시지 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/texts` | 모델 입력·출력 등의 텍스트 데이터 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/system-metrics` | CPU/RAM/GPU 측정 스냅샷 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/complete` | 실행 완료, 본문 없음 (204) |
| POST | `/api/v1/sdk/runs/{runId}/fail` | 실패 사유와 함께 종료 (204) |

이메일 인증 후 로그인하여 프로젝트 생성·키 발급·SDK 실행까지 HTTP로 호출할 수 있습니다.

### 로그인·토큰 갱신·로그아웃

`POST /api/v1/auth/login`은 `{"email":"user@example.com","password":"your-password"}`를 받습니다. ACTIVE 계정만 성공하며 기존 정책에 따라 CSRF는 제외합니다. 실패한 이메일·비밀번호와 비활성 계정은 구분하지 않는 `401 UNAUTHORIZED`를 반환합니다. 이메일/필수값/길이 오류는 400이며 비밀번호 원문 공백은 보존하고 UTF-8 72바이트를 초과하면 거부합니다.

로그인·갱신 성공은 본문 없는 204이며 토큰 원문은 JSON 대신 `Set-Cookie`로만 전달합니다. `access_token`은 Path `/`, `refresh_token`은 Path `/api/v1/auth`이고 모두 HttpOnly 및 설정된 Secure/SameSite를 적용합니다. `Cache-Control: no-store`를 사용하며 기존 CSRF 쿠키 발급 헤더도 보존합니다. 로그인 응답이나 `GET /api/v1/auth/csrf`에서 받은 XSRF-TOKEN 쿠키 원문을 이후 변경 요청의 X-XSRF-TOKEN 헤더에 보냅니다.

프런트엔드가 다른 origin에서 호출할 때는 fetch의 `credentials: 'include'` 또는 axios의 `withCredentials: true`가 필요합니다. CORS 허용 출처는 기존 `CORS_ALLOWED_ORIGINS` 설정을 사용합니다.

`POST /api/v1/auth/refresh`는 본문 없이 호출하며 유효한 refresh_token 쿠키와 CSRF가 필요합니다. access JWT가 없거나 만료되어도 갱신할 수 있습니다. refresh 토큰의 상태와 소유자의 현재 계정 상태를 확인하고, 기존 토큰 폐기와 새 토큰 저장을 한 트랜잭션에서 처리합니다. 같은 토큰의 동시 갱신은 한 요청만 성공하고 이전 토큰 재사용은 401입니다. refresh 누락/오류/만료/폐기/비활성 계정도 401이며, 실패한 요청은 새 쿠키나 삭제 쿠키를 발급하지 않습니다. 프런트엔드는 갱신 요청을 하나로 합쳐야 합니다. Swagger에는 refresh 쿠키 AND CSRF로 표시하며 access 쿠키는 요구하지 않습니다.

`POST /api/v1/auth/logout`은 CSRF를 요구하고 현재 refresh 토큰만 폐기한 뒤 생성 시와 같은 Path/HttpOnly/Secure/SameSite로 인증 쿠키를 삭제합니다. refresh 토큰이 없거나 잘못되었거나 이미 만료/폐기되어도 204로 동작하며 다른 로그인 세션과 SDK 키는 유지합니다. CSRF가 없거나 틀리면 상태 변경 없이 403입니다. 로그아웃은 브라우저 쿠키 삭제와 refresh 재사용 차단이며, 이미 복사한 access JWT를 즉시 무효화하는 denylist/세션 저장소는 없습니다. 복사된 JWT는 기존 만료 시각(기본 15분)까지 유효할 수 있습니다. 같은 세션에서 갱신과 로그아웃을 동시에 보내지 않도록 클라이언트가 요청을 조정해야 합니다.

```powershell
$authUrl = 'http://localhost:8080/api/v1/auth'
Invoke-RestMethod -Method Post -Uri "$authUrl/login" -ContentType 'application/json' -Body '{"email":"user@example.com","password":"your-password"}' -SessionVariable labflowSession
Invoke-RestMethod -Method Get -Uri "$authUrl/csrf" -WebSession $labflowSession
$csrfHeaders = @{ 'X-XSRF-TOKEN' = $labflowSession.Cookies.GetCookies([uri]$authUrl)['XSRF-TOKEN'].Value }
Invoke-RestMethod -Method Post -Uri "$authUrl/refresh" -WebSession $labflowSession -Headers $csrfHeaders
Invoke-RestMethod -Method Post -Uri "$authUrl/logout" -WebSession $labflowSession -Headers $csrfHeaders
```

`AuthHttpTests`는 이메일 발송만 대체하고 실제 비밀번호 인증·쿠키·CSRF·DB 트랜잭션으로 인증 흐름, 토큰 회전/재사용 거부, 로그아웃 멱등성, 동시 갱신, 새 토큰 저장 실패 시 롤백을 검사합니다. 회원가입→이메일 인증→로그인→프로젝트 생성→키 발급→SDK 실행도 HTTP로 연결해 검증합니다.

### 개인 프로젝트 API

프로젝트 생성·목록·수정·삭제에는 ACTIVE 사용자의 `access_token` 쿠키가 필요하며 변경 요청에는 CSRF 쿠키/헤더도 필요합니다. 생성 시 소유자는 인증 정보에서 결정합니다. SDK Bearer 키는 프로젝트 관리 권한을 주지 않습니다. 공개 상세 조회는 로그인 없이 가능하고 비공개 상세 조회는 비로그인 401, 다른 사용자 403입니다. 관리자도 다른 사용자의 비공개 프로젝트를 관리할 수 없습니다.

생성 본문은 `{"name":"my-project","description":"실험 기록","visibility":"PRIVATE"}`입니다. 성공하면 `201`, `{"projectId":123}`과 상세 URL을 담은 Location 헤더를 반환합니다. `GET /api/v1/projects`는 다른 사용자의 PUBLIC 프로젝트를 포함하지 않는 개인 목록입니다. `page`는 0부터, `size`는 기본 20/최대 100이며 생성 시각과 ID 내림차순으로 정렬합니다. 응답은 `items`, `page`, `size`, `totalElements`, `totalPages`입니다. 공개 프로젝트 탐색용 전체 목록은 별도로 구현하지 않았습니다.

상세/목록 항목에는 `projectId`, `name`, `description`, `createdBy`, `visibility`, `createdAt`, `updatedAt`, `version`이 있습니다. 수정은 상세에서 받은 version과 함께 `{"name":"renamed","description":null,"visibility":"PUBLIC","version":0}`을 PUT으로 전송합니다. 수정 후에는 최신 상세를 다시 조회합니다. 삭제도 같은 조회 버전을 `?version=0`으로 보내며 오래된 수정·삭제 요청은 `409 STALE_STATE`입니다. 소유 관계와 생성 시각은 변경되지 않습니다.

삭제는 소유자·버전 확인 후 프로젝트 키, Run, 파라미터·메트릭·로그·텍스트·시스템/GPU 지표·Run 메타데이터를 한 트랜잭션에서 영구 제거합니다. 중간 실패 시 모두 롤백합니다. 프로젝트 행 잠금은 키 발급, Run 생성, Run 상태 변경 및 기록 추가와 공유하여 삭제와 동시에 고아 데이터가 생기지 않도록 합니다. 기존 키로 진행 중인 요청도 삭제 후 새 Run/기록을 생성할 수 없습니다. 반복 삭제는 404입니다. 이 삭제 API에 필요한 테이블은 Flyway V1에 포함되어 있습니다.

`ProjectHttpTests`는 실제 JWT·CSRF·보안 필터와 H2에서 CRUD, 공개 범위, 개인 목록, 소유자 권한, 클라이언트 버전 충돌, 종속 데이터 삭제·롤백·동시성을 확인합니다. 테스트 프로필은 로컬 Gmail 설정과 분리하여 외부 메일을 발송하지 않습니다.

### 웹 Run 비교 API

`GET /api/v1/projects/{projectId}/runs/compare`는 `frontend`의 Run 비교에 필요한 데이터를 한 번에 반환합니다. 프로젝트 조회와 같은 공개 정책을 적용합니다. PUBLIC은 익명 조회 가능, PRIVATE은 소유자만 가능하며 다른 프로젝트 또는 없는 Run을 포함하면 전체 요청을 404로 거부합니다. SDK Bearer 키는 웹 사용자의 인증을 대체하지 않습니다.

| 쿼리 | 설명 |
| --- | --- |
| `runIds` | 같은 프로젝트의 서로 다른 Run 2~4개. `runIds=123,456` 또는 반복 파라미터 지원. 응답도 선택 순서 유지 |
| `metricKey` | SDK에 기록한 실제 지표 이름(예: `val_accuracy`, `recall_at_10`, `val_loss`). 필수, 1~100자 |
| `baselineRunId` | 선택한 Run 중 기준 Run. 생략하면 첫 Run |
| `differencesOnly` | true이면 파라미터 비교 행 중 값이 다른 행만 반환. 기본 false |
| `maxPoints` | Run별 차트 점 수 상한. 기본 500, 2~1000 |

예: `/api/v1/projects/100/runs/compare?runIds=201,202&metricKey=val_accuracy&baselineRunId=202&differencesOnly=true&maxPoints=500`

응답의 `runs`는 각 Run의 ID·이름·상태·시작/종료 시각과 다음 데이터를 포함합니다.

- `latestMetric`: 선택 지표의 전체 기록 중 `recordedAt`, ID 순으로 가장 최근 기록. step이 없는 기록도 포함합니다.
- `lastStep`: 선택 지표에 실제로 기록된 가장 큰 Step. `latestMetric.step`은 늦게 도착한 이전 Step 기록 등으로 이 값과 다를 수 있습니다.
- `commonStepMetric`: 모든 선택 Run에 선택 지표가 실제로 존재하는 **가장 큰 공통 Step**의 값. 기록이 없는 Step을 보간하거나 이전 값으로 채우지 않습니다.
- `deltaFromBaseline`: 공통 Step에서 현재 Run 값에서 기준 Run 값을 뺀 수치. 기준 Run은 0이며 지표의 좋고 나쁨과 무관한 부호입니다.
- `series`: Step 오름차순의 `points`, 중복 Step 제거 후 원본 점 수 `totalPoints`, 축약 여부 `sampled`. 각 점에는 ID·Step·값·epoch·경과 시간·기록 시각이 있습니다.

최상위 `commonStep`이 null이면 모든 Run의 공통값과 차이도 null입니다. 선택 지표가 없는 Run은 `latestMetric`/`lastStep`이 null이고 곡선은 빈 배열입니다. Step 없는 지표는 최신값에만 포함합니다. `availableMetrics`는 선택 Run들이 기록한 지표 이름의 합집합으로 지표 선택 메뉴에 사용할 수 있습니다. 알 수 없는 지표를 요청하면 값을 만들어내지 않고 빈 결과를 반환합니다.

같은 Run/지표/Step에 여러 기록이 있으면 `recordedAt`이 큰 기록, 시각까지 같으면 ID가 큰 기록을 사용합니다. 차트는 이 기준으로 중복을 제거한 뒤 전체 Step 범위의 실제 기록을 일정 순위 간격으로 선택하고 첫 점과 마지막 점을 보존합니다. 최대 `maxPoints`개이며 보간 값은 생성하지 않습니다. 전체 이력을 JVM에 로드하지 않고 DB에서 축약합니다. 공통 Step·최신값·차이는 차트 샘플과 독립적으로 원본 전체에서 계산하므로 샘플에 없는 공통 Step도 정확히 비교합니다. 하나의 REPEATABLE_READ 트랜잭션으로 처리합니다.

최상위 `parameters`는 `{key, values, different}` 행 배열입니다. `values`는 응답 `runs`와 같은 순서이며 미설정 값은 null입니다. 빈 문자열과 미설정 값은 다르게 취급합니다. 키 합집합을 이름순으로 정렬하며 `differencesOnly`는 이 행 배열에만 적용됩니다. 중복 Run, 범위를 벗어난 선택 개수/점 수, 선택에 없는 기준 Run 등은 `400 INVALID_INPUT`입니다.

`frontend`의 비교 화면은 이 API의 응답으로 곡선·공통값·차이·파라미터를 표시합니다. 기존 테이블을 사용하며 DB 마이그레이션은 필요하지 않습니다. `RunComparisonHttpTests`가 실제 HTTP·JWT·H2에서 비교, 기준 변경, 누락/희소 Step, 중복 기록, 1,501점 이력의 샘플링과 권한·입력 오류를 검증합니다.

### Studio 전체 API 연결

Run 목록은 `page`(0부터), `size`(1~100), `search`(이름의 부분 문자열, 최대 100자), `status`(쉼표로 구분한 상태), `sort`(`recent`, `high`, `low`, `duration`), `metricKey`, `runIds`(서로 다른 ID 최대 100개)를 지원합니다. 검색·필터 후 DB에서 정렬하고 한 페이지만 반환합니다. 지표 정렬은 `metricKey`가 필수이며 시각/ID 기준 최신 값을 사용하고 미기록 Run은 뒤에 둡니다. `duration`은 진행 중이면 서버 현재 시각까지의 시간을 사용합니다. 동률은 시작 시각/ID 내림차순입니다. `totalElements`는 검색 결과 수, `projectTotal`과 `counts`는 프로젝트 전체 집계, `availableMetrics`는 프로젝트 전체 지표 이름입니다. Studio는 페이지당 20개 및 그 요약만 조회하며 페이지 간 비교 선택을 유지합니다. 개요의 확인할 실험은 별도 상태 필터로 최대 3개를 조회합니다. 이 인덱스는 Flyway V1에 포함됩니다. 기존 DB 최초 편입 시 누락되었다면 `database/run-list-metric-index.sql`을 검토해 보정한 뒤 구조 검사를 통과해야 합니다.

프런트는 같은 출처의 탭 간 Web Locks(미지원 시 IndexedDB 임대)로 refresh·로그인·로그아웃을 직렬화합니다. 잠금을 얻은 뒤 `/auth/me`로 다른 탭의 갱신 여부를 확인하고 불필요한 회전을 생략합니다. BroadcastChannel/storage 이벤트로 세션 변경을 전달하고 이전 사용자의 화면과 진행 요청을 정리합니다. 종료된 실험과 비교는 폴링을 중지하며 상세·설정 화면은 Run 목록/요약을 주기적으로 읽지 않습니다.

`frontend`는 세션, 프로젝트 CRUD, Run 목록·개요·비교·상세, 기록 커서 탐색, API 키 발급·목록·폐기와 기록 내보내기를 실제 API로 처리합니다. 빈 데이터나 서버 오류를 샘플 데이터로 대체하지 않습니다. 최신 프런트에는 여기 추가한 API가 필요하므로 백엔드를 다시 빌드/실행합니다.

`GET /api/v1/auth/me`는 현재 사용자만 반환하고 쿠키를 JSON에 노출하지 않습니다. 만료된 access 쿠키는 공통 프런트 API 클라이언트가 CSRF/refresh로 한 번 갱신합니다. 키 관리 API는 PUBLIC 프로젝트여도 소유자만 사용할 수 있고 원문/해시는 목록에 포함하지 않습니다. 폐기는 프로젝트 잠금과 기존 키 버전 검사를 적용하며 폐기한 키의 SDK 요청은 거부합니다. 키 목록은 0 기반 페이지, size 1~100입니다.

Run 요약은 같은 프로젝트의 서로 다른 Run 1~100개를 받아 선택 순서로 각 Run의 `metrics`와 `lastStep`을 반환합니다. `metrics`는 지표별 시각/ID 기준 최신 기록이며 마지막 Step은 전체 지표 기록의 최대 Step입니다. SQL 윈도 함수로 최신 기록만 선택하므로 전체 이력을 애플리케이션 메모리에 로드하지 않습니다. 다른 프로젝트의 Run이나 없는 Run을 포함하면 404입니다. 단일 곡선은 비교 API와 같은 중복 Step 제거·전체 구간 샘플링 규칙과 `maxPoints` 상한을 사용합니다.

`LABFLOW_STUDIO_NODE`에 Node 실행 파일 경로를 설정하고 `StudioBrowserIntegrationTests`를 실행하면 임시 HTTP/H2 서버와 실제 `frontend` 브라우저에서 로그인·세션 갱신·프로젝트 저장/삭제·키 발급/폐기·SDK 기록·비교·공개 조회·JSON 다운로드·로그아웃을 검증합니다. 별도 `LABFLOW_RECORDS_NODE`의 `RecordCursorBrowserIntegrationTests`는 1,000개 초과 기록 탐색과 최신 갱신을 검증합니다. 새 DB 컬럼/마이그레이션은 없습니다.

### SDK Run API

웹 화면은 `run/run/presentation/RunQueryController`의 사용자용 조회 API를 사용합니다. `RunQueryService`는 먼저 프로젝트 공개 정책을 확인하고 Run이 URL의 프로젝트에 속하는지 검사합니다. PUBLIC 프로젝트의 실행·기록은 비로그인 조회가 가능하고 PRIVATE은 소유자만 읽을 수 있습니다. 다른 프로젝트 Run ID를 섞으면 404입니다. SDK 키는 웹 사용자 인증을 대체하지 않습니다.

실행 목록은 생성 시각/ID 내림차순, 기본 20개/최대 100개의 페이지이며 전체 `counts.RUNNING/COMPLETED/FAILED`를 함께 반환합니다. records는 각 기록 종류를 시각/ID 오름차순으로 최대 limit개(기본/최대 1000) 반환하고 파라미터는 전체(최대 100개)를 반환합니다. `latest=true`이면 종류별 최근 limit개를 가져와 시간/ID 오름차순으로 반환합니다. 종류별 전체 탐색은 아래 커서 API를 사용합니다. 원문, 사용자 정의 metric 이름과 숫자 값, 선택적인 step/epoch/기록 시각을 보존합니다. 새 테이블이나 스키마 변경은 없습니다.

`frontend/`의 `http://127.0.0.1:5181/#/records`에서 실제 프로젝트와 Run을 선택해 기록을 조회합니다. 상단의 **실험 기록** 링크로도 진입합니다. 최신 페이지는 5초마다 갱신하며 과거 페이지를 탐색하는 동안에는 자동 갱신하지 않습니다. ADMIN 전용 API 테스트 도구는 `frontend/`의 `/#/api-test`로 통합했습니다. 사용법과 실제 브라우저 검증은 [관리자 API 테스트 안내](../docs/admin-api-workbench.md)를 참고하세요.

기록 커서 API:

```text
GET /api/v1/projects/{projectId}/runs/{runId}/records/{kind}?latest=true&limit=200
GET /api/v1/projects/{projectId}/runs/{runId}/records/{kind}?before={beforeCursor}&limit=200
GET /api/v1/projects/{projectId}/runs/{runId}/records/{kind}?after={afterCursor}&limit=200
```

`kind`는 `metrics`, `logs`, `texts`, `system-metrics`입니다. `limit`은 기본 200/최대 1,000이며 전체 기록 수의 제한이 아니라 한 페이지의 크기입니다. 옵션 없이 조회하면 첫 페이지를 반환합니다. `latest=true`, `before`, `after`는 동시에 사용할 수 없습니다. 파라미터는 최대 100개의 불변 설정이므로 기존 묶음 조회로 전체를 읽습니다.

응답은 `items`, `limit`, `hasMore`, `beforeCursor`, `afterCursor`, `nextCursor`입니다. `items`는 조회 방향과 무관하게 `(recordedAt, id)` 오름차순입니다. `beforeCursor`는 첫 항목 이전, `afterCursor`는 마지막 항목 이후를 조회할 때 사용합니다. `hasMore`와 `nextCursor`는 **현재 조회 방향** 기준이며, 최신/이전 조회는 더 과거의 기록, 처음/이후 조회는 더 새로운 기록을 가리킵니다. `nextCursor`는 `hasMore=false`일 때 null입니다. 빈 페이지는 모든 커서가 null이므로 증분 조회 호출자는 기존 `after` 위치를 보관해 다음 폴링에 재사용합니다.

커서는 버전·Run·기록 종류와 시간/ID 위치를 포함하는 불투명한 URL-safe 문자열입니다. 다른 Run/종류의 커서, 잘못된 형식, 잘못된 limit·옵션 조합은 400입니다. 커서는 권한을 부여하지 않으며 매번 PUBLIC/PRIVATE와 Run 소속을 확인합니다. 동일한 기록 시각도 ID로 구분하므로 페이지 경계에서 같은 기록을 반복하지 않습니다. 기존 `(run_id, recorded_at, id)` 인덱스를 사용하는 keyset 조회이며 별도 DDL은 필요하지 않습니다.

최신은 수신 순서가 아니라 **기록 시각** 기준입니다. SDK가 뒤늦게 과거 `recordedAt`을 보내면 해당 기록은 과거 페이지에 위치하며, 현재 `after` 위치보다 앞에 추가된 기록은 그 이후 조회에 포함되지 않습니다. 최신 조회는 고정된 스냅샷을 보장하지 않으며 새로운 기록을 반영합니다.

`RunQueryHttpTests`는 1,005개 기록, 동일 시각, 4종류 양방향 탐색, 최신/증분 조회와 잘못된 커서·접근 권한을 검사합니다. `RecordCursorBrowserIntegrationTests`는 `LABFLOW_RECORDS_NODE`에 Node 실행 파일의 절대 경로를 지정하면 실제 `frontend/` + Spring Boot/H2 + Edge로 이전/다음 이동과 최신 자동 갱신을 검증합니다.

SDK 경로는 `Authorization: Bearer <project-api-key>`만 사용합니다. JWT 쿠키와 CSRF 토큰은 필요하지 않으며 사용자 JWT를 Bearer 값으로 넣어도 인증되지 않습니다. Swagger의 Authorize에서 `projectApiKey`에 원문 키를 입력할 수 있습니다. SDK 작업의 프로젝트와 소유자는 인증된 키에서 결정되며 요청 파라미터로 변경할 수 없습니다. 같은 프로젝트의 다른 유효한 키는 같은 실행에 접근할 수 있습니다.

요청 DTO는 `sdk/presentation`에 두고 `toCommand()`로 application 명령으로 변환합니다. `SdkRunController`는 인증 정보 추출·검증·HTTP 응답만 담당하고 서비스가 프로젝트 접근, 상태, 트랜잭션을 검사합니다. 생성은 `201`과 `{ "runId": 123 }`, 조회는 `200`과 실행 응답, 기록/종료는 본문 없는 `204`입니다. 실행 조회에 영속성 버전·키 해시·사용자 인증 정보는 포함하지 않습니다.

| 요청 | 본문 예시 |
| --- | --- |
| 실행 생성 | `{"name":"training","metadata":{"framework":"pytorch"}}` |
| 파라미터 | `{"parameters":{"optimizer":"adam","batch_size":"32"}}` |
| 메트릭 | `{"step":10,"epoch":1,"elapsedTime":1.5,"metrics":{"loss":0.125,"accuracy":0.94}}` |
| 로그 | `{"message":"training started","level":"INFO","source":"USER"}` |
| 텍스트 | `{"key":"prediction","text":"고양이","step":10}` |
| 시스템 지표 | `{"elapsedTime":1.5,"system":{"cpuUtilization":25,"ramUsed":1024,"ramTotal":8192,"gpus":[{"index":0,"utilization":50,"memoryUsed":2048,"memoryTotal":4096}]}}` |
| 실패 | `{"error":"out of memory"}` |

메트릭 값은 JSON 숫자만 허용하며 `"0.125"` 같은 문자열, boolean, null, 객체/배열과 유한하지 않은 수는 `400`입니다. 로그는 `message`, `level`, `source`가 필수이며 레벨은 `DEBUG/INFO/WARN/ERROR`, 출처는 `USER/STDOUT/STDERR/SDK`입니다. 로그/텍스트의 빈 본문 문자열은 허용하고 공백과 줄바꿈을 보존합니다. 각 기록의 `recordedAt`은 ISO-8601 시각으로 선택 사항이며 생략 시 서버 시각을 사용합니다. GPU 목록도 생략할 수 있습니다.

ACTIVE 사용자·개인 프로젝트·발급한 키와 DB 스키마를 준비한 뒤 다음처럼 한 실행을 호출할 수 있습니다.

```powershell
$sdkUrl = 'http://localhost:8080/api/v1/sdk/runs'
$sdkHeaders = @{ Authorization = 'Bearer <project-api-key>' }
$run = Invoke-RestMethod -Method Post -Uri $sdkUrl -Headers $sdkHeaders -ContentType 'application/json' -Body '{"name":"training"}'
Invoke-RestMethod -Method Put -Uri "$sdkUrl/$($run.runId)/parameters" -Headers $sdkHeaders -ContentType 'application/json' -Body '{"parameters":{"optimizer":"adam"}}'
Invoke-RestMethod -Method Post -Uri "$sdkUrl/$($run.runId)/metrics" -Headers $sdkHeaders -ContentType 'application/json' -Body '{"step":1,"metrics":{"loss":0.125}}'
Invoke-RestMethod -Method Post -Uri "$sdkUrl/$($run.runId)/complete" -Headers $sdkHeaders
Invoke-RestMethod -Method Get -Uri "$sdkUrl/$($run.runId)" -Headers $sdkHeaders
```

`SdkRunHttpTests`는 MockMvc·실제 보안 필터·H2·실제 발급 키로 위 흐름, 배치 롤백, 상태 충돌, 프로젝트 접근 제한, 키 폐기·소유자 상태 변경, DTO 검증과 OpenAPI 계약을 검사합니다. 테스트 자체에 외부 트랜잭션을 두지 않아 HTTP 서비스의 실제 커밋/롤백을 확인합니다.

### 이메일 코드로 회원가입

이메일 인증을 완료하지 않고 나갔다가 다시 로그인하면, 비밀번호가 올바른 경우 `401 EMAIL_VERIFICATION_REQUIRED`를 반환합니다. 프론트는 이메일을 유지하고 비밀번호를 지운 뒤 인증 화면으로 이동합니다. 기존 코드가 만료되었다면 해당 화면에서 재발송을 요청할 수 있습니다. 틀린 비밀번호와 차단 계정은 기존 `401 UNAUTHORIZED`를 유지하며 인증 쿠키는 발급하지 않습니다.

1. `POST /api/v1/auth/sign-up`에 `email`, `password`, `confirmPassword`, `name`을 보냅니다.
2. 이메일로 받은 6자리 영문/숫자 코드를 입력합니다. I/L/O/0/1은 생성하지 않습니다.
3. `GET /api/v1/auth/csrf`로 CSRF 쿠키를 받은 뒤, 쿠키와 `X-XSRF-TOKEN` 헤더를 함께 전송합니다.
4. `POST /api/v1/auth/email-verifications/verify`에 `{"email":"user@example.com","code":"A2B3C4"}`를 보냅니다. 성공하면 계정이 ACTIVE로 바뀝니다. 자동 로그인하지 않습니다.
5. 미수신 시 `POST /api/v1/auth/email-verifications/resend`에 `{"email":"user@example.com"}`를 보냅니다. 이 요청에도 CSRF가 필요합니다.

코드는 10분간 유효하고 대소문자를 구분하지 않으며 앞뒤 공백을 제거합니다. 실패 5회 후 잠기며, 재발송하면 이전 코드는 무효화됩니다. 재발송은 60초 간격이고 최초 발송을 포함해 계정당 시간당 5회로 제한합니다. 가입/재발송은 IP당 시간당 합계 10회, 검증은 100회입니다. 로그인은 IP당 분당 60회와 정규화한 이메일/IP 조합당 15분에 10회를 함께 적용합니다. 성공·실패 모두 요청 횟수에 포함하며 계정 자체를 전역 잠그지 않습니다. 로그인 한도는 양수 `LOGIN_IP_LIMIT`, `LOGIN_ACCOUNT_IP_LIMIT`로 변경합니다. 모든 IP 제한은 DB의 고정 시간 구간 카운터를 원자적으로 증가시켜 재시작·여러 서버 인스턴스에서도 공유합니다. 시간 구간 경계에서는 다음 구간 한도를 새로 사용할 수 있습니다. DB에는 IP/이메일 원문 대신 SHA-256 키·만료 시각·횟수만 저장하며 만료 행은 요청 시 최대 분당 한 번 정리합니다. 테이블은 Flyway V1에 포함됩니다. 기존 DB 최초 편입 시 누락되었다면 `database/auth-request-limits.sql`을 검토해 보정합니다.

`TRUSTED_PROXY_CIDRS`는 즉시 연결하는 신뢰 프록시의 CIDR 목록이며 기본값은 `127.0.0.1/32,::1/128`, 빈 값은 헤더 신뢰 안 함입니다. 비신뢰 주소의 `X-Forwarded-For`는 무시하고 신뢰 프록시 체인은 오른쪽부터 첫 비신뢰 IP까지 확인합니다. 호스트 이름은 허용하지 않으며 잘못된/과도한 헤더는 소켓 주소로 처리합니다. 광범위한 네트워크를 신뢰 목록에 넣지 마세요. Servlet의 자동 forwarded 주소 변경은 꺼 두어 이 검증보다 먼저 원격 주소가 바뀌지 않게 합니다.

`scripts/serve-external.mjs`는 전달받은 forwarding 헤더를 덮어씁니다. 외부 진입이 Cloudflare/cloudflared로만 제한된 구성에서는 **게이트웨이 프로세스 환경**에 `LABFLOW_TRUST_CLOUDFLARE_HEADERS=true`를 지정하면 검증한 `CF-Connecting-IP`를 백엔드로 전달합니다. 기본값은 false이며 소켓 주소를 사용합니다. 이 옵션은 백엔드 `.env`가 아닌 게이트웨이를 실행하는 환경에 설정합니다. `node --test scripts/serve-external.test.mjs`로 위조 헤더 제거와 옵션 동작을 확인합니다.

검증 실패는 400, IP 제한 초과는 429입니다. 재발송은 계정 존재·상태 및 계정별 제한 여부를 노출하지 않고 항상 202를 반환합니다. 202는 메일 도착을 보장하지 않습니다.

`.env`에 `EMAIL_VERIFICATION_SECRET`(JWT 키와 별개의 임의 값, 최소 32바이트), `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`을 설정합니다. 예시 파일은 SMTP 587/STARTTLS 기준입니다. 비밀키 누락 시 회원가입 트랜잭션은 실패하며 저장되지 않습니다. SMTP 설정 누락/발송 실패 시에는 가입 정보가 유지되고 서버에 원문 없는 오류 로그가 남습니다.

사용자와 코드 HMAC은 같은 트랜잭션에 저장하며 메일은 커밋 후 발송합니다. 현재 발송은 요청 스레드에서 수행되며 SMTP 타임아웃은 각각 5초입니다. 발송 실패 시 사용자가 재발송해야 합니다. 메모리 이벤트이므로 커밋 직후 서버가 종료되면 메일이 유실될 수 있으며, 자동 재시도/전달 보장이 필요하면 영속 발송 큐를 추가해야 합니다. 인증 코드는 로그와 API 응답에 포함하지 않습니다.

테이블은 Flyway V1에 포함됩니다. 기존 DB 최초 편입 시 누락되었다면 `database/email-verifications.sql`을 검토해 보정합니다. 일반 회귀 테스트에서는 H2가 테이블을 생성합니다.

이메일 인증 코드는 다음 계층으로 구분합니다.

- `auth/application/verification`: 인증 서비스, 코드 생성/HMAC, 메일 발송 인터페이스와 발송 요청 이벤트
- `auth/domain`: `EmailVerification`과 저장소 계약 `EmailVerificationRepository`
- `auth/domain/exception`: 인증 실패 예외
- `auth/infrastructure/persistence`: 엔티티, `EmailVerificationMapper`, `EmailVerificationJpaRepository`, `EmailVerificationRepositoryImpl`
- `auth/infrastructure/mail`: SMTP 발송 구현과 커밋 후 발송 리스너
- `auth/presentation`: HTTP 컨트롤러와 IP 요청 제한

저장소 구현은 서비스 트랜잭션을 필수로 요구합니다. JPA 저장소의 쓰기 잠금은 서비스가 끝날 때까지 유지되며, Mapper가 변환을 담당합니다. `save`는 신규 INSERT, `update`는 기존 행 갱신입니다. 엔티티는 `AssignedIdEntity`를 사용하고 기존 `user_id` 기본키 컬럼을 유지합니다.

### Swagger에서 인증된 요청 실행

1. 서버를 DB와 JWT 설정으로 실행합니다.
2. Swagger의 `POST /api/v1/auth/login`에 ACTIVE 계정의 이메일/비밀번호를 입력합니다. 성공하면 브라우저에 HttpOnly 인증 쿠키가 저장됩니다.
3. Swagger의 `GET /api/v1/auth/csrf`를 먼저 실행해 `XSRF-TOKEN` 쿠키를 받습니다.
4. API 키 발급을 실행하면 Swagger UI가 CSRF 쿠키 원문을 `X-XSRF-TOKEN` 헤더로 보냅니다.

`access_token`은 HttpOnly 쿠키입니다. Swagger의 Authorize 입력만으로 브라우저의 HttpOnly 쿠키를 설정할 수 없습니다. SDK의 Bearer API 키는 사용자 JWT와 별개의 인증 수단입니다.

### 오류 응답

```json
{
  "code": "INVALID_INPUT",
  "message": "Request validation failed",
  "errors": { "name": "must not be blank" }
}
```

| 상태 | 의미 |
| --- | --- |
| 400 | 요청 본문, 경로 인자, 도메인 입력 제약 위반 |
| 401 | 인증 실패, 미인증/차단 계정, 유효하지 않은 토큰 |
| 403 | 프로젝트 소유자가 아님 또는 CSRF 검증 실패 |
| 404 | 프로젝트/사용자/실행 없음 |
| 409 | 이메일 중복, 실행 상태·파라미터 충돌, 동시 저장 제약 또는 오래된 버전 |

오류 응답에 비밀번호, 토큰 원문, DB 오류 메시지를 넣지 않습니다. CSRF 검증은 인증 필터보다 앞서므로 CSRF가 빠진 변경 요청은 인증 상태와 무관하게 먼저 403이 될 수 있습니다.

프로젝트/API 키의 오래된 객체를 저장하면 `409 STALE_STATE`로 거부합니다. 갱신 후 같은 도메인 객체를 다시 저장하려면 저장소에서 최신 버전을 다시 조회해야 합니다.

## .env 환경 설정과 실행

`backend/`에서 `.env.example`을 `.env`로 복사하고 값을 채웁니다. 이미 `.env`가 있다면 덮어쓰지 않습니다.

```powershell
# 최초 설정 시에만 실행
Copy-Item .env.example .env
# DB와 JWT 값을 채운 후 실행
.\gradlew.bat bootRun
```

`.env`는 Spring Config Data의 확장자 힌트 `[.properties]`로 읽습니다. 별도 dotenv 라이브러리가 필요하지 않으며, **따옴표나 `export` 없는 `KEY=value` 형식**을 사용합니다. 주석은 별도 줄에 작성하고, 값 안의 역슬래시는 Java properties 규칙에 따라 이스케이프합니다. [Spring Boot 설정 파일 문서](https://docs.spring.io/spring-boot/reference/features/external-config.html#importing-extensionless-files)

| 키 | 의미 |
| --- | --- |
| `APP_PROFILE` | 기본 실행 환경: `local` 또는 `prod` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MariaDB 연결 정보 |
| `JWT_SECRET_KEY` | 최소 32바이트의 무작위 서명 비밀값 |
| `JWT_ACCESS_TOKEN_EXPIRATION`, `JWT_REFRESH_TOKEN_EXPIRATION` | 예: `15m`, `7d` |
| `CORS_ALLOWED_ORIGINS` | 정확한 출처를 쉼표로 구분. 예: `https://app.example.com,https://admin.example.com` |
| `JWT_COOKIE_SECURE`, `JWT_COOKIE_SAME_SITE` | 인증 및 CSRF 쿠키의 공통 정책 |
| `JPA_DDL_AUTO` | 로컬 기본 `validate`. 운영 프로필은 이 값과 무관하게 `validate` 고정 |
| `API_DOCS_ENABLED` | Swagger/OpenAPI 노출 여부 |

로컬 프로필은 `Secure=false`, Swagger 활성화를 기본으로 하고 운영 프로필은 `Secure=true`, Swagger 비활성화를 기본으로 합니다. `.env.prod.example`은 운영 설정 예시입니다. `SameSite=None`은 `Secure=true`일 때만 허용합니다. CORS는 자격 증명을 사용하므로 `*` 또는 경로가 있는 URL을 허용하지 않습니다.

실행 위치가 `backend/`이면 `./.env`, 저장소 루트이면 `./backend/.env`를 읽습니다. 두 위치에 파일이 모두 있으면 backend 파일이 우선합니다. 다른 위치는 OS 환경변수 `DOTENV_LOCATION=file:/absolute/path/.env[.properties]`로 지정할 수 있습니다. OS 환경변수와 명령행 설정은 파일보다 우선합니다.

실제 `.env`와 변형 파일은 Git에서 제외하고 `.env.example`, `.env.prod.example`만 버전 관리합니다. `test` 프로필의 H2와 JWT 값은 테스트 전용이며 실제 DB 설정을 요구하지 않습니다.

## 저장소와 스키마 규칙

- `save()`는 모든 도메인 저장소에서 신규 INSERT 용도입니다. 같은 ID의 기존 객체를 다시 넘겨 묵시적으로 수정하지 않습니다.
- `update()`는 기존 엔티티를 조회해 변경 가능한 필드만 반영합니다. 리프레시 토큰은 전용 `revoke()`로 폐기 시각을 변경합니다. 삭제 작업도 트랜잭션 안에서 수행합니다.
- 모든 할당 ID 엔티티는 `AssignedIdEntity`를 사용합니다. `@PostPersist`와 `@PostLoad`에서 신규 상태를 해제합니다.
- API 키 해시는 64자 `NOT NULL` 및 유니크 제약, 프로젝트 ID에는 조회 인덱스를 둡니다. 리프레시 토큰 해시는 43자 `NOT NULL` 및 유니크 제약이며 사용자 ID/만료 시각에 조회 인덱스를 둡니다.
- 사용자/프로젝트/API 키의 필수 컬럼과 문자열 길이를 도메인 검증에 맞추고, 생성 시각·소유 관계·키 해시는 수정 대상에서 제외합니다.
- 프로젝트와 API 키는 조회한 버전을 도메인에 보존해 변경 전 비교하고, JPA `@Version`으로 실제 동시 쓰기도 검사합니다.
- API 키는 발급 시 `lastUsedAt=null`입니다. 키와 계정 상태 검증을 통과한 인증에서만 사용 시각을 기록합니다. 전용 조건부 갱신은 활성 상태를 다시 확인하고 시간 역행을 막으며 버전도 증가시켜 오래된 객체의 덮어쓰기를 거부합니다. 사용 시각은 SDK 작업 완료 시각이 아니라 인증 성공 시각입니다.

스키마 변경은 `src/main/resources/db/migration`의 Flyway 버전 SQL로 관리합니다. 새 DB에는 완전한 V1을 적용하고, 기존 DB에는 `schemaCheck` 후 명시적 `schemaBaseline`이 필요합니다. JPA는 기본 `validate`이며 운영에서는 이를 고정합니다. 로컬 `update`는 버릴 수 있는 실험용 DB에서만 사용하고 공유 DB/배포에는 사용하지 않습니다. 적용된 V1은 수정하지 않고 다음 변경부터 V2 이상을 추가합니다. 상세 절차는 [DB 스키마 관리](database/README.md)에 정리했습니다.

API 키 서비스는 application 계층의 `IssueProjectApiKeyCommand`만 받습니다. 컨트롤러가 HTTP 요청 DTO를 명령으로 변환합니다. 기존 `/api/project-api-keys/...` 경로는 제공하지 않으므로 클라이언트도 `/api/v1/project-api-keys/...`로 변경해야 합니다.

## 코드 안내

### Run 도메인

실행 관련 코드는 기존 `project/project`, `project/key`와 같은 구조로 나눕니다.

- `run/run`: 실행 모델·상태 전이·생성/조회/완료/실패 서비스, 프로젝트 접근 정책과 기록 서비스
- `run/parameter`: 실행별 불변 파라미터. 같은 키/값의 재전송은 허용하며 값 변경은 거부합니다. 실행당 최대 100개입니다.
- `run/metric`: 유한한 숫자 메트릭. 음수 값도 허용하고 epoch/step/경과 시간은 음수를 허용하지 않습니다.
- `run/log`: 메시지·레벨·출처(`USER`, `STDOUT`, `STDERR`, `SDK`)를 가진 실행 로그
- `run/systemmetric`: CPU/GPU 사용률(0~100), RAM/GPU 메모리(바이트)의 스냅샷. 미수집 수치는 null, GPU 목록은 비어 있을 수 있습니다.
- `run/text`: `text()`용 이름·본문·선택 step을 가진 실험 텍스트 데이터
- `run/artifact`: 결과 파일의 이름·종류·저장 위치·예약/실제 크기·선택 SHA-256과 업로드 수명주기. `RunArtifactCommand`, 도메인, Mapper와 JPA 저장소를 분리합니다.

각 하위 패키지는 `domain`, `domain/repository`, `application`, `infrastructure/persistence`를 사용합니다. 저장 모델은 `AssignedIdEntity`를 사용하며 도메인의 `create/restore`, Mapper, JPA 저장소와 저장소 구현을 구분합니다. 기록은 추가 전용이며 일반 조회는 최대 1,000개로 제한합니다. 기존 묶음 조회는 처음/최근 limit개를 반환하며, 웹 시계열은 종류별 커서 API로 전체 기록을 탐색할 수 있습니다.

Artifact는 생성 시 `UPLOADING`이며 예약 크기와 실제 크기가 일치하고 업로드 기한 안에 검증되어야 `READY`로 전환합니다. 같은 크기/체크섬의 완료 재요청은 원래 업로드 시각을 유지하고, 다른 결과로 덮어쓰기는 거부합니다. 실패 시 `FAILED`, 삭제 요청 시 `DELETING`으로 전환합니다. 일반 파일 목록에는 `findReadyByRunId()`를 사용합니다. 전체 상태 조회는 `findByRunId()`이며 두 조회 모두 생성 시각/ID 오름차순, 최대 1,000개입니다. `save()`는 신규 INSERT, `update()`는 도메인 조회 버전과 JPA `@Version`을 검사하는 상태 변경입니다.

SDK는 실행 중인 Run의 `POST /api/v1/sdk/runs/{runId}/artifacts?name=...&kind=OTHER`로 바이너리를 전송합니다. Content-Length와 Idempotency-Key가 필요하며 성공 응답에는 artifactId가 들어 있습니다. 같은 요청 ID와 동일한 이름·종류·내용은 기존 ID를 반환합니다. 다운로드는 같은 프로젝트 키로 `GET /api/v1/sdk/runs/{runId}/artifacts/{artifactId}`를 호출하며 종료한 Run도 지원합니다.

파일은 백엔드 작업 디렉터리 기준 `./data/artifacts`에 저장합니다. `labflow.artifacts.directory`, `labflow.artifacts.max-file-bytes`(기본 1 GiB), `labflow.artifacts.max-project-bytes`(기본 20 GiB)로 설정합니다. 업로드는 짧은 트랜잭션에서 프로젝트·Run 잠금과 용량 예약 → 잠금 밖에서 `.part` 파일 스트리밍·SHA-256 계산·로컬 원자적 이름 변경 → 짧은 트랜잭션에서 소속·실행 상태·기한 재검증과 READY 확정으로 처리합니다. UPLOADING 예약 크기도 프로젝트 용량에 포함하므로 동시 업로드로 한도를 넘길 수 없습니다. 전송 중 같은 프로젝트의 다른 Run 기록과 Heartbeat는 진행할 수 있습니다.

동일 요청 ID가 아직 UPLOADING이면 409를 반환합니다. 일반 전송 실패는 파일과 예약을 제거하여 같은 ID로 재시도할 수 있습니다. 전송 중 프로젝트 삭제 또는 Run 종료가 먼저 확정되면 업로드 완료를 거부하고 파일·예약을 정리합니다. 비정상 종료로 남은 예약은 다음 업로드 예약 시 24시간 기한을 기준으로 정리합니다. 프로젝트 삭제 커밋 후에는 완료 파일과 임시 파일을 모두 삭제 시도합니다. DB 스키마 추가는 없습니다. 파일 삭제 자체가 실패하면 로그가 남으며 실제 MariaDB 잠금 및 파일시스템 장애는 별도 운영 검증이 필요합니다.

Python SDK의 로컬 보관 로그는 `POST .../runs/{runId}/logs`에 `X-Labflow-Replay: true`와 원래 `Idempotency-Key`를 함께 보내 복구합니다. 일반 로그와 같은 멱등성 작업으로 처리하여 응답 유실 후 복구도 중복 저장하지 않습니다. 복구에는 STDOUT/STDERR와 `recordedAt`이 필수이며 종료한 Run에서는 수집 시각이 종료 시각 이하여야 합니다. 인증·프로젝트 소속 검사는 그대로 적용하며 일반 종료 후 쓰기 금지는 유지합니다. `SdkRunHttpTests`와 실제 `PythonSdkIntegrationTests`에서 종료 후 복구·중복 제거를 확인합니다.

`RunService`와 `RunRecordService`는 인증이 완료된 `ProjectApiKeyPrincipal`을 받습니다. 실행의 소속 프로젝트와 소유자를 확인하므로 `PUBLIC` 프로젝트도 다른 프로젝트 키로 접근할 수 없습니다. 이는 SDK 전용 정책이며 일반 사용자용 공개 프로젝트 조회 정책은 별도로 구현해야 합니다. 실행 생성은 `RUNNING`, 종료는 `COMPLETED` 또는 `FAILED`입니다. 같은 완료 또는 같은 사유의 실패 재전송은 저장된 결과를 유지합니다. 다른 종료 결과로의 전환과 종료 후 기록 추가는 `409 RUN_STATE_CONFLICT`로 거부합니다. 없는 실행은 `404 RUN_NOT_FOUND`입니다.

`POST /api/v1/sdk/runs/{runId}/heartbeat`는 본문 없이 실행의 생존 시각을 서버 시계로 갱신합니다. 기존 프로젝트 접근 정책과 실행 잠금을 적용하며 종료된 실행에는 409를 반환합니다. SDK/웹 조회의 `lastHeartbeatAt`은 마지막 수신 시각이며 미수신 실행은 null입니다. 시간 역행으로 기존 생존 시각을 덮어쓰지 않습니다. 컬럼은 Flyway V1에 포함되며, 기존 DB 최초 편입 시 누락되었다면 `database/run-heartbeat.sql`을 검토해 보정합니다.

SDK 상세와 웹 목록·상세는 실행 상태와 별도로 `connectionStatus`를 반환합니다. 서버 시각으로 마지막 Heartbeat부터 기본 2분 이상 경과하면 `DISCONNECTED`입니다. 첫 신호가 없으면 시작 시각을 기준으로 하며 그 전까지는 `WAITING`, 최근 신호가 있으면 `CONNECTED`, 종료한 실행은 `INACTIVE`입니다. `RUN_HEARTBEAT_TIMEOUT` 환경변수 또는 `labflow.runs.heartbeat.timeout` 설정으로 양수 Duration(`2m`, `120s` 등)을 지정합니다. 웹은 기존 5초 주기 조회로 표시를 갱신합니다.

통신 단절은 프로세스 종료의 증거가 아니므로 **자동 FAILED 전환은 하지 않습니다**. 연결 끊김도 상태·집계에서는 `RUNNING`이며 종료 시각과 실패 사유는 비어 있습니다. 연결 상태는 조회 때 계산하므로 스케줄러나 추가 DB 마이그레이션은 필요 없습니다. 같은 Run에 Heartbeat가 다시 도착하면 `CONNECTED`로 복구되고 기록을 계속 받을 수 있습니다. SDK는 자동 stdout/stderr의 디스크 보관과 수동 재전송을 지원합니다. 명시적 지표 등 다른 기록의 디스크 큐와 프로세스 재시작 후 학습 재개는 제공하지 않습니다.

`POST /api/v1/projects/{projectId}/runs/{runId}/stop`은 본문 없이 로그인한 프로젝트 소유자가 Run을 수동 종료하는 웹 API입니다. 쿠키 인증과 CSRF가 필요하며 PUBLIC 프로젝트도 소유자만 종료할 수 있습니다. 프로젝트 → 실행 순서로 잠가 SDK의 기록·Heartbeat·종료 및 프로젝트 삭제와 직렬화합니다. `FAILED`, 서버 종료 시각, `Manually stopped by project owner` 사유를 저장합니다. 같은 수동 종료 재요청은 204와 기존 종료 시각을 유지하며 이미 완료됐거나 다른 사유로 실패한 Run에는 409, URL의 프로젝트에 속하지 않는 Run에는 404를 반환합니다. 클라이언트 프로세스를 원격으로 중지하는 기능은 아닙니다.

기록 배치는 한 트랜잭션이며 일부 값이 잘못되면 전체가 롤백됩니다. SDK 쓰기는 `Idempotency-Key`만 지원하며 SDK 0.4.0은 쓰기마다 UUID v4를 생성합니다. API 키 ID·HTTP 메서드·경로·키의 SHA-256 범위 해시를 공통 `idempotency_request` 테이블에서 관리합니다. 도메인 엔티티에는 멱등키를 저장하지 않습니다. JSON API는 헤더 선택, 파일 업로드는 필수이며 GET에서는 무시합니다. 키는 출력 가능한 ASCII 1~300자, 최초 선점부터 15일간 유효합니다.

동일 내용 재전송은 최초 상태·본문과 Location 등 응답 헤더를 재생합니다. 내용·쿼리 변경은 `422 IDEMPOTENCY_KEY_REUSED`, 처리 중 중복은 `409 IDEMPOTENT_REQUEST_PROCESSING`과 Retry-After 1입니다. API 키 교체는 별도 범위이므로 과거 요청을 새 키로 보내면 중복 저장될 수 있습니다. 인증·현재 접근 권한 검사는 응답 재생 전에 수행합니다.

요청 선점은 짧은 별도 트랜잭션이고 JSON 쓰기는 프로젝트 → 원장 → Run 순서로 잠급니다. 업무 기록·요약·성공 응답은 같은 트랜잭션으로 커밋합니다. 처리된 오류는 업무 롤백 후 최초 오류를 저장하며, 결과 미확정 중단은 503과 같은 키 재시도를 안내합니다. 2분의 복구 lease가 지나도 살아 있는 JSON 요청은 NOWAIT 행 잠금으로 보호합니다. 파일은 원장의 artifact_id로 예약을 연결하고 OS 파일 잠금으로 재개를 보호합니다. 스트리밍 중에는 DB 트랜잭션을 유지하지 않고 완료 메타데이터·201 응답·SHA-256을 짧은 트랜잭션으로 함께 저장합니다.

Flyway V3가 원장을 생성하고 이전 `sdk_request_entity` 및 Artifact의 `client_request_id`를 제거합니다. 실험·기록·파일은 보존하나 기존 멱등 기록은 이관하지 않습니다. 만료 원장은 1분마다 최대 1,000행을 지우고 프로젝트 삭제 때도 정리합니다. 서버 V3·SDK 0.4.0을 중지 배포로 함께 전환해야 하며, 기존 SDK는 호환되지 않습니다. 상세 절차와 DB 복원 요구 사항은 [DB 스키마 관리](database/README.md)를 참고하세요.

요청 명령은 HTTP DTO와 분리되어 있으며 `sdk/presentation/SdkRunController`가 DTO를 명령으로 변환해 서비스를 호출합니다. Python SDK는 `sdk/src/labflow`에 구현되어 있으며 API key 설정, Run 생성, `params/metric/log/text`, 완료·실패 및 상태 조회를 제공합니다. SDK 0.2.0부터 환경 메타데이터, stdout/stderr, CPU/RAM·선택 NVIDIA GPU 지표와 주기 Heartbeat를 자동 수집합니다. 설치·사용법과 실제 HTTP 연결 테스트는 [SDK README](../sdk/README.md)를 참고하세요. 클라이언트 기록 시각이 없으면 서버 시각을 사용하고 경과 시간은 초 단위입니다. 메타데이터 키는 100자/값은 2,000자, 파라미터 값은 2,000자, 로그 본문은 16,000자, 텍스트 본문은 65,536자, 실패 사유는 4,000자로 제한합니다.

현재 신규 설치는 Flyway V1으로 전체 스키마를 생성합니다. `database/runs.sql`은 과거 Run 부분 DDL이며 신규 설치 기준으로 사용하지 않습니다. 기존 DB의 최초 편입 절차와 데이터 보존 검증은 [DB 스키마 관리](database/README.md)를 따릅니다.

기존 `@Lob` 기본 매핑으로 생성한 MariaDB의 `run_text_entity.text`와 `run_log_entity.message`가 `TINYTEXT`라면 255바이트를 넘는 분류 보고서·로그가 저장되지 않습니다. 수정된 JPA 매핑은 배포 DDL과 동일하게 `LONGTEXT`를 명시합니다. V1은 두 열을 `LONGTEXT`로 생성합니다. 기존 DB 최초 편입 시에는 `database/expand-run-text-columns.sql`을 검토해 두 열을 확장하며 기존 기록은 보존합니다. API의 텍스트 65,536자·로그 16,000자 제한은 유지합니다. DB 용량 불일치(SQLState 22001)는 중복 데이터 충돌과 구분하여 `500 DATA_STORAGE_ERROR`로 응답합니다.

- `auth`: 회원가입/이메일 인증, 쿠키 로그인·갱신·로그아웃 및 JWT 인증
- `user`: 사용자 상태 전이, 권한과 프로필 저장
- `project/project`: 개인 프로젝트 CRUD, 공개 범위, 버전 검사 및 종속 데이터 삭제
- `project/key`: 프로젝트 API 키 발급과 SDK 인증
- `run`: 실험 실행 수명주기 및 파라미터·메트릭·로그·시스템 지표·텍스트 기록
- `sdk/presentation`: Bearer 키 전용 SDK HTTP API와 요청·응답 DTO
- `global`: 보안/MVC/OpenAPI 설정, 입력 검증, 예외 응답, ID 생성

각 Java 타입에는 역할을 설명하는 Javadoc을 추가했고, 주요 인증 판단·트랜잭션·필드 갱신에는 처리 의도를 설명하는 주석을 두었습니다.

### ADMIN API 테스트

`GET /api/v1/admin/api-test`는 ADMIN에게 현재 서버의 API 메서드·경로 목록을 반환합니다. 비로그인은 401, 일반 사용자는 403이며 DB의 현재 역할과 계정 상태를 적용합니다. `GET /api/v1/auth/me`의 role로 Studio 진입 링크를 표시하고 요청 전후에도 서버 권한을 재확인합니다. 일반 프로젝트·SDK API의 권한을 우회하지 않습니다. 이전 별도 프런트의 검사는 `AdminApiBrowserIntegrationTests`와 현재 Studio의 검사로 대체했습니다.
