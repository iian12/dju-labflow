# LabFlow Python SDK

Python 3.10 이상에서 동작하는 Python SDK입니다. 수동 기록은 동기식이며 자동 수집은 백그라운드에서 동작합니다. 현재 백엔드의 프로젝트 API key 전용 Run API를 사용합니다.

## 설치와 설정

다른 프로젝트에서는 해당 프로젝트의 가상환경을 활성화한 뒤 GitHub에서 SDK를 설치합니다. Python 3.10 이상과 Git이 필요합니다. 설치 패키지 이름은 `labflow-sdk`, Python import 이름은 `labflow`입니다.

```powershell
python -m pip install "labflow-sdk @ git+https://github.com/iian12/dju-labflow.git@main#subdirectory=sdk"
```

기존 Git 설치를 최신 버전으로 갱신하려면 `python -m pip install --upgrade "labflow-sdk @ git+https://github.com/iian12/dju-labflow.git@main#subdirectory=sdk"`를 사용합니다. 같은 설치 문자열을 `requirements.txt`에 한 줄로 적어도 됩니다. 재현 가능한 실험에는 `@main` 대신 사용할 커밋의 전체 SHA를 고정하세요.

실제 학습 예제용 NumPy/scikit-learn도 설치하려면 패키지 이름에 `[examples]`를 붙입니다.

```powershell
python -m pip install "labflow-sdk[examples] @ git+https://github.com/iian12/dju-labflow.git@main#subdirectory=sdk"
```

예제 스크립트는 저장소의 `sdk/examples/train_digits.py`에 있습니다. 위 명령은 SDK와 모델 의존성을 설치하며 예제 스크립트 자체를 현재 프로젝트에 복사하지는 않습니다. 다른 모델 코드에서는 `import labflow` 후 아래의 기본 사용처럼 기록하면 됩니다.

이 저장소에서 SDK를 개발할 때는 editable 설치를 사용합니다.

```powershell
# 저장소 루트에서
python -m pip install -e ./sdk
$env:LABFLOW_API_KEY = '<발급한 프로젝트 키 원문>'
$env:LABFLOW_BASE_URL = 'http://localhost:8080'
```

이미 ACTIVE 계정, 개인 프로젝트와 프로젝트 API key가 있어야 합니다. Swagger에서 회원가입/인증/로그인 후 프로젝트와 키를 만들 수 있습니다. SDK는 사용자 로그인 쿠키와 CSRF를 사용하지 않습니다. 프로젝트는 키에서 결정되므로 SDK에 projectId를 지정하지 않습니다. `api_key`, `base_url` 인자가 환경변수보다 우선합니다. URL은 `/api/v1/sdk`를 붙이지 않은 백엔드 루트이며 배포 context path는 포함할 수 있습니다. 기본 URL은 `http://localhost:8080`, 타임아웃은 10초입니다.

다른 프로젝트에서도 `LABFLOW_API_KEY`와 `LABFLOW_BASE_URL`을 설정하세요. 다른 컴퓨터에서 실행할 때는 그 컴퓨터에서 접근할 수 있는 백엔드 URL을 지정해야 합니다. `localhost`는 Python을 실행하는 컴퓨터 자신을 가리킵니다. API key는 SDK 패키지나 Git 저장소에 포함하지 않습니다.

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

## 환경 정보·출력·시스템 지표·Heartbeat 자동 수집

`labflow.init()`과 `Client.start_run()`은 기본으로 다음 항목을 수집합니다.

| 항목 | 기본 동작 |
| --- | --- |
| 환경 정보 | 생성 요청의 metadata에 OS/OS 버전, 아키텍처, Python 버전/구현, SDK 버전 기록 |
| stdout/stderr | 터미널에 그대로 출력하면서 로그 API로 전송. 출처는 STDOUT/STDERR, 레벨은 INFO/ERROR |
| System Metrics | 시작 후 CPU 사용률(0~100), 호스트 RAM 사용량/전체(바이트), 이후 10초 간격 |
| Heartbeat | 시작 후 서버에 생존 신호 전송, 이후 30초 간격. 조회 응답의 `lastHeartbeatAt`으로 확인 |

