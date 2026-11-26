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

출력 캡처는 Python의 `sys.stdout`/`sys.stderr` 텍스트 쓰기를 대상으로 합니다. **각 쓰기가 반환되기 전에 디스크 트랜잭션으로 먼저 기록**하고, 줄바꿈·명시적 flush·Run 종료 때 백그라운드 전송합니다. 긴 줄은 서버의 16,000 UTF-16 단위 제한에 맞게 분할합니다. 원래 터미널 출력은 유지하고 현재 API key를 마스킹합니다. 미완성 출력의 끝이 현재 키의 4자 이상 접두사와 일치하면 체크포인트에서도 가립니다. C 확장/하위 프로세스의 파일 디스크립터 직접 출력과 이미 다른 스트림에 연결된 logging 핸들러는 캡처하지 않습니다. 중첩 Run은 현재 컨텍스트에 해당하는 Run에 한 번 기록하고, 컨텍스트가 없는 다른 스레드의 출력은 가장 최근 활성 Run에 기록합니다.

출력 전송과 주기 수집은 Run별 백그라운드 스레드에서 수행합니다. stdout/stderr 로그는 큐에 넣거나 HTTP를 보내기 전에 SQLite `synchronous=FULL`로 커밋하며, 서버의 성공 응답을 확인한 뒤에만 삭제합니다. 메모리 전송 큐는 1,000개이며 초과한 로그도 디스크에 유지해 수동 복구할 수 있습니다. 디스크 저장 실패·용량 초과 시에는 경고하고 실패 개수를 남기며 해당 출력을 전송 큐에 넣지 않습니다. `max_retries`는 같은 요청 ID와 원래 본문으로 제한 횟수만큼 재시도합니다. 다음 주기의 시스템 측정/Heartbeat는 새 관측값이며 로컬 재전송 대상이 아닙니다. 명시적인 `metric()`/`log()` 호출은 기존처럼 동기식이며 실패를 호출자에게 전달합니다.

`finish()`/`fail()`은 자동 수집을 중지한 뒤 기본 **최대 10초** 동안 남은 자동 로그 전송을 기다립니다. 기한이 지나면 이미 디스크에 보관된 전송 중·대기 로그를 남기고 경고한 뒤 종료 API를 호출합니다. 종료 API의 `timeout`과 `max_retries`, 로컬 파일 기록 시간은 이 네트워크 정리 대기 한도와 별도입니다. `flush_timeout=0`이면 기다리지 않습니다. 종료 요청 실패 시 자동 수집은 중지된 상태를 유지하므로 서버 상태를 확인하세요. 이미 시작된 동기 HTTP 요청을 강제 종료하지는 않으며, 해당 작업은 별도 연결 풀에서 응답 또는 요청 타임아웃 후 정리됩니다.

`run.telemetry_status`에는 기존 `errors`, `dropped_logs`, `last_error`와 함께 `spooled_logs`(이 Run의 디스크 보관 로그·미완성 출력 수), `unpersisted_logs`(보관하지 못한 로그 수), `flush_timed_out`이 있습니다. 정상 연결 중에도 전송 대기·미완성 출력은 `spooled_logs`에 포함됩니다. 보관 실패는 경고하고 `dropped_logs`에도 포함하며 학습 예외를 발생시키지 않습니다.

### 로컬 보관과 수동 재전송

기본 파일은 사용자 홈의 `.labflow/spool/pending-v2.sqlite3`입니다. API 키·Authorization은 저장하지 않고, 현재 API 키를 마스킹한 로그 본문, 원래 멱등키, Run 경로, 서버 주소, 수집 시각과 만료 시각을 보관합니다. 보관 기간은 기본 7일이며 보관·조회 시 만료 기록을 정리합니다. 용량은 동일 디렉터리를 쓰는 전체 서버의 JSON 본문과 미완성 출력 스냅샷 합계 100 MiB가 기본이며 SQLite 관리 공간은 별도입니다. 한도를 넘기면 기존 미전송 로그는 유지하고 새 로그의 보관 실패를 경고·집계합니다.

줄바꿈 없는 출력도 매번 체크포인트를 갱신합니다. 미완성 출력의 제거와 완성된 로그의 추가는 한 트랜잭션이므로, 강제 종료가 그 사이에 발생해도 마지막 커밋 상태에서 복구합니다. `replay_pending()`은 원래 프로세스의 PID와 시작 시각을 확인하여 종료된 프로세스의 체크포인트만 로그로 전환합니다. 실행 중인 프로세스의 미완성 출력은 재전송하지 않고 `remaining`에 남깁니다. 응답을 받기 전에 강제 종료됐더라도 원래 요청 ID로 복구하므로 서버에서 중복을 제거합니다.

이 보장은 디스크 저장이 성공한 자동 stdout/stderr 출력에 적용됩니다. 매 쓰기에 디스크 I/O가 추가되며, 저장 실패·한도 초과, 아직 반환되지 않은 쓰기, 디스크 손상까지 무손실을 보장하지 않습니다. 기록용 디렉터리는 SQLite 파일 잠금과 동기화를 지원하는 로컬 디스크를 사용하세요.

