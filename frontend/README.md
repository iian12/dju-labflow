# LabFlow Studio

Studio 디자인으로 모든 활성 화면을 실제 백엔드 API에 연결했습니다. 프로젝트·Run·지표·파라미터·로그·API 키에 샘플 데이터를 대신 표시하지 않습니다. API 테스트 도구도 이 앱에서 제공합니다.

## 실행

Java 21 백엔드를 먼저 실행하고, Node.js 24 이상에서 `frontend/start.cmd` 또는 `npm run dev`를 실행한 뒤 [Studio](http://127.0.0.1:5181/)를 엽니다. 기본 화면은 로그인입니다.

개발 서버는 `/api`를 `http://127.0.0.1:8080`으로 전달합니다. 다른 서버는 Vite 실행 전에 `LABFLOW_BACKEND_URL`을 지정하세요. 운영 배포에서도 동일 출처의 `/api` 경로를 백엔드로 전달해야 합니다. 회원가입 메일에는 백엔드의 메일 설정이 필요합니다. 키 목록·세션 확인·Run 요약 API를 추가했으므로 최신 백엔드를 함께 실행해야 합니다.

## 연결한 기능

| 화면 | 실제 API / 동작 |
| --- | --- |
| 회원가입·인증·로그인 | `/api/v1/auth/sign-up`, `email-verifications/verify`, `email-verifications/resend`, `login` |
| 로그인 유지·로그아웃 | `/api/v1/auth/me`, `csrf`, `refresh`, `logout`; HttpOnly 쿠키 사용 |
| 프로젝트 | `/api/v1/projects` 목록/생성, `/{id}` 상세/수정/삭제; 최신 version 전달 |
| Run 목록 | `/projects/{id}/runs`의 모든 페이지와 `/runs/summaries` 일괄 요약; 실제 지표 이름으로 선택·검색·필터·정렬 |
| 프로젝트 개요 | 실제 상태 집계와 `/runs/{runId}/metric-series` 곡선 |
| Run 비교 | `/runs/compare`; 2~4개 선택, 지표/기준 변경, 공통 Step 값, 차이, 다른 설정만 보기 |
| Run 상세 | 실제 상태·실패 사유·메타데이터·파라미터와 기록 종류별 커서 탐색 |
| API 키 | 실제 발급·페이지 목록·폐기; 원문은 발급 직후 해당 화면 메모리에만 보관 |
| Artifact 파일 | Run의 Artifacts 탭에서 SDK로 올린 파일을 페이지별로 조회하고 원본 이름으로 다운로드 |
| 기록 내보내기 | Artifacts 탭에서 각 기록 스트림을 끝까지 조회해 실제 전체 기록을 JSON 다운로드 |

PUBLIC 프로젝트 링크는 익명 조회가 가능하고 PRIVATE과 프로젝트 설정·키 관리는 소유자 권한을 적용합니다. 백엔드 오류 시 샘플로 대체하지 않으며 빈 데이터·권한 오류·연결 오류를 구분해 보여 줍니다. 키 발급·프로젝트 변경 같은 요청은 중복 클릭을 막고 성공 후 서버 데이터를 다시 조회합니다.

64비트 식별자와 큰 Step/메모리 값은 JSON 파싱 시 자릿수를 보존합니다. 공통 API 클라이언트가 CSRF와 한 번의 동시 세션 갱신을 처리합니다. 사용자나 프로젝트 변경 시 이전 요청을 취소하고 이전 화면 데이터를 숨깁니다. 서버 데이터는 localStorage에 보관하지 않습니다. 검색·정렬·선택·기준 Run 같은 탐색 설정만 사용자/프로젝트별로 보존하며 기존 데모 저장 데이터는 사용하지 않습니다.

## 검증

- `npm run typecheck`: 앱·테스트 strict TypeScript 검사
- `npm run build`: 타입 검사 및 프로덕션 빌드
- `npm test`: 인증·기록 커서·Heartbeat·Studio·관리자 API 테스트와 요청 클라이언트 검사 29개, 브라우저 검사는 Edge headless 사용
- 이전 샘플 화면 전용 탐색 테스트 파일은 디자인 참고용으로 보존했으며 현재 `npm test` 대상에서 제외했습니다.

실제 HTTP/H2 백엔드와 프런트 전체 흐름 검증(저장소 루트 PowerShell):

```powershell
$env:LABFLOW_STUDIO_NODE = (Get-Command node).Source
.\backend\gradlew.bat -p backend test --tests com.dju.labflow.StudioBrowserIntegrationTests
```

테스트는 전용 임시 포트의 백엔드와 Vite 서버에서 실제 로그인·토큰 갱신·프로젝트 생성/수정/삭제·API 키 발급/폐기·SDK 기록·Run 비교·개요·상세·전체 JSON 다운로드·공개 조회·로그아웃·모바일을 검증하고 테스트 계정/프로젝트를 정리합니다. 화면 캡처는 무시된 `frontend/test-results/`에 저장합니다. 운영 DB에는 연결하지 않습니다.

Design review guided by Unslop, created by Damir Sokolovsky.
https://github.com/heydamir/unslop — CC BY 4.0

## 실제 실험 기록 · 커서/최신 조회

상단 **실험 기록** 또는 `http://127.0.0.1:5181/#/records`로 진입합니다. 로그인한 사용자의 실제 프로젝트를 고르고 Run 목록에서 실험을 선택합니다. PUBLIC 프로젝트는 `ID로 실험 열기` 또는 직접 링크로 로그인 없이 조회할 수 있습니다. PRIVATE은 소유자 로그인 쿠키가 필요합니다.

직접 링크 형식은 `/#/records/{projectId}/{runId}/logs`이며 마지막 부분은 `metrics`, `logs`, `texts`, `system-metrics`입니다. 각 화면은 새 커서 API로 한 번에 200개의 실제 기록을 조회하고, **이전 기록 / 다음 기록 / 최신 기록 보기**를 제공합니다. Run ID·기록 ID·큰 step/메모리 값은 반올림하지 않습니다.

최신 보기에서는 5초마다 새로 조회합니다. 과거 페이지에서는 갱신을 멈추고 같은 위치를 유지하며, 최신 기록 보기로 돌아오면 갱신을 재개합니다. 기록은 시간/ID 오름차순이며 로그 최신 보기에서는 마지막 메시지를 따라갑니다. 로그를 위로 스크롤해 읽으면 자동 스크롤은 멈추며 최신 기록 보기로 다시 이동할 수 있습니다. 검색/출처 필터는 현재 페이지에 적용됩니다. 메트릭 곡선도 현재 페이지의 실제 값과 기록 순서를 사용합니다.

이 기능에는 커서 API를 포함한 백엔드가 필요합니다. 개발 프록시는 기존 `LABFLOW_BACKEND_URL` 설정을 사용합니다. 만료된 로그인은 쿠키와 CSRF로 한 번 갱신하며, 네트워크 오류·권한 부족·빈 기록을 구분해 표시합니다.

실제 서버와 브라우저 연동 검증(저장소 루트 PowerShell):

```powershell
# JAVA_HOME에 설치한 Java 21 경로를 설정한 환경에서 실행합니다.
$env:LABFLOW_RECORDS_NODE = (Get-Command node).Source
.\backend\gradlew.bat -p backend test --tests com.dju.labflow.RecordCursorBrowserIntegrationTests --no-daemon
```

임시 PRIVATE 프로젝트와 1,005개 로그를 만들고 실제 커서 탐색·새 기록 전송·자동 갱신·메트릭/텍스트/시스템 조회·모바일을 확인한 뒤 데이터를 정리합니다. 기존 `npm test`에도 모의 API를 이용한 커서/최신/권한/64비트 ID·모바일 테스트가 포함됩니다.

## 관리자 API 테스트

ADMIN 로그인 시 상단 **API 테스트** 또는 `/#/api-test`로 진입합니다. 현재 39개 API의 JSON·커서·파일 전송, 연결 컨텍스트, 요청·응답 기록과 마스킹을 제공합니다. 서버 권한 확인으로 일반 사용자와 비로그인 접근을 차단합니다. [관리자 API 테스트 안내](../docs/admin-api-workbench.md)에 사용법과 실제 HTTP/H2 통합 검증 명령을 정리했습니다.
