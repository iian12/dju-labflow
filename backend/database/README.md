# DB 스키마 관리

현재 코드의 공식 기준은 `src/main/resources/db/migration`의 V1~V3입니다. V1은 사용자·프로젝트·키·토큰·이메일 인증·요청 제한·Run과 기록·파일·SDK 멱등성까지 **16개 테이블**을 포함합니다. V2는 지표별 요약 테이블 `run_metric_summary`를 추가하고 기존 이력을 채웁니다. V3는 공통 멱등 응답 원장으로 전환합니다. 적용된 V1/V2는 수정하지 않습니다.

Flyway가 DDL 변경을 담당하고 JPA는 `validate`로 매핑만 검사합니다. 빈 DB는 백엔드 시작 시 V1부터 최신 버전까지 적용하고, 이후 시작에서는 미적용 버전만 실행합니다. `APP_PROFILE=prod`는 `JPA_DDL_AUTO=update`가 설정되어 있어도 `validate`를 사용합니다. 일반 H2 회귀 테스트만 Flyway를 끄고 `create-drop`을 유지합니다.

## V2 요약 테이블 전환

- 모든 메트릭 기록 서버를 중지하고 DB를 백업한 뒤 새 버전을 시작합니다. 기존 버전과 새 버전을 동시에 운영하는 롤링 배포는 지원하지 않습니다. V2 backfill과 새 writer 전환 사이에 과거 writer가 기록을 추가하면 요약이 누락될 수 있습니다.
- 최신 기록은 `(recorded_at, id)` 내림차순, 마지막 step은 모든 기록의 최대값이며 NULL은 제외합니다. 한 `(run_id, metric_key)`당 최신 원본 ID·기록 시각·최대 step만 보관합니다. 원본 이력은 변경하지 않습니다.
- 메트릭과 요약 UPSERT, SDK 요청 처리 기록은 같은 트랜잭션에서 커밋됩니다. 프로젝트 → Run 잠금을 유지하며 요약 갱신 실패 시 원본도 롤백됩니다.
- 이력이 없는 DB의 `schemaCheck`/`schemaBaseline`은 V1 구조를 검사합니다. V1 이력을 기록한 뒤 기동하면 V2가 적용됩니다. V2 적용 DB의 `schemaCheck`는 요약 테이블도 검사합니다.
- 데이터가 큰 DB는 복제본에서 backfill 시간·임시 디스크 사용량을 측정하고 점검 시간을 확보합니다. MariaDB DDL은 암묵적으로 커밋되므로 V2 전체가 원자적으로 롤백되는 것은 아닙니다. 실패 시 Flyway 이력과 실제 테이블 상태를 확인하고 원인을 해결한 뒤 복구해야 합니다.
- V2 이전 앱으로 롤백한 뒤 기록이 추가되었다면, 다시 새 앱을 시작하기 전에 모든 서버를 중지한 상태에서 `rebuild-run-metric-summary.sql`을 실행하고 기존 집계와 비교합니다. SQL 오류 시 중단·ROLLBACK하는 클라이언트를 사용하고 `--force`로 계속 실행하지 않습니다. 원본은 삭제하지 않으며 적용된 V2를 수정하거나 재실행하지 않습니다.

## V3 멱등 응답 원장 전환

- 모든 이전 SDK writer와 서버를 중지하고 DB·Artifact 파일을 백업합니다. V3는 `idempotency_request`를 생성하고 `sdk_request_entity`, `run_artifact_entity.client_request_id` 및 해당 유니크 제약을 제거합니다. 기존 실험·이력·Artifact 메타데이터와 실제 파일은 보존합니다. 멱등키는 도메인 테이블에 중복 보관하지 않습니다.
- SDK 0.4.0과 함께 전환합니다. `Idempotency-Key`만 지원하며 이전 `X-Request-ID`는 호환되지 않습니다. 기존 멱등 원장은 폐기되므로 과거 요청이나 과거 오프라인 큐를 새 프로토콜로 무조건 재전송하면 중복 쓰기가 됩니다. SDK는 별도 `pending-v2.sqlite3`를 사용해 기존 큐를 자동 재생하지 않습니다.
- 새 앱 시작 후 Flyway 성공 V3, JPA validate, 주요 SDK 쓰기·같은 응답 재생·파일 다운로드를 확인합니다. `schemaCheck`는 현재 Flyway 버전에 도입된 엔티티만 검사합니다.
- MariaDB DDL은 암묵적으로 커밋됩니다. **이전 앱으로 파일만 되돌려서는 롤백되지 않으며, V3 이전 DB 백업을 복원하고 기존 SDK와 함께 되돌려야 합니다.** 전환 후 쓴 데이터가 있다면 먼저 별도로 보존·대조합니다. 복원 없이 이전 앱과 새 앱을 동시에 운영하지 않습니다.
- 실제 MariaDB 통합 테스트는 V2 기존 데이터 보존, V3 스키마 검증, NOWAIT 경합과 lease 복구·응답 재생·내용 충돌을 검사합니다.