```python
import labflow

with labflow.init(
    "training",
    flush_timeout=10.0,
    spool_directory="./offline-logs",  # 생략하면 사용자 홈; LABFLOW_SPOOL_DIR도 지원
    spool_retention_days=7.0,
    spool_max_bytes=100 * 1024 * 1024,
) as run:
    print("연결 장애가 나면 자동 로그를 로컬에 보관합니다")

# 네트워크 복구 후 같은 서버와 보관 디렉터리로 연결해 명시적으로 실행합니다.
with labflow.Client(spool_directory="./offline-logs") as client:
    result = client.replay_pending(limit=1000)
    print(result)  # sent, remaining, failed, expired, last_error
```

재전송은 자동으로 시작하지 않으며 한 번에 기본 1,000개(최대 10,000개)를 처리합니다. `remaining`이 남으면 다시 호출할 수 있습니다. 원래 요청 ID와 본문을 유지해 서버 저장 후 응답이 유실되어도 중복 저장하지 않습니다. 성공한 기록만 제거하며 4xx 실패 기록은 남기고 다른 기록을 계속 처리합니다. 네트워크·프로토콜·서버 장애 또는 429에서는 중단합니다. 같은 서버의 여러 프로젝트가 섞여 있으면 현재 키로 접근 가능한 Run만 성공하며, 키를 교체해도 같은 프로젝트 권한이 있으면 복구할 수 있습니다.

새 서버의 로그 복구 기능은 `X-Labflow-Replay: true`를 사용합니다. 종료된 Run에서도 STDOUT/STDERR와 원래 수집 시각이 있고 그 시각이 서버의 Run 종료 시각을 넘지 않는 로그만 허용합니다. 클라이언트 시계가 서버보다 앞서거나 Run/프로젝트가 삭제되면 복구가 거부될 수 있으며 실패 기록은 만료 전까지 남습니다. 서버를 먼저 업데이트한 뒤 새 SDK를 사용하세요.

`run.close()` 또는 `client.close()`도 같은 대기·보관 정책을 적용하지만 서버 실행을 종료 상태로 바꾸지는 않습니다. `client.close()`에서 여러 Run을 닫을 때는 하나의 `flush_timeout` 예산을 공유합니다. 정상 종료에서는 with 문이나 명시적 종료/close를 사용하고, 강제 종료 후에는 새 Client에서 `replay_pending()`을 호출하세요.

서버는 마지막 Heartbeat 이후 기본 2분이 지나면 `connectionStatus=DISCONNECTED`를 반환하며 웹 목록·상세에 **연결 끊김**으로 표시합니다. 첫 Heartbeat가 없으면 Run 시작 시각을 기준으로 판단합니다. 최초 신호 대기 중에는 `WAITING`, 신호 수신 후에는 `CONNECTED`, 종료한 Run은 `INACTIVE`입니다. 서버 환경변수 `RUN_HEARTBEAT_TIMEOUT=2m`으로 기준을 변경할 수 있습니다.

통신 장애만으로 실험 실패를 확정하지 않으므로 연결이 오래 끊겨도 `status=RUNNING`, `endedAt=null`을 유지합니다. 실행 중인 같은 프로세스가 같은 Run에 Heartbeat를 다시 보내면 연결 표시가 자동 복구되고 새 기록을 받을 수 있습니다. 이는 학습 프로세스 재시작이나 과거 미전송 기록의 자동 복구를 제공하는 기능은 아닙니다. 자동 Heartbeat를 끄거나 전송 주기를 기준 시간보다 길게 설정하면 학습 중에도 연결 끊김으로 표시될 수 있습니다.

실제로 중단된 Run은 웹 상세에서 로그인한 프로젝트 소유자가 **Run 종료 처리**로 정리할 수 있습니다. 수동 종료는 사유와 종료 시각을 저장하고 `FAILED`로 전환하며, 이후 새 기록·Heartbeat는 거부합니다. 웹의 종료 처리는 클라이언트 학습 프로세스를 중지하지 않습니다.

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

`AuthenticationError`(401), `PermissionDeniedError`(403), `NotFoundError`(404), `ConflictError`(409/422)는 `ApiError`를 상속하고 status_code/code/message/errors를 제공합니다. 연결/타임아웃은 `NetworkError`, 잘못된 응답은 `ProtocolError`, 클라이언트 입력 오류는 `ValidationError`입니다. 공통 기반은 `LabflowError`입니다. API key는 오류 메시지와 객체 repr에서 노출하지 않습니다.

SDK 0.4.0부터 모든 SDK 쓰기 요청에 UUID v4를 생성하여 `Idempotency-Key`만 전송합니다. Run 생성, 파라미터, 메트릭, 로그, 텍스트, 시스템 지표, Heartbeat, 종료와 파일 업로드가 대상입니다. `X-Request-ID` 및 이전 `request_id` 인자는 지원하지 않습니다.

