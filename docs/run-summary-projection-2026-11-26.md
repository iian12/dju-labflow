# Run 요약 테이블과 polling 전환

## 문제와 변경

기존 `/runs/summaries`는 전체 메트릭 이력에 `ROW_NUMBER()`를 적용해 지표별 최신 기록을 찾고, 별도로 원본 전체에서 최대 step을 계산했다. 기록이 누적될수록 화면 polling의 DB 비용이 증가했다.

V2는 `(run_id, metric_key)`를 기본키로 하는 `run_metric_summary`를 추가한다. 각 행에는 최신 원본 ID, 최신 기록 시각, 해당 지표의 최대 step만 저장한다. 최신 원본은 `(recorded_at, id)` 순서로 결정하며 최대 step과는 독립적이다. NULL step은 기존 SQL `MAX`와 동일하게 제외한다.

`RunRecordService.metrics()`가 기존 프로젝트 → Run 잠금을 획득한 뒤 원본을 저장·flush하고 같은 트랜잭션에서 요약을 UPSERT한다. 요약 저장소는 `MANDATORY`로 트랜잭션 없이 호출할 수 없게 했다. 요약 갱신 오류, 외부 트랜잭션 롤백은 원본과 SDK 요청 처리 기록도 함께 취소한다. 요청 ID 재전송은 기존 멱등 처리 경로를 유지한다.

요약 조회는 요약 행에서 최신 원본을 PK로 연결하고, 요약의 최대 step을 Run 단위로 집계한다. 응답 형식·공개 범위·프로젝트 소속 검사는 유지한다. Run 목록의 지표 정렬과 지표 이름 조회도 요약을 사용해 원본 이력을 재탐색하지 않는다. 프로젝트 삭제 시 요약 행도 함께 삭제한다.

## 화면 갱신

- 현재 화면의 목록/확인할 Run에 대해서만 요약을 일괄 조회한다. 목록 갱신과 요약 갱신을 분리했다.
- 실행 중인 Run이 있는 화면은 응답 완료 후 5초를 기다려 다시 조회한다. 느린 요청이 중첩되지 않는다.
- Run 상태가 종료 상태로 바뀌면 최종 요약을 다시 읽는다. 모두 종료된 화면은 요약 polling을 멈추고, 수동 새로고침은 계속 지원한다.
- 목록은 새 Run 발견을 위해 5초 갱신을 유지한다. 기록 상세/설정 화면은 기존처럼 목록 요약을 조회하지 않는다.
- 숨겨진 탭은 주기적 요청을 보내지 않고, 탭 복귀 시 활성 polling을 즉시 재개한다.
- 실패 시 마지막 성공 데이터를 유지하며 재조회 간격을 10초, 20초, 40초, 최대 60초까지 늘린다. 성공하면 5초로 복귀한다.

## 검증

최종 검사 결과는 백엔드 304개 통과·실패 0개·환경 조건에 따른 생략 12개, 프론트엔드 39개 통과다. MariaDB 마이그레이션·요약 검사는 실제 임시 MariaDB에서 실행했다. 생략된 검사는 별도 설정이 필요한 인증 전용 MariaDB 7개와 백엔드 연동 브라우저/SDK/모델 검사 5개다. Spotless, `bootJar`, 프론트엔드 production build도 통과했다.

H2 회귀 검사는 과거 시각으로 늦게 도착한 기록, 같은 시각의 ID 순서, NULL step, 다중 지표, 바깥 트랜잭션 롤백, 요약 CHECK 제약 오류에 따른 전체 롤백, 같은 요청 ID 재전송, 4개 스레드의 동시 기록, 프로젝트별 삭제 격리를 포함한다. 기존 이력 집계 SQL을 정답으로 사용해 요약 결과를 비교한다. HTTP 접근 제어와 목록 정렬 회귀 검사도 유지한다.

실제 MariaDB 검사는 독립 임시 서버를 사용하며 운영 DB나 앱 `.env`에 연결하지 않는다. V1에 10만 건을 저장한 뒤 V2 backfill 결과를 비교하고, 동시 기록·과거 기록·NULL step·트랜잭션 롤백·요약 쓰기 실패를 검증한다. 구버전 writer로 인해 어긋난 요약을 관리 SQL로 복구하는 경로도 검사한다.

브라우저 검사는 실제 Edge에서 API를 모킹해 실행 중 요약 갱신, 종료 후 최종 읽기와 polling 중단, 수동 갱신, 숨김 탭 복귀, 오류 backoff, 느린 요청 중첩 방지를 확인한다. 실제 백엔드를 사용하는 외부 장기 검증과는 구분한다.

검증 명령:

```powershell
# 설치된 MariaDB bin 경로를 명시한다. 테스트는 별도 임시 데이터 디렉터리·포트만 사용한다.
$env:LABFLOW_MARIADB_BIN = 'C:/Program Files/MariaDB 11.8/bin'
cd backend
.\gradlew.bat check bootJar
cd ../frontend
npm test
npm run build
```

