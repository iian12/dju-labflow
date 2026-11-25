# API 인증 충돌 수정 및 배포 검증 · 2026-11-26

라즈베리파이 NVMe의 `auth-retry-20261126` 릴리스에 수정한 백엔드를 배포하고,
외부 Windows PC에서 브라우저·Python SDK·k6 테스트를 완료했다.
전체 결과는 통과이며, 테스트 계정과 프로젝트는 삭제했다. 기존 사용자 1명은 보존했다.

## 수정 내용

동시 SDK 요청이 같은 API 키의 사용 시각을 갱신할 때 MariaDB의
REPEATABLE READ 및 snapshot isolation에서 오류 1020이 발생했다.
API 키 인증을 별도 Bean의 READ COMMITTED 새 트랜잭션으로 분리하고,
1020·1213·1205 또는 SQLSTATE 40001 충돌에만 최대 3회 시도하도록 변경했다.
각 시도는 키와 소유자 상태를 다시 확인한다. 인증을 끝낸 뒤 SDK 본문을 한 번 실행한다.
재시도 소진은 `Retry-After: 1`을 포함한 503으로 응답하고, 무효 키는 401을 유지한다.
DB 전역 기본 격리 수준과 snapshot isolation 설정은 변경하지 않았다.

Prometheus용 Micrometer, loopback 관리 포트, Node Exporter, 온도·스로틀링 수집,
Grafana 대시보드 및 외부 테스트 도구도 저장소에 포함했다.
운영 절차는 [monitoring/operations.txt](../monitoring/operations.txt)를 따른다.

## 자동 검사

| 검사 | 결과 |
| --- | --- |
| 백엔드 `gradlew.bat build --no-daemon` | 299개 통과, 실패·오류 0개, 환경 조건에 따른 10개 생략. Spotless·빌드 통과 |
| 실제 MariaDB 11.8.9 인증 통합 검사 | 위 실행에 포함된 7개 모두 통과. snapshot isolation ON, 기본 REPEATABLE READ |
| 인증 회귀 범위 | 기존 1020 재현, 20개 동시 클라이언트의 인증 300회, 새 트랜잭션·롤백, 계정 차단·키 폐기, 소진 시 실제 HTTP 503과 SDK 미실행, 사용 시각 단조 증가 |
| 프런트엔드 `npm test` | TypeScript 및 37개 검사 통과 |
| 실제 외부 브라우저·SDK | 로그인·쿠키·접근 제어·프로젝트·기록·공유·토큰 갱신·삭제·로그아웃, 1MiB 파일 해시 일치 통과 |
| Prometheus/Grafana | 대상 3개 UP, 쿼리 30개 정상, 데이터 소스 및 실제 대시보드 표시 통과 |
| 배포 무결성 | 로컬 및 NVMe JAR SHA256 일치, 배포 이후 새 ERROR·snapshot 충돌·deadlock 0건 |

생략한 10개는 Windows MariaDB 마이그레이션 5개와 Python 모델·SDK 및
ADMIN·Studio·기록 커서의 선택적 통합 검사 각 1개다. 이 실행에서 해당 환경 변수는
활성화하지 않았다. 이번 변경의 MariaDB 인증 검사는 별도 Docker DB에서 실제 실행했고,
외부 브라우저와 Python SDK는 배포 서버에서 별도로 검증했다.

## 외부 부하 결과

k6 사이클 `20261126-003239`: 1 → 5 → 10 → 1 → 0 VU,
반복 사이 1초 대기, 계획 170초·실측 171.684초.
외부 HTTPS 터널을 거친 결과이며 장시간 내구성이나 최대 수용량을 입증하는 시험은 아니다.

| 지표 | 수정 전 | 수정 후 |
| --- | --- | --- |
| HTTP 요청 | 4,609 | 4,951 |
| 실패 | 50 (1.0848%) | 0 (0%) |
| 클라이언트 p95 | 59.636ms | 56.799ms |
| 클라이언트 p99 | 300.996ms | 307.001ms |
| 종료 | 오류율 기준 초과로 조기 종료 | 전체 부하 및 회복 확인 완료 |

전체 오류율 <1%, checks >99%, 전체 p95 <1초·p99 <2초,
읽기 p95 <500ms·쓰기 p95 <1초·파일 p95 <2초 기준을 모두 통과했다.
이번 사이클 전체 관측 최대 CPU 23.1%, RAM 39.1%, 온도 57.6°C,
스로틀링과 Hikari 대기 연결은 0이었다.

| 저장 항목 | 성공 요청에 따른 예상 | 실제 |
| --- | --- | --- |
| Runs | 10 | 10 |
| Metrics | 1,480 | 1,480 |
| Logs | 730 | 730 |
| Texts | 78 | 78 |
| System metrics | 78 | 78 |
| Parameters | 20 | 20 |
| Artifacts | 10 | 10 |

동일 요청 ID로 보낸 78회 재전송이 중복 행을 만들지 않는 것도 확인했다.
64KiB 파일 10개의 업로드·다운로드 및 해시 검증을 포함한다.
테스트 후 프로젝트·Run은 0개이고 서비스 4개는 모두 active였다.

## 기록 위치

PC의 Git 제외 디렉터리 `.external-access/load-tests/20261126-003239/`에
`report.html`, 전체 JSON, k6 요약·압축 원시 측정, 서버 시계열, 브라우저·Grafana 화면을 보관한다.
실행용 API 키·쿠키 파일과 테스트 계정 인증 파일은 삭제했다.
Grafana 대시보드 `labflow-performance`에 테스트 구간 주석 ID 2를 등록했다.
공유용 기록에는 계정·키·쿠키·서버 로그 원문을 포함하지 않는다.

배포 JAR SHA256:
`7612d95bbab66d9ef6f37665a2039f4c4382ec70ecf6726c4aca79a410902e09`.
