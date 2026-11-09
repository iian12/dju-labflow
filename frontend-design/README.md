# LabFlow 로컬 테스트 프론트

`frontend/`와 분리된 React + TypeScript + Vite 앱입니다. 실제 백엔드와 연결해 일반 사용자 흐름을 테스트합니다. 이 폴더와 `sdk_test/`는 `test` 브랜치에서 관리하며 `main`에는 포함하지 않습니다. 의존성, 빌드 결과, 화면 캡처 및 배포용 로컬 상태는 Git에서 제외합니다.

## 실행

Node.js 24, Java 21, 실행 중인 백엔드와 DB가 필요합니다.

```powershell
# 터미널 1: 기존 DB/JWT/SMTP 설정 사용
cd backend
.\gradlew.bat bootRun
```

```powershell
# 터미널 2
cd frontend-design
npm ci
npm run dev
```

- 실제 프론트: http://127.0.0.1:5174/
- 회원가입: http://127.0.0.1:5174/#/signup
- API 테스트 도구: http://127.0.0.1:5174/#/api-test

기본 백엔드는 `http://127.0.0.1:8080`입니다. 다른 주소는 dev 서버 시작 전에 `$env:LABFLOW_BACKEND_URL = 'http://127.0.0.1:다른포트'`로 설정합니다. Vite 프록시가 `/api`, `/v3/api-docs`, `/swagger-ui`를 연결하므로 브라우저는 같은 출처로 요청합니다. 변경한 백엔드 조회 API를 적용하려면 백엔드도 재시작해야 합니다. 정적 `dist`와 `npm run preview`에는 개발 프록시가 없으므로 테스트는 `npm run dev`로 진행하세요.

## 일반 사용자 흐름

1. 회원가입 → 받은 이메일 코드 인증 → 로그인합니다. 인증 화면에서 재발송할 수도 있습니다. 실제 SMTP는 백엔드 설정을 사용합니다.
2. 새 프로젝트를 만들고 PRIVATE/PUBLIC을 선택합니다. 프로젝트 목록과 검색, 상세, 정보 수정, 전체 삭제를 제공합니다. 삭제는 확인 후 모든 소속 실험·기록·키를 함께 제거합니다.
3. 프로젝트의 API Keys에서 키 이름을 정해 발급하고 복사합니다. 원문은 현재 화면 메모리에만 보관합니다. 발급한 키 원문을 서버에서 재조회하거나 전체 키 목록·폐기하는 API는 아직 없습니다.
4. 저장소 루트의 PowerShell에서 실제 학습 예제를 실행합니다.

```powershell
.\sdk\.venv\Scripts\python.exe .\sdk\examples\train_digits.py
```

필요한 모델 의존성은 `python -m pip install -e './sdk[examples]'`로 설치합니다. 키 입력창에 같은 프로젝트의 API key를 붙여넣으세요. SDK 설정은 [SDK README](../sdk/README.md)를 참고합니다.

5. 해당 프로젝트의 Runs에서 실제 실행을 엽니다. 실행 중에는 목록·상태·기록을 5초마다 갱신합니다. 탭이 숨겨져 있으면 주기 갱신을 쉬고, 새로고침 버튼으로도 조회할 수 있습니다.

## 실험 화면

- Overview: 프로젝트 전체 실행 수·상태별 집계, 프로젝트 정보와 최근 실행
- Runs: 실제 실행 목록, 상태·소요 시간·시작 시각, 서버 페이지네이션
- Metrics: 모든 사용자 정의 metric의 실제 값, Step/Epoch/Elapsed Time 축 선택, 호버·키보드 원본 값 확인
- Parameters: 서버에 저장한 key/value
- Logs: 메시지·레벨·출처·시각, 검색과 출처 필터
- Texts: 입력/출력 샘플과 분류 보고서 등의 원문, 선택 step과 시각
- System: 저장된 CPU/RAM/GPU 스냅샷. 최소 SDK는 이를 자동 수집하지 않으므로 기록이 없으면 빈 상태를 표시합니다.
- Run Overview: SDK가 보낸 메타데이터, 종료 상태와 실패 사유

데모 수치나 예시 Run을 일반 화면에 섞지 않습니다. 빈 데이터·읽는 중·오류를 구분합니다. 기록 조회는 각 시계열의 처음 최대 1000개이며 상한에 도달하면 화면에 표시합니다. 커서 페이지네이션은 후속 작업입니다. Java Long ID는 정밀도를 유지합니다. 차트로 정확히 표현할 수 없는 매우 큰 step은 반올림하지 않고 해당 축에서 제외합니다.

PUBLIC 프로젝트는 링크를 가진 비로그인 사용자도 모든 실행과 기록을 읽을 수 있습니다. PRIVATE은 소유자만 조회합니다. 관리·키 발급 버튼은 로그인한 사용자의 개인 프로젝트에만 표시합니다.

## 인증과 데이터

사용자 API는 HttpOnly 로그인 쿠키와 CSRF를 사용합니다. 만료 시 refresh 요청을 하나로 합친 뒤 인증 실패한 요청만 한 번 재시도합니다. 네트워크 오류의 쓰기 요청은 자동 재시도하지 않습니다. 프로젝트 수정·삭제에는 최신 version을 보내며 충돌은 사용자에게 표시합니다.

비밀번호·API key는 localStorage/sessionStorage에 저장하지 않습니다. 새로고침하거나 API 테스트 도구로 이동하면 현재 화면의 키 원문이 사라집니다. 로그인 쿠키는 브라우저가 만료/로그아웃까지 관리합니다.

API 테스트 도구는 현재 25개 업무 API의 폼·원본 JSON 편집·응답·요청 기록·성공 확인 기능을 유지합니다. 프로젝트 생성/상세의 ID·version과 발급 키를 다음 호출에 자동 연결하며, SDK 요청은 사용자 쿠키 없이 Bearer 키만 사용합니다. 도구에서는 오류를 그대로 확인하기 위해 자동 토큰 갱신을 하지 않습니다.

## 검증

```powershell
cd frontend-design
npm test
npm run build
```

단위 테스트는 데이터 정밀도·차트 변환·누락/중복 값·CSRF·인증 분리·refresh 합치기·오류 정책을 확인하고 화면 카탈로그를 전체 Controller 경로와 비교합니다.

실제 브라우저 + 실제 HTTP + H2 통합 테스트:

```powershell
# frontend-design에서 최초 1회
npx playwright install chromium
# 저장소 루트에서
$env:LABFLOW_FRONTEND_NODE = (Get-Command node).Source
cd backend
.\gradlew.bat test --tests com.dju.labflow.FrontendBrowserIntegrationTests --rerun-tasks --no-daemon
```

임의 포트의 별도 Vite와 테스트 백엔드를 사용해 현재 서버와 분리합니다. 테스트용 사용자로 25개 API, 일반 프론트의 로그인·프로젝트 CRUD·키 발급·실험 차트/파라미터/로그/텍스트/시스템·PUBLIC 비로그인 읽기·로그아웃·모바일을 실제 Chromium에서 확인합니다. 이메일 전송만 대체하여 실제 Gmail로 보내지 않습니다. 결과 로그는 `backend/build/frontend-browser-integration.log`, 캡처는 `frontend-design/test-results/`에 생성합니다.
