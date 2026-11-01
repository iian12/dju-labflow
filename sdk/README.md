# LabFlow Python SDK

Python 3.10 이상에서 동작하는 동기식 최소 SDK입니다. 현재 백엔드의 프로젝트 API key 전용 Run API를 사용합니다.

## 설치와 설정

```powershell
# 저장소 루트에서
python -m pip install -e ./sdk
$env:LABFLOW_API_KEY = '<발급한 프로젝트 키 원문>'
$env:LABFLOW_BASE_URL = 'http://localhost:8080'
```

이미 ACTIVE 계정, 개인 프로젝트와 프로젝트 API key가 있어야 합니다. Swagger에서 회원가입/인증/로그인 후 프로젝트와 키를 만들 수 있습니다. SDK는 사용자 로그인 쿠키와 CSRF를 사용하지 않습니다. 프로젝트는 키에서 결정되므로 SDK에 projectId를 지정하지 않습니다. `api_key`, `base_url` 인자가 환경변수보다 우선합니다. URL은 `/api/v1/sdk`를 붙이지 않은 백엔드 루트이며 배포 context path는 포함할 수 있습니다. 기본 URL은 `http://localhost:8080`, 타임아웃은 10초입니다.

## 기본 사용

```python
import labflow

with labflow.init("training", metadata={"framework": "pytorch"}) as run:
    labflow.params({"optimizer": "adam", "batch_size": 32, "learning_rate": 0.001})
    labflow.log("학습 시작")
    for step in range(3):
        labflow.metric({"loss": 1.0 / (step + 1), "accuracy": 0.8 + step * 0.05}, step=step)
    labflow.text("prediction", "고양이가 창가에 앉아 있다.", step=2)
```

정상적으로 with 블록을 마치면 완료 처리하고, 예외가 발생하면 실패 사유를 기록한 뒤 원래 예외를 다시 전달합니다. 실패 기록 자체가 실패해도 원래 실험 예외는 유지하고 경고를 남깁니다. 자동 실패 사유는 4,000 UTF-16 단위 이내로 자릅니다.

반환된 객체에도 같은 메서드가 있습니다. 명시적 종료를 원하면 다음처럼 사용합니다.

```python
run = labflow.init("manual-run", api_key="<project-api-key>")
run.params({"optimizer": "adam"})
run.metric({"loss": 0.125}, step=1)
run.log("checkpoint saved", level="INFO")
run.text("prediction", "cat", step=1)
run.finish()
# 실패 시에는 finish 대신 run.fail("out of memory")
```

- `metric()`은 숫자 mapping을 받고 문자열·bool·NaN·무한대는 거부합니다. step/epoch는 0 이상의 정수입니다. 경과 시간은 monotonic clock으로 계산하며 `elapsed_time`으로 직접 지정할 수도 있습니다.
- `params()`와 metadata의 값은 문자열·유한 숫자·bool을 허용하고 서버 계약에 맞춰 문자열로 변환합니다. bool은 `true/false`로 저장합니다. null이나 중첩 객체는 허용하지 않습니다.
- `log()`는 메시지·레벨·출처를 전송합니다. 기본값은 `INFO/USER`이며 `DEBUG/INFO/WARN/ERROR`, `USER/STDOUT/STDERR/SDK`를 지원합니다.
- `text()`는 이름과 실제 텍스트 데이터를 전송합니다. 로그/텍스트의 공백과 빈 문자열을 보존합니다.
- `recorded_at`은 선택적인 timezone-aware datetime입니다. 생략하면 서버 시각을 사용합니다. 숫자/텍스트 기록에는 선택적인 step을 지정할 수 있습니다.
- `finish()`/`fail()` 뒤에는 새 기록을 보낼 수 없습니다. Run 객체의 같은 완료/같은 실패 사유 재호출은 멱등적입니다. 모듈 함수는 활성 Run이 있어야 동작합니다.
- `labflow.init()`의 활성 Run은 현재 thread/async context에 저장됩니다. 같은 context에서 기존 Run을 종료하지 않고 다시 init하면 로컬 오류입니다. 동시 실험에는 명시적 Client/Run 객체를 사용합니다. SDK 자체는 동기식입니다.
- init으로 만든 Run은 종료 시 자체 HTTP 연결을 닫습니다. `close()`는 연결 해제이며 서버 Run을 완료/실패로 변경하지 않습니다.

