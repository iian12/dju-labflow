# LabFlow Backend

Java 21 / Spring Boot 4.1 기반 백엔드입니다. 도메인과 JPA 엔티티를 분리하고 서비스에서 트랜잭션을 관리합니다.

## 검증

```powershell
.\gradlew.bat build
```

테스트는 `test` 프로필의 메모리 H2를 사용하므로 별도 MariaDB가 필요하지 않습니다. 운영 DB 설정은 변경하지 않습니다. H2 회귀 테스트는 인증, MVC, 영속성 변경을 확인하며 MariaDB 고유의 잠금/동시성 동작까지 보장하지는 않습니다.

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

## Swagger / OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

Spring Boot 4.1과 호환되는 `springdoc-openapi-starter-webmvc-ui:3.2.0`을 사용합니다. [호환성 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)

현재 HTTP로 구현된 API만 Swagger에 표시됩니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | CSRF 쿠키 초기화. 로그인 불필요 |
| POST | `/api/v1/project-api-keys/issue/{projectId}` | 프로젝트 소유자의 API 키 발급 |

회원가입·로그인·토큰 갱신·프로젝트 생성은 서비스 계층만 구현되어 있으므로 아직 HTTP API로 호출할 수 없습니다. 이메일 인증 API도 아직 구현되지 않았습니다. 이 문서에서는 존재하지 않는 엔드포인트를 제공하는 것처럼 표시하지 않습니다.

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
| 404 | 프로젝트/사용자 없음 |
| 409 | 이메일 중복 또는 동시 저장 제약 충돌 |

오류 응답에 비밀번호, 토큰 원문, DB 오류 메시지를 넣지 않습니다. CSRF 검증은 인증 필터보다 앞서므로 CSRF가 빠진 변경 요청은 인증 상태와 무관하게 먼저 403이 될 수 있습니다.

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

JPA 매핑은 스키마 정의이며 기존 DB를 자동 마이그레이션하지 않습니다. 새 로컬 DB의 초기 생성에 Hibernate를 사용할 경우 `.env`의 `JPA_DDL_AUTO=update`를 명시할 수 있습니다. 운영에서는 별도 DDL/마이그레이션으로 제약과 인덱스를 반영하고 `validate`를 유지하세요.

API 키 서비스는 application 계층의 `IssueProjectApiKeyCommand`만 받습니다. 컨트롤러가 HTTP 요청 DTO를 명령으로 변환합니다. 기존 `/api/project-api-keys/...` 경로는 제공하지 않으므로 클라이언트도 `/api/v1/project-api-keys/...`로 변경해야 합니다.

## 코드 안내

- `auth`: 비밀번호 로그인, 토큰 생성/회전, 쿠키/JWT 인증
- `user`: 사용자 상태 전이, 권한과 프로필 저장
- `project/project`: 프로젝트 생성 및 저장 모델
- `project/key`: 프로젝트 API 키 발급과 SDK 인증
- `global`: 보안/MVC/OpenAPI 설정, 입력 검증, 예외 응답, ID 생성

각 Java 타입에는 역할을 설명하는 Javadoc을 추가했고, 주요 인증 판단·트랜잭션·필드 갱신에는 처리 의도를 설명하는 주석을 두었습니다.