환경 정보 키는 `system.os`, `system.os_version`, `system.architecture`, `system.python_version`, `system.python_implementation`, `system.sdk_version`입니다. 사용자 metadata와 합치며 같은 키는 사용자 값이 우선합니다. 합계 100개 제한을 적용합니다. 환경변수 전체, API key, 설치 패키지 목록은 수집하지 않습니다.

CPU/RAM 수집용 `psutil`은 기본 의존성입니다. NVIDIA GPU 사용률·메모리도 수집하려면 `python -m pip install -e './sdk[system]'`으로 NVML 바인딩을 추가합니다. GPU/드라이버가 없거나 권한이 없는 환경에서는 CPU/RAM 수집을 계속하고 GPU 목록은 비워 둡니다. 장치가 일부 측정값을 지원하지 않으면 해당 값만 생략합니다.

```python
import labflow

with labflow.init(
    "automatic-tracking",
    system_metrics_interval=5.0,
    heartbeat_interval=15.0,
) as run:
    print("이 출력은 STDOUT 로그에도 저장됩니다")
    # 학습 코드...
    labflow.heartbeat()  # 자동 주기와 별도로 즉시 전송할 수도 있습니다.
    print(run.telemetry_status)  # errors, dropped_logs, last_error
```

`Client(...)`에도 같은 옵션을 전달할 수 있습니다. 기능별로 끄려면 `collect_environment=False`, `capture_output=False`, `system_metrics_interval=None`, `heartbeat_interval=None`을 지정합니다. 간격은 양의 유한한 초 단위 값입니다. 자동 수집 실패는 학습 예외를 발생시키지 않으며 최초 실패 시 경고하고 `run.telemetry_status`에 누적 오류 수와 최근 오류 타입을 제공합니다. 수동 `heartbeat()`는 다른 수동 API처럼 오류를 호출자에게 전달합니다.

출력 캡처는 Python의 `sys.stdout`/`sys.stderr` 텍스트 쓰기를 대상으로 합니다. 줄바꿈·명시적 flush·Run 종료 때 전송하며 긴 줄은 서버의 16,000 UTF-16 단위 제한에 맞게 분할합니다. 원래 터미널 출력은 유지하고 전송본의 현재 API key는 가립니다. C 확장/하위 프로세스의 파일 디스크립터 직접 출력과 이미 다른 스트림에 연결된 logging 핸들러는 캡처하지 않습니다. 중첩 Run은 현재 컨텍스트에 해당하는 Run에 한 번 기록하고, 컨텍스트가 없는 다른 스레드의 출력은 가장 최근 활성 Run에 기록합니다.

출력 전송과 주기 수집은 Run별 백그라운드 스레드에서 수행합니다. 로그 큐는 1,000개로 제한하며 초과한 새 로그는 버리고 `dropped_logs`에 집계합니다. 기본 설정에서는 전송 실패한 로그/메트릭을 재전송하지 않습니다. `max_retries`를 설정하면 같은 요청 ID와 원래 본문으로 제한 횟수만큼 재시도하며, 다음 주기의 측정/Heartbeat는 새 요청 ID를 가진 새 관측값입니다. 네트워크 지연 동안에도 학습 출력은 계속됩니다. `finish()`/`fail()`은 수집을 중지하고 남은 로그를 전송한 뒤 종료 API를 호출합니다. 따라서 종료 시에는 미전송 로그의 HTTP 처리 시간만큼 기다릴 수 있습니다. 종료 요청이 실패한 뒤에는 자동 수집이 정지된 상태를 유지하므로 서버 상태를 먼저 확인하세요.

`run.close()` 또는 `client.close()`도 출력과 수집 스레드를 정리하지만 서버 실행을 종료 상태로 바꾸지는 않습니다. with 문이나 명시적 종료/close를 사용하세요. 프로세스 강제 종료 시 미전송 로그는 유실될 수 있습니다. Heartbeat 미수신은 조회 시각으로 확인할 수 있으며 자동 FAILED 전환/서버 타임아웃 판정은 제공하지 않습니다.

