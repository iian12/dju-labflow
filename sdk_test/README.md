# 독립 Python 프로젝트에서 LabFlow SDK 사용하기

이 폴더의 `.venv`에는 GitHub에서 설치한 `labflow-sdk 0.1.1`, NumPy, scikit-learn이 들어 있습니다. 저장소의 `sdk/src`를 editable 설치하거나 직접 import하지 않습니다. `requirements.txt`는 검증된 원격 커밋을 고정합니다.

## 바로 실행

백엔드가 `http://127.0.0.1:8080`에서 실행 중이어야 합니다. [프론트](http://127.0.0.1:5174/)에서 사용할 프로젝트의 API key를 발급하거나 기존 키를 준비하세요.

```powershell
# dju_labflow 최상위에서
cd sdk_test
.\.venv\Scripts\python.exe .\train_digits.py
```

`Project API key (hidden):`가 나오면 키를 붙여넣고 Enter를 누르세요. 입력을 화면에 표시하지 않으며 파일에 저장하지 않습니다. 이미 `LABFLOW_API_KEY` 환경변수가 있으면 자동으로 사용합니다. 가상환경 활성화 없이도 위 명령으로 정확한 Python을 사용할 수 있습니다.

기본 실행은 CPU에서 손글씨 숫자를 분류하는 실제 `SGDClassifier` 모델을 20 epoch 학습합니다. 데이터는 scikit-learn 패키지에 포함되어 있어 별도로 다운로드하지 않습니다.

## 기록되는 내용

- 파라미터: epoch, batch size, learning rate, seed, 전처리와 데이터 분할 수
- 매 epoch 실제 메트릭: `train_loss`, `train_accuracy`, `val_loss`, `val_accuracy`
- 마지막 평가: `test_loss`, `test_accuracy`
- 로그: 시작, epoch별 진행, 종료
- 텍스트: 숫자별 분류 보고서와 정답·예측·확률 샘플
- 실행 상태: 정상 종료는 `COMPLETED`, 학습 예외는 사유와 함께 `FAILED`

학습/검증/테스트 데이터를 분리하고 전처리는 학습 데이터에만 맞춥니다. 테스트 데이터는 마지막에 한 번만 평가합니다. 결과는 터미널에도 출력됩니다. 프론트에서 키를 발급한 프로젝트의 **Runs**를 열어 실제 차트·파라미터·로그·텍스트를 확인하세요.

## 옵션

```powershell
# 다른 설정으로 새 실험
.\.venv\Scripts\python.exe .\train_digits.py --name digits-lr-005 --epochs 30 --learning-rate 0.005 --batch-size 128

# 다른 백엔드 연결
.\.venv\Scripts\python.exe .\train_digits.py --base-url http://127.0.0.1:8080

# 실패 처리 확인: 의도적으로 2 epoch 후 예외 발생 (종료 코드 1)
.\.venv\Scripts\python.exe .\train_digits.py --name digits-failure-test --epochs 5 --fail-after-epoch 2
```

기본 이름은 `sdk-test-digits`입니다. 백엔드 주소는 `--base-url`, `LABFLOW_BASE_URL`, `http://127.0.0.1:8080` 순으로 선택합니다. 다른 컴퓨터에서는 접근 가능한 실제 서버 주소를 지정하세요.

## 환경 재생성

Python 3.10 이상과 Git이 필요합니다. 이 환경은 Python 3.13으로 구성했습니다.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip check
```

`.venv/`는 기존 Git 제외 규칙으로 커밋되지 않습니다. 키도 소스 코드나 Git 저장소에 넣지 않습니다.