## 로컬 SQL 측정

Windows의 MariaDB 11.8.6, InnoDB 버퍼 풀 32MiB, 20개 Run × 5개 지표, 총 100,000개 원본과 100개 요약 행을 사용했다. 같은 연결에서 기존/개선 SELECT를 번갈아 실행하고 결과를 모두 소비했다. 처음 5회는 제외하고 30회 측정했다. SQL은 통합 테스트 `summaryBackfillAndConcurrentWritesMatchHistoryAndUseBoundedReads`에 보존한다.

| 최신 메트릭 ID 선택 SQL | p50 | p95 |
| --- | ---: | ---: |
| 원본 이력의 window 집계 | 173.993ms | 196.779ms |
| 요약과 최신 원본 PK 연결 | 0.321ms | 0.616ms |

이 수치는 최신 ID 선택 SQL만 측정한다. 인증, HTTP, 최대 step 조회, 직렬화 및 네트워크 비용은 포함하지 않는다. Raspberry Pi의 외부 API p95 또는 이전 2시간 테스트와 직접 비교할 수 없으며, 쓰기 비용 증가도 이 수치로 평가할 수 없다. 실행 환경에 따라 값이 달라지므로 CI 합격 조건으로 시간을 고정하지 않는다.

## 배포와 남은 검증

V1은 수정하지 않았고 V2 적용 시 기존 원본을 보존한다. 최초 전환에는 모든 구버전 메트릭 writer를 중지해야 한다. DB 백업, 복제본 backfill 확인, 롤백 후 요약 재구축은 `backend/database/README.md`와 `rebuild-run-metric-summary.sql`을 따른다.

이번 변경의 정량적 트레이드오프는 원본 저장마다 요약 갱신이 추가되는 것이다. 같은 데이터 규모와 부하에서 요약 API 지연, 쓰기 API 지연, 잠금 대기, DB CPU 및 마지막 5분의 성능을 다시 비교해야 한다. 위 로컬 SQL 결과와 아래 배포 검증은 새로운 2시간 부하 테스트의 결과가 아니다.

## Raspberry Pi 배포 검증 — 2026-11-26

- 실행 버전: `4f6f46fdad20c42212f07d8f88f02f9900687d4a`. 이전 버전은 `0d9317a7faf54d4fc8595e6d87d990f4ee6cc368`.
- NVMe ext4의 `/mnt/nvme/labflow/releases/summary-4f6f46f-20261126`에 백엔드와 새 프론트엔드를 함께 배포했다. `current` 링크와 실제 Java 작업 디렉터리, 로컬/서버 JAR SHA-256 일치를 확인했다.
- 애플리케이션 writer를 중지하고 `/mnt/nvme/labflow/shared/backups/before-summary-4f6f46f-20261126131401.sql.gz`를 저장한 뒤 gzip 검증을 통과했다. DB 백업은 NVMe 내부의 배포 복구용이며 외부 백업을 의미하지 않는다.
- 13:14 KST에 Flyway V2가 성공했다. 운영 DB의 기존 메트릭은 0건이었으므로 대량 backfill 검증은 위 로컬 MariaDB 10만 건 검사에 해당한다.
- 기존 cloudflared 프로세스를 유지했으며 외부 주소는 `https://store-designation-estimates-said.trycloudflare.com`으로 유지했다.
- 실제 외부 Edge·Python SDK에서 로그인, Secure/HttpOnly 쿠키, 프로젝트 공개 범위, 키 발급, 메트릭·로그·텍스트·Heartbeat, 1MiB 파일 왕복 SHA-256, 세션 갱신, 프로젝트 삭제·키 무효화·로그아웃을 검증했다.
- 새 Run에서 중복 요청, 과거 시각의 기록, 같은 시각의 새 기록을 보냈다. DB에는 원본 6행과 요약 2행이 저장됐으며 마지막 step 200을 확인했다. 실제 웹 화면의 갱신 관측 시간은 5,499ms였다. 이는 5초 polling에 따른 표시 지연이며 API 응답 시간이나 p95가 아니다.
- 종료 후 최종 조회, 11초 동안 추가 요약 polling 중단, 수동 새로고침을 확인했다.
- Prometheus 대상 3개 UP, Grafana 쿼리 30개 정상. DB·백엔드·게이트웨이·터널 모두 active, 서비스 자동 재시작 0회.
- 13:18 KST 정리 후 기존 사용자 1명 보존, 임시 사용자·프로젝트·Run·메트릭·요약·파일 메타데이터·물리 파일은 모두 0건. 검증용 자격 증명과 seed SQL도 제거했다. 온도 52.9°C와 throttled=0x0은 종료 시점의 단일 표본이다.

외부 검증 원본 보고서와 화면은 로컬 `.external-access/redeploy-4f6f46f/verification`에 보관한다. 운영 반영 후 새 2시간 부하 테스트는 아직 실행하지 않았다.