서버에는 `POST /api/v1/sdk/runs/{runId}/heartbeat`가 필요합니다. 기존 DB를 사용하는 서버는 배포 전에 [Heartbeat DDL](../backend/database/run-heartbeat.sql)을 한 번 적용해야 합니다. SDK/웹 상세 조회에 `lastHeartbeatAt`이 추가되며 미수신 실행은 null입니다.

## 실제 모델 학습으로 사용해 보기

`examples/train_digits.py`는 scikit-learn의 [digits 데이터셋](https://scikit-learn.org/stable/modules/generated/sklearn.datasets.load_digits.html)으로 [SGDClassifier](https://scikit-learn.org/stable/modules/generated/sklearn.linear_model.SGDClassifier.html)를 실제 학습합니다. 8×8 손글씨 숫자를 0~9로 분류하는 CPU 예제이며 데이터셋은 패키지에 포함되어 있습니다. GPU나 별도의 데이터 다운로드가 필요하지 않습니다.

1. 백엔드를 실행하고 [API 테스트 화면](http://127.0.0.1:5174/#/api-test/key-issue)에서 개인 프로젝트의 API key를 발급하거나 기존 키를 준비합니다.
2. 저장소 루트에서 아래 명령을 실행합니다. 이미 만든 가상환경을 사용하며, 새 환경이면 먼저 `python -m venv sdk/.venv`를 실행합니다.

```powershell
& .\sdk\.venv\Scripts\python.exe -m pip install -e './sdk[examples]'
& .\sdk\.venv\Scripts\python.exe .\sdk\examples\train_digits.py --base-url http://127.0.0.1:8080
```

`LABFLOW_API_KEY`가 없으면 `Project API key (hidden):`에서 키를 붙여넣고 Enter를 누릅니다. 입력은 표시하지 않으며 파일에 저장하지 않습니다. 이미 환경변수를 설정했다면 자동으로 사용합니다. `--base-url`이 없으면 `LABFLOW_BASE_URL`, 그것도 없으면 `http://localhost:8080`을 사용합니다.

학습은 기본 20 epoch이며 다음 내용을 실제 API로 기록합니다.

- 파라미터: epoch, batch size, learning rate, seed, 데이터 분할 수와 전처리 설정 등
- 메트릭: epoch별 `train_loss`, `train_accuracy`, `val_loss`, `val_accuracy`와 마지막 `test_loss`, `test_accuracy`
- 로그: 학습 시작·epoch별 진행·학습 종료
- 텍스트: 숫자별 분류 보고서 `classification_report`와 정답·예측·확률 샘플 `prediction_samples`
- 상태: 정상 학습 후 `COMPLETED`, 학습 예외 발생 시 사유와 함께 `FAILED`

학습/검증/테스트 데이터를 나누고 StandardScaler는 학습 데이터에만 맞춥니다. 테스트 세트는 마지막에 한 번만 평가합니다. 매 epoch에는 실제 loss/accuracy를 터미널에도 출력합니다. `step`은 누적 mini-batch 수, `epoch`는 1부터 시작합니다.

다른 설정으로 비교하려면 새 Run 이름과 옵션을 지정합니다.

```powershell
& .\sdk\.venv\Scripts\python.exe .\sdk\examples\train_digits.py --name digits-lr-005 --epochs 30 --learning-rate 0.005 --batch-size 128
```

실패 처리는 별도 Run에서 의도적인 학습 예외를 발생시켜 확인할 수 있습니다. 이 경우 종료 코드는 1이며 정상적인 실패 시나리오입니다.

```powershell
& .\sdk\.venv\Scripts\python.exe .\sdk\examples\train_digits.py --name digits-failure-demo --epochs 5 --fail-after-epoch 2
```

학습 후 [프론트](http://127.0.0.1:5174/)에서 로그인하고 키를 발급한 프로젝트의 **Runs**를 열면 SDK가 만든 실험이 표시됩니다. Metrics에서 실제 loss/accuracy 차트를, Parameters·Logs·Texts에서 저장된 파라미터·로그·분류 보고서·예측 샘플을 확인합니다. 웹 조회에는 로그인 쿠키를 사용하므로 키를 다시 입력할 필요가 없습니다. PUBLIC 프로젝트의 결과는 비로그인 조회도 가능합니다. API 테스트 도구의 [Run 상태 조회](http://127.0.0.1:5174/#/api-test/run-get)를 직접 사용할 때는 출력한 Run ID와 **같은 API key**를 입력합니다.

실제 모델 + SDK + HTTP + DB 검증은 아래 명령으로 재현합니다. 모델 의존성을 설치한 Python의 절대 경로와 Java 21이 필요합니다.

```powershell
# 저장소 루트에서
$env:LABFLOW_MODEL_PYTHON = (Resolve-Path './sdk/.venv/Scripts/python.exe').Path
cd backend
.\gradlew.bat test --tests com.dju.labflow.PythonModelIntegrationTests --rerun-tasks --no-daemon
```

임시 H2 백엔드와 키로 20 epoch 실제 학습 후 메트릭·로그·텍스트·완료 상태를 검사하고, 별도 Run의 학습 예외가 실패로 기록되는지도 확인합니다. 이 자동 테스트는 사용자 프로젝트에 데이터를 남기지 않습니다. `LABFLOW_MODEL_PYTHON`을 지정하지 않으면 일반 백엔드 테스트에서는 건너뜁니다.

## 오류와 전송 정책

`AuthenticationError`(401), `PermissionDeniedError`(403), `NotFoundError`(404), `ConflictError`(409)는 `ApiError`를 상속하고 status_code/code/message/errors를 제공합니다. 연결/타임아웃은 `NetworkError`, 잘못된 응답은 `ProtocolError`, 클라이언트 입력 오류는 `ValidationError`입니다. 공통 기반은 `LabflowError`입니다. API key는 오류 메시지와 객체 repr에서 노출하지 않습니다.

SDK 0.3.0부터 모든 SDK 쓰기 요청에 UUID v4 `requestId`를 자동 생성해 `X-Request-ID` 헤더로 전송합니다. Run 생성, 파라미터, 메트릭, 로그, 텍스트, 시스템 지표, Heartbeat, 완료/실패가 대상입니다. 본문은 기존 형식을 유지합니다. 같은 내용의 로그를 새로 호출하면 새 ID가 생성되어 각각 기록됩니다.

서버는 프로젝트별 요청 ID와 작업 종류·Run ID·검증된 본문의 SHA-256 해시를 비교합니다. 같은 ID와 같은 내용은 Run 생성 시 원래 runId/201/Location을, 나머지 쓰기는 204를 반환하며 중복 저장하지 않습니다. JSON 객체 키 순서는 비교 결과에 영향을 주지 않습니다. 같은 ID를 다른 본문/작업/Run에 재사용하면 `409 REQUEST_ID_CONFLICT`입니다. ID는 경로별이 아니라 **프로젝트 전체에서 유일**해야 합니다. 서로 다른 프로젝트는 같은 ID를 사용할 수 있고, 같은 프로젝트의 다른 API key로도 재시도할 수 있습니다.

SDK의 `request_id` 인자로 직접 ID를 지정할 수도 있습니다. 영문/숫자/`-`/`_` 1~64자를 허용하며 대소문자를 구분합니다. 재전송은 원래 본문까지 같아야 합니다. 특히 `metric()`을 새로 호출해 수동 재전송할 때는 `elapsed_time`과 지정한 `recorded_at`도 동일하게 전달하세요. 프로세스 재시작을 넘는 수동 재시도를 원하면 ID와 원래 입력값을 호출자가 보관해야 합니다. 재시도에 새 ID를 만들면 새 기록으로 취급합니다.

```python
import labflow

# 기본 0: 자동 재시도 없음. 업데이트된 서버에서 필요에 맞게 0~5로 설정합니다.
with labflow.init("training", max_retries=2) as run:
    run.metric({"loss": 0.125}, step=1)
    run.log("학습 진행 중")
```

`max_retries`는 `Client(...)`에도 적용됩니다. 연결/타임아웃과 HTTP 502/503/504만 0.2초부터 지수적으로 간격을 늘려 재시도합니다. 자동 재시도는 ID와 직렬화한 본문을 한 번 만든 뒤 그대로 재사용하므로 자동 생성 경과 시간과 기록 시각도 변하지 않습니다. 4xx, HTTP 500, 리다이렉트, 잘못된 응답 본문은 자동 재시도하지 않습니다. 최종 `NetworkError`의 `request_id`와 `attempts`로 요청 ID와 시도 횟수를 확인할 수 있습니다. 이 옵션은 자동 출력 캡처와 시스템 지표·Heartbeat 전송에도 적용됩니다.

서버는 성공 결과와 실제 기록을 같은 DB 트랜잭션으로 커밋하고, 실패하면 요청 ID도 소비하지 않습니다. 성공한 요청은 Run 종료 후에도 같은 ID/내용으로 재전송할 수 있지만, 인증·현재 접근 권한 검사는 매번 수행합니다. 중복 판정 기록은 프로젝트 삭제 시 함께 삭제하며 별도 TTL은 없습니다. 오프라인 전송 큐나 프로세스 간 ID 보관은 제공하지 않습니다.

배포 순서는 [요청 ID DDL](../backend/database/sdk-requests.sql) 적용 → 서버 업데이트 → SDK 업데이트입니다. 기존 DB에서 한 번 적용하며 새 DB는 갱신된 `runs.sql`을 사용합니다. 이전 서버는 헤더를 무시하므로 업데이트된 서버에서만 중복 제거가 보장됩니다. 헤더 없는 이전 클라이언트는 기존 동작을 유지합니다. HTTP 리다이렉트는 따라가지 않으므로 최종 URL을 설정해야 합니다. 환경 프록시는 자동 적용하지 않으며 HTTPS 인증서 검증은 기본 활성화입니다.

수동 API는 동기식입니다. 자동 수집에만 백그라운드 큐를 사용하며, 오프라인 영속 저장과 파일/데이터셋 업로드는 제공하지 않습니다.

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

`LABFLOW_PYTHON`은 위에서 SDK를 설치한 Python 실행 파일의 절대 경로로 지정하세요. 이 테스트는 `test` 프로필로 임의 포트의 실제 Spring Boot HTTP 서버와 H2를 실행하고, 임시 ACTIVE 사용자·PRIVATE 프로젝트·키를 생성합니다. Python의 연결 테스트 6개를 실행한 뒤 Java 저장소에서 환경 정보·자동 stdout/stderr·시스템 지표·주기 Heartbeat와 파라미터·메트릭·로그·텍스트·종료 상태의 저장 결과와 서버 커밋 후 응답 유실 시 Run/기록이 한 번만 저장되는 것을 검증하고 임시 데이터를 삭제합니다. 실 DB와 Gmail 설정은 사용하지 않습니다.

NVIDIA GPU가 있는 테스트 머신에서는 `$env:LABFLOW_EXPECT_GPU = 'true'`를 추가하면 실제 GPU 사용률·메모리의 DB 저장도 필수로 검증합니다. GPU 없는 머신에서는 이 변수를 설정하지 않습니다.

일반 `pytest`에서는 연결 테스트 6개를 건너뛰고, 일반 백엔드 테스트에서도 `LABFLOW_PYTHON`을 지정하지 않으면 이 테스트를 건너뜁니다. 직접 연결 테스트를 실행하려면 폐기 가능한 테스트 서버의 `LABFLOW_TEST_BACKEND_URL`, 그 서버의 두 다른 프로젝트에 발급한 `LABFLOW_TEST_API_KEY`, `LABFLOW_TEST_OTHER_API_KEY`를 모두 지정하고 `python -m pytest -q tests/integration/test_backend.py`를 실행합니다. 직접 실행한 Python 테스트는 서버에 만든 실험을 남깁니다. 이 테스트 전용 변수는 SDK의 일반 환경변수와 분리되어 있습니다.