키 범위는 **API 키 ID + HTTP 메서드 + 요청 경로 + 멱등키**입니다. 같은 범위에서 같은 본문·쿼리를 재전송하면 최초 HTTP 상태·본문·Location을 돌려줍니다. JSON 객체 키 순서는 무시하며 배열 순서는 보존합니다. 다른 본문·쿼리면 `422 IDEMPOTENCY_KEY_REUSED`, 처리 중이면 `409 IDEMPOTENT_REQUEST_PROCESSING`과 `Retry-After: 1`입니다. 다른 경로·메서드·API 키는 독립된 요청이며, **API 키를 교체하고 과거 요청을 다시 보내면 새 쓰기가 됩니다.**

`idempotency_key`로 직접 지정할 수 있으며 공백 없는 출력 가능한 ASCII 1~300자를 대소문자 구분해 허용합니다. 최초 선점부터 15일간 유효하고 재전송으로 연장되지 않습니다. 만료 후 같은 키는 새 쓰기입니다. 자동 만료 정리는 한 번에 최대 1,000행씩 1분 간격으로 실행합니다. 수동 재시도에서는 키뿐 아니라 원래 입력도 보관하세요. 특히 메트릭의 `elapsed_time`과 `recorded_at`을 동일하게 유지해야 합니다. 같은 내용의 새 로그 호출에는 새 키가 생성되어 각각 기록됩니다.

```python
import labflow

with labflow.init("training", max_retries=2) as run:
    run.metric({"loss": 0.125}, step=1)
    run.log("학습 진행 중", idempotency_key="log-example-1")
```

`max_retries`는 기본 0이며 0~5로 설정합니다. 연결/타임아웃, HTTP 502/503/504와 처리 중 409만 같은 키·직렬화 본문으로 재시도합니다. 간격은 0.2초부터 지수적으로 늘어납니다. 내용 충돌 422, 다른 4xx, HTTP 500, 리다이렉트와 응답 형식 오류는 자동 재시도하지 않습니다. `NetworkError.idempotency_key`, `ApiError.idempotency_key`, `NetworkError.attempts`에서 실패한 요청을 확인할 수 있습니다. 결과가 불확실하면 새 키로 바꾸지 말고 같은 키로 확인하세요.

업무 기록·요약과 성공 응답은 같은 DB 트랜잭션으로 저장합니다. 처리된 HTTP 오류는 업무 변경을 롤백한 뒤 최초 오류 응답을 보관하므로, 같은 요청은 같은 오류를 반환합니다. 커밋 결과가 확인되지 않은 중단은 503을 반환하고 같은 키로 복구합니다. 인증·현재 접근 권한 검사는 캐시 조회 전에 매번 수행합니다. 이미 성공한 요청은 Run 종료 후에도 유효 기간 안에서 재전송할 수 있습니다. 원장은 프로젝트 삭제 시 함께 정리합니다.

0.4.0은 서버 V3와 함께 전환해야 합니다. 기존 SDK 쓰기를 멈추고 DB·파일을 백업한 뒤 서버를 업데이트하고 SDK를 업데이트하세요. V3는 이전 `sdk_request_entity`와 Artifact의 `client_request_id`를 제거하며 기존 실험·기록·파일은 보존합니다. 이전 멱등 기록은 변환하지 않습니다. 기존 `pending.sqlite3`는 자동 재전송·삭제하지 않고 새 `pending-v2.sqlite3`를 사용합니다. 이전 큐는 서버 반영 여부를 대조한 후 수동 정리하세요. 새 큐도 서버 주소와 API 키 해시별로 분리하므로, 키 교체 후에는 원래 키의 대기 로그를 자동 전송하지 않습니다. 이전 SDK는 새 서버에서 거부됩니다.

일반 JSON API에서 헤더를 생략하면 중복 방지 없이 실행됩니다. 파일 업로드는 헤더가 필수입니다. GET에서는 무시합니다. HTTP 리다이렉트는 따라가지 않으므로 최종 URL을 설정하세요. 환경 프록시는 자동 적용하지 않으며 HTTPS 인증서 검증은 기본 활성화입니다.

수동 API는 동기식입니다. 자동 stdout/stderr 수집에는 백그라운드 큐와 로컬 보관을 사용하며, 수동 메트릭·로그·텍스트의 오프라인 보관은 제공하지 않습니다. Artifact 파일 전송은 아래 항목을 참고하세요.

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

### Artifact 파일 전송

```python
import labflow

with labflow.Client() as client:
    with client.start_run("training") as run:
        artifact_id = labflow.artifact("weights.pt", kind="MODEL")
    client.download_artifact(run.id, artifact_id, "weights-copy.pt")
```

다운로드 경로의 상위 디렉터리는 미리 생성하세요. SDK는 임시 파일에 내려받고 SHA-256을 확인한 후 대상 파일을 교체합니다. 같은 파일을 다시 전송할 때 같은 `idempotency_key`를 지정하면 기존 Artifact ID를 돌려받습니다.