## 명시적 Client와 상태 조회

```python
from labflow import Client

with Client() as client:
    with client.start_run("explicit") as run:
        run.metric({"loss": 0.1}, step=1)
    info = client.get_run(run.id)
    assert info["status"] == "COMPLETED"
```

Client가 만든 Run은 Client의 연결을 공유하며 개별 Run 종료가 Client를 닫지 않습니다. Run의 `state`는 로컬에서 성공 응답을 확인한 상태이며 서버의 최신 상태는 `get_run()`으로 확인합니다.

## 오류와 전송 정책

`AuthenticationError`(401), `PermissionDeniedError`(403), `NotFoundError`(404), `ConflictError`(409)는 `ApiError`를 상속하고 status_code/code/message/errors를 제공합니다. 연결/타임아웃은 `NetworkError`, 잘못된 응답은 `ProtocolError`, 클라이언트 입력 오류는 `ValidationError`입니다. 공통 기반은 `LabflowError`입니다. API key는 오류 메시지와 객체 repr에서 노출하지 않습니다.

쓰기 요청을 자동 재시도하지 않습니다. 타임아웃은 서버가 기록하지 않았다는 뜻이 아니며 다시 전송하면 중복 Run/기록이 생길 수 있습니다. 완료/실패 재호출도 응답이 유실된 경우에는 결과를 먼저 확인하세요. HTTP 리다이렉트는 따라가지 않으므로 최종 URL을 설정해야 합니다. 환경 프록시는 자동 적용하지 않으며 HTTPS 인증서 검증은 기본 활성화입니다.

배치/비동기 큐, 자동 stdout/stderr 캡처, CPU/GPU 자동 수집, 오프라인 저장 및 파일/데이터셋은 이번 범위에 포함하지 않습니다. `system` 추가 의존성은 후속 수집 기능을 위한 선택 설치 항목입니다.

## 개발과 검증

```powershell
python -m pip install -e './sdk[dev]'
cd sdk
python -m pytest -q
python -m ruff check .
python -m ruff format --check .
python -m build
```

실제 HTTP 연결 테스트는 Java 21과 SDK 개발 의존성이 설치된 Python이 필요합니다. 저장소 루트에서 실행합니다.

```powershell
$env:LABFLOW_PYTHON = (Resolve-Path './sdk/.venv/Scripts/python.exe').Path
cd backend
.\gradlew.bat spotlessCheck test --tests com.dju.labflow.PythonSdkIntegrationTests --rerun-tasks --no-daemon
```

`LABFLOW_PYTHON`은 위에서 SDK를 설치한 Python 실행 파일의 절대 경로로 지정하세요. 이 테스트는 `test` 프로필로 임의 포트의 실제 Spring Boot HTTP 서버와 H2를 실행하고, 임시 ACTIVE 사용자·PRIVATE 프로젝트·키를 생성합니다. Python의 연결 테스트 4개를 실행한 뒤 Java 저장소에서 파라미터·메트릭·로그·텍스트·종료 상태의 저장 결과를 검증하고 임시 데이터를 삭제합니다. 실 DB와 Gmail 설정은 사용하지 않습니다.

일반 `pytest`에서는 연결 테스트 4개를 건너뛰고, 일반 백엔드 테스트에서도 `LABFLOW_PYTHON`을 지정하지 않으면 이 테스트를 건너뜁니다. 직접 연결 테스트를 실행하려면 폐기 가능한 테스트 서버의 `LABFLOW_TEST_BACKEND_URL`, 그 서버의 두 다른 프로젝트에 발급한 `LABFLOW_TEST_API_KEY`, `LABFLOW_TEST_OTHER_API_KEY`를 모두 지정하고 `python -m pytest -q tests/integration/test_backend.py`를 실행합니다. 직접 실행한 Python 테스트는 서버에 만든 실험을 남깁니다. 이 테스트 전용 변수는 SDK의 일반 환경변수와 분리되어 있습니다.