## 새 DB

1. 빈 MariaDB 데이터베이스와 접속 계정을 준비합니다. 데이터베이스 자체와 계정 생성은 Flyway의 범위가 아닙니다.
2. 기존 `.env` 형식으로 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 및 애플리케이션 설정을 지정합니다.
3. 백엔드를 실행합니다. Flyway V1 → V2 → V3 → JPA 검증 → HTTP 서버 준비 순서입니다.
4. `flyway_schema_history`에 성공한 버전 1~3이 있는지 확인합니다.

V1을 직접 실행한 뒤 서버를 시작하지 마세요. 이 경우 이력이 없는 기존 DB로 취급되어 자동 시작이 거부됩니다. 이 폴더의 과거 `runs.sql`, `email-verifications.sql`, `auth-request-limits.sql` 등을 신규 설치에 추가 실행할 필요도 없습니다.

## 기존 DB를 최초로 편입

기존 테이블이 있는 DB에 자동 baseline을 허용하지 않습니다. 대상 DB를 명시하고 구조를 먼저 검사합니다. **애플리케이션의 `.env`는 아래 관리 명령에서 읽지 않습니다.** 모든 명령은 `backend/`에서 실행합니다.

1. DB와 로컬 Artifact 저장소를 백업하고 복원 가능한지 확인합니다. 운영 트래픽을 멈추고 복제본에서 전환을 먼저 검증합니다. 구조 대조부터 baseline까지 다른 배포나 DDL 변경이 동시에 실행되지 않게 합니다.
2. `LABFLOW_SCHEMA_URL`, `LABFLOW_SCHEMA_USER`, `LABFLOW_SCHEMA_PASSWORD`를 관리 명령을 실행할 프로세스의 환경변수로 제공합니다. 비밀번호는 명령행 인수·문서·셸 이력에 적지 말고 비밀값 주입 방식을 사용합니다. URL에는 비밀번호를 넣지 않습니다.
3. 이력과 현재 구조를 확인합니다.

   ```powershell
   .\gradlew.bat schemaInfo
   .\gradlew.bat schemaCheck
   ```

   예시 URL 형식은 `jdbc:mariadb://127.0.0.1:3306/labflow_clone`입니다. 관리 계정은 대상 스키마의 메타데이터와 테이블을 읽을 수 있어야 합니다. `schemaBaseline`에는 Flyway 이력 테이블을 생성하고 기록할 권한도 필요합니다.

4. `schemaCheck`가 실패하면 해당 차이만 보정합니다. 이 명령은 DDL이나 사용자 데이터를 수정하지 않습니다. JPA 매핑·필수 NOT NULL·문자열 최소 길이·enum 값·utf8mb4·LONGTEXT·시간 정밀도·기본키·필수 인덱스/유니크 키·컬렉션 외래 키를 검사합니다. 인덱스/외래 키 이름은 달라도 구성 컬럼과 유일성이 같으면 허용합니다. 추가 테이블이나 컬럼, 모든 CHECK/default/trigger까지 완전히 비교하는 도구는 아니므로 `SHOW CREATE TABLE`과 V1도 검토합니다.

   과거 SQL은 **첫 편입 전에 빠진 변경을 보정할 때만** 참고합니다. 상태를 확인하지 않고 전부 다시 실행하지 마세요. V1 편입 후에는 과거 SQL을 더 사용하지 않습니다.

   | 차이 | 보정 기준 |
   | --- | --- |
   | 이메일 인증/요청 제한 테이블 누락 | V1의 해당 CREATE TABLE 또는 과거 개별 SQL 검토 |
   | Heartbeat/최신 지표 인덱스/긴 텍스트 누락 | `run-heartbeat.sql`, `run-list-metric-index.sql`, `expand-run-text-columns.sql` 검토 |
   | 사용자/프로젝트/API 키/토큰 등 초기 구조 누락 | V1 전체 정의와 실제 구조를 대조하여 별도 보정 SQL 작성 |
   | nullable version 또는 필수 컬럼 | NULL/중복 행을 조사한 뒤 제약 보정; 임의 기본값으로 이력을 덮어쓰지 않음 |
   | 과거 `TEAM` 공개 범위 | 해당 데이터를 `PRIVATE`으로 이관한 뒤 enum을 `PRIVATE/PUBLIC`으로 정리 |
   | 문자셋/시간 정밀도/짧은 문자열 | 기존 데이터와 잠금 영향을 확인하고 V1 요구에 맞춰 확장 |

