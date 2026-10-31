# LabFlow Backend

Java 21 / Spring Boot 4.1 기반 백엔드입니다. 도메인과 JPA 엔티티를 분리하고 서비스에서 트랜잭션을 관리합니다.

## 검증

```powershell
.\gradlew.bat build
```

테스트는 `test` 프로필의 메모리 H2를 사용하므로 별도 MariaDB가 필요하지 않습니다. 운영 DB 설정은 변경하지 않습니다. H2 회귀 테스트는 인증, MVC, 영속성 변경을 확인하며 MariaDB 고유의 잠금/동시성 동작까지 보장하지는 않습니다.

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
- MVP의 프로젝트는 개인 소유이며 공개 범위는 `PUBLIC`과 `PRIVATE`만 지원합니다. 팀 관리와 `TEAM` 공개 범위는 추후 구현합니다. 공개 범위별 조회 API와 인가 정책은 아직 구현되어 있지 않습니다.
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
| POST | `/api/v1/auth/sign-up` | 미인증 회원 생성 및 코드 발송 요청 (201) |
| POST | `/api/v1/auth/email-verifications/verify` | 코드 검증 후 계정 활성화 (204) |
| POST | `/api/v1/auth/email-verifications/resend` | 코드 재발송 요청 (202) |
| POST | `/api/v1/project-api-keys/issue/{projectId}` | 프로젝트 소유자의 API 키 발급 |
| POST | `/api/v1/sdk/runs` | 실행 생성. ID와 Location 반환 (201) |
| GET | `/api/v1/sdk/runs/{runId}` | 실행 상태·메타데이터 조회 (200) |
| PUT | `/api/v1/sdk/runs/{runId}/parameters` | 불변 파라미터 추가, 같은 키/값 재전송 허용 (204) |
| POST | `/api/v1/sdk/runs/{runId}/metrics` | 숫자 메트릭 배치 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/logs` | 출처·레벨별 실행 메시지 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/texts` | 모델 입력·출력 등의 텍스트 데이터 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/system-metrics` | CPU/RAM/GPU 측정 스냅샷 기록 (204) |
| POST | `/api/v1/sdk/runs/{runId}/complete` | 실행 완료, 본문 없음 (204) |
| POST | `/api/v1/sdk/runs/{runId}/fail` | 실패 사유와 함께 종료 (204) |

로그인·토큰 갱신·프로젝트 생성은 서비스 계층만 구현되어 있으므로 아직 HTTP API로 호출할 수 없습니다.

### SDK Run API

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

1. `POST /api/v1/auth/sign-up`에 `email`, `password`, `confirmPassword`, `name`을 보냅니다.
2. 이메일로 받은 6자리 영문/숫자 코드를 입력합니다. I/L/O/0/1은 생성하지 않습니다.
3. `GET /api/v1/auth/csrf`로 CSRF 쿠키를 받은 뒤, 쿠키와 `X-XSRF-TOKEN` 헤더를 함께 전송합니다.
4. `POST /api/v1/auth/email-verifications/verify`에 `{"email":"user@example.com","code":"A2B3C4"}`를 보냅니다. 성공하면 계정이 ACTIVE로 바뀝니다. 자동 로그인하지 않습니다.
5. 미수신 시 `POST /api/v1/auth/email-verifications/resend`에 `{"email":"user@example.com"}`를 보냅니다. 이 요청에도 CSRF가 필요합니다.

코드는 10분간 유효하고 대소문자를 구분하지 않으며 앞뒤 공백을 제거합니다. 실패 5회 후 잠기며, 재발송하면 이전 코드는 무효화됩니다. 재발송은 60초 간격이고 최초 발송을 포함해 계정당 시간당 5회로 제한합니다. 가입/재발송은 IP당 시간당 합계 10회, 검증은 100회입니다. IP 제한은 프로세스 메모리에 있으므로 재시작 시 초기화되며 여러 서버를 운영할 때는 게이트웨이 등에서 공통 제한이 필요합니다. 프록시 환경에서는 신뢰하는 프록시만 원격 주소를 설정하도록 구성해야 합니다.

검증 실패는 400, IP 제한 초과는 429입니다. 재발송은 계정 존재·상태 및 계정별 제한 여부를 노출하지 않고 항상 202를 반환합니다. 202는 메일 도착을 보장하지 않습니다.

`.env`에 `EMAIL_VERIFICATION_SECRET`(JWT 키와 별개의 임의 값, 최소 32바이트), `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`을 설정합니다. 예시 파일은 SMTP 587/STARTTLS 기준입니다. 비밀키 누락 시 회원가입 트랜잭션은 실패하며 저장되지 않습니다. SMTP 설정 누락/발송 실패 시에는 가입 정보가 유지되고 서버에 원문 없는 오류 로그가 남습니다.

사용자와 코드 HMAC은 같은 트랜잭션에 저장하며 메일은 커밋 후 발송합니다. 현재 발송은 요청 스레드에서 수행되며 SMTP 타임아웃은 각각 5초입니다. 발송 실패 시 사용자가 재발송해야 합니다. 메모리 이벤트이므로 커밋 직후 서버가 종료되면 메일이 유실될 수 있으며, 자동 재시도/전달 보장이 필요하면 영속 발송 큐를 추가해야 합니다. 인증 코드는 로그와 API 응답에 포함하지 않습니다.

기존 DB에는 `database/email-verifications.sql`의 테이블을 먼저 적용해야 합니다. 이 스크립트는 자동 실행되지 않습니다. 테스트에서는 H2가 테이블을 생성합니다.

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
2. 브라우저에 유효한 `access_token` 쿠키가 있어야 합니다. 현재 로그인 HTTP API가 없으므로 Swagger만으로 로그인할 수는 없습니다. 인증 연동은 테스트에서 검증합니다.
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
| `JPA_DDL_AUTO` | 기본 `validate`. 스키마가 준비되어 있어야 실행 가능 |
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

JPA 매핑은 스키마 정의이며 기존 DB를 자동 마이그레이션하지 않습니다. 새 로컬 DB의 초기 생성에 Hibernate를 사용할 경우 `.env`의 `JPA_DDL_AUTO=update`를 명시할 수 있습니다. 운영에서는 별도 DDL/마이그레이션으로 제약과 인덱스를 반영하고 `validate`를 유지하세요.

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

각 하위 패키지는 `domain`, `domain/repository`, `application`, `infrastructure/persistence`를 사용합니다. 저장 모델은 `AssignedIdEntity`를 사용하며 도메인의 `create/restore`, Mapper, JPA 저장소와 저장소 구현을 구분합니다. 기록은 추가 전용이며 일반 조회는 최대 1,000개로 제한합니다. 기록 시각/ID 순으로 첫 limit개를 반환하며 커서 페이지네이션은 아직 제공하지 않습니다.

`RunService`와 `RunRecordService`는 인증이 완료된 `ProjectApiKeyPrincipal`을 받습니다. 실행의 소속 프로젝트와 소유자를 확인하므로 `PUBLIC` 프로젝트도 다른 프로젝트 키로 접근할 수 없습니다. 이는 SDK 전용 정책이며 일반 사용자용 공개 프로젝트 조회 정책은 별도로 구현해야 합니다. 실행 생성은 `RUNNING`, 종료는 `COMPLETED` 또는 `FAILED`입니다. 같은 완료 또는 같은 사유의 실패 재전송은 저장된 결과를 유지합니다. 다른 종료 결과로의 전환과 종료 후 기록 추가는 `409 RUN_STATE_CONFLICT`로 거부합니다. 없는 실행은 `404 RUN_NOT_FOUND`입니다.

기록 추가와 종료는 같은 실행 행을 서비스 트랜잭션에서 잠가 직렬화합니다. 저장소의 잠금 조회는 활성 트랜잭션을 요구합니다. 실행 상태 갱신은 도메인의 조회 버전도 검사해 오래된 객체가 종료 상태를 덮어쓰지 못하게 합니다. 기록 배치는 한 트랜잭션이며 일부 값이 잘못되면 전체가 롤백됩니다. 메트릭·로그·텍스트·시스템 지표의 재전송은 새 기록을 추가하므로 자동 재시도/중복 제거는 추후 SDK 계약에서 정해야 합니다.

요청 명령은 HTTP DTO와 분리되어 있으며 `sdk/presentation/SdkRunController`가 DTO를 명령으로 변환해 서비스를 호출합니다. Python SDK의 `log/metric/text` 메서드는 아직 구현하지 않았습니다. 클라이언트 기록 시각이 없으면 서버 시각을 사용하고 경과 시간은 초 단위입니다. 메타데이터 키는 100자/값은 2,000자, 파라미터 값은 2,000자, 로그 본문은 16,000자, 텍스트 본문은 65,536자, 실패 사유는 4,000자로 제한합니다.

기존 DB에는 `database/runs.sql`을 먼저 적용해야 합니다. 이 DDL은 자동 실행되지 않으며 테스트에서는 H2가 새 테이블을 생성합니다. 실제 MariaDB 마이그레이션 실행은 별도 배포 단계에서 수행합니다.

- `auth`: 비밀번호 로그인, 토큰 생성/회전, 쿠키/JWT 인증
- `user`: 사용자 상태 전이, 권한과 프로필 저장
- `project/project`: 프로젝트 생성 및 저장 모델
- `project/key`: 프로젝트 API 키 발급과 SDK 인증
- `run`: 실험 실행 수명주기 및 파라미터·메트릭·로그·시스템 지표·텍스트 기록
- `sdk/presentation`: Bearer 키 전용 SDK HTTP API와 요청·응답 DTO
- `global`: 보안/MVC/OpenAPI 설정, 입력 검증, 예외 응답, ID 생성

각 Java 타입에는 역할을 설명하는 Javadoc을 추가했고, 주요 인증 판단·트랜잭션·필드 갱신에는 처리 의도를 설명하는 주석을 두었습니다.