5. 구조가 맞는 복제본에 baseline을 기록합니다.

   ```powershell
   .\gradlew.bat schemaBaseline
   .\gradlew.bat schemaInfo
   ```

   `schemaBaseline`은 구조 검사를 다시 통과해야 실행되며 V1까지 적용된 것으로 이력만 기록합니다. 사용자 테이블을 재생성하거나 데이터를 변경하지 않습니다. 이력이 이미 있는 DB에는 재실행할 수 없습니다. 기존 이력을 지우거나 `repair`로 실패를 숨기지 마세요.
6. 백엔드를 시작하여 JPA 검증, 로그인·SDK 기록·기존 기록 조회를 확인합니다. 복제본에서 성공한 동일 보정 절차를 백업한 운영 DB에 적용하고 운영 baseline을 명시적으로 실행합니다.

관리 명령은 `info`, `check`, `baseline`만 제공합니다. Flyway의 `clean`은 비활성화했고 `baseline-on-migrate=false`, `validate-on-migrate=true`, `out-of-order=false`를 유지합니다. Baseline은 스키마를 생성하거나 데이터 복구를 수행하는 기능이 아닙니다.

## 다음 변경부터

- 최초 배포 후 V1을 수정하지 않고 `V2__설명.sql`, `V3__설명.sql`을 추가합니다. 적용된 파일은 체크섬 검증 대상입니다.
- 엔티티 변경과 대응하는 마이그레이션, 신규 DB 생성 및 기존 버전 업그레이드 테스트를 같은 변경에 포함합니다.
- 컬럼 추가 → 데이터 이관/코드 전환 → 후속 배포에서 삭제 순서로 호환성을 유지합니다.
- MariaDB의 DDL은 암묵적으로 커밋될 수 있습니다. 실패 시 SQL 전체가 자동 롤백된다고 가정하지 말고 실제 적용 상태를 확인한 뒤 복원 또는 후속 보정으로 복구합니다. 앱 버전 롤백만으로 DB는 되돌아가지 않습니다.
- 대규모 인덱스/컬럼 변경은 잠금 시간과 디스크 여유를 복제본에서 측정하고 별도 배포 절차로 실행합니다. 배포 후 수동 DDL이 필요했다면 후속 마이그레이션과 이력을 함께 정리합니다.

## 검증

```powershell
.\gradlew.bat test --tests '*SchemaMigrationTests' --tests '*EnvironmentConfigurationTests'
```

`SchemaMigrationTests`는 H2 MariaDB 모드에서 신규 생성·재시작·명시적 baseline·데이터를 보존하는 후속 V2·체크섬 변경 거부를 검사합니다. 이 테스트용 V2는 임시 폴더에서만 생성되며 제품 마이그레이션에는 포함되지 않습니다.

실제 Windows MariaDB ZIP의 `bin` 경로를 `LABFLOW_MARIADB_BIN` 환경변수로 지정하면 다음 검사를 실행할 수 있습니다. 시스템 서비스나 기존 DB를 사용하지 않고 임시 데이터 디렉터리와 임의의 loopback 포트로 새 프로세스를 실행한 뒤 종료합니다.

```powershell
.\gradlew.bat test --tests '*MariaDbMigrationIntegrationTests'
```

MariaDB 검사는 V1 생성 후 실제 앱의 JPA 검증, 공유 요청 제한의 동시 UPSERT, 기존 사용자/한글/이모지 보존, 후속 V2 적용, 필수 인덱스·유니크 키 누락 시 baseline 거부, 컬럼 누락 시 앱 기동 거부를 확인합니다. `LABFLOW_MARIADB_BIN`이 없으면 선택 검사로 건너뜁니다.

참고: [Spring Boot DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Flyway baseline](https://documentation.red-gate.com/fd/baseline-277578867.html), [MariaDB DDL 트랜잭션 제한](https://documentation.red-gate.com/fd/schema-model-273973399.html).
