# 통합 검증 · 2026-11-25

검증한 기능 커밋은 `5e9ba56`입니다. `develop`의 기존 기능과 다음 8개 변경을 포함합니다.

| 커밋 | 변경 |
| --- | --- |
| `8abebaa` | 백엔드 Java 지역 변수 타입 명시 |
| `6335fa4` | 전체 기록 내보내기에서 파라미터 조회 완료 보장 |
| `edb2904` | 파일 스트리밍과 DB 잠금 분리, 업로드 용량 예약 및 정리 |
| `bf044b6` | SDK 자동 로그 디스크 보관, 종료 기한, 강제 종료 복구와 서버 멱등 재전송 |
| `a8ea781` | Run 서버 검색·정렬·페이지 처리 및 제한된 폴링 |
| `8ef8fb4` | 신뢰 프록시 IP 복원 및 DB 공유 인증 요청 제한 |
| `1e6dfe2` | 여러 탭의 토큰 갱신·로그아웃 직렬화와 세션 변경 전파 |
| `5e9ba56` | 전체 16개 테이블 Flyway V1, 기존 DB 구조 검사 및 명시적 편입 |

## 검증 결과

| 검사 | 결과 |
| --- | --- |
| 백엔드 `build --rerun-tasks` | 286개 통과, 실패·오류·생략 0개. Spotless 및 빌드 통과 |
| 실제 MariaDB 11.4.5 | 독립 임시 프로세스에서 5개 통과. V1 생성, JPA 검증, baseline 및 V2 데이터 보존, 동시 UPSERT, 스키마 불일치 거부 |
| Python SDK 실제 HTTP/H2 연동 | Java 통합 검사에서 Python 6개 모두 통과 |
| 실제 모델 학습 HTTP/H2 연동 | 숫자 분류 학습의 정상 완료 및 예외에 따른 FAILED 전이 통과 |
| 실제 Studio·기록 커서·ADMIN 브라우저 연동 | 임시 HTTP/H2 서버 및 Edge에서 모두 통과. 두 탭의 실제 회전 쿠키 포함 |
| 프런트엔드 `npm test` | 37개 통과 |
| 프런트엔드 `npm run build` | TypeScript 및 프로덕션 빌드 통과 |
| SDK 단독 `pytest -q` | 145개 통과. 외부 백엔드 설정이 필요한 6개는 이 실행에서 생략하며 위 HTTP 통합 검사에서 모두 별도 검증 |
| SDK Ruff lint/format | 통과 |
| 게이트웨이 헤더 검사 | 2개 통과 |
| `git diff --check` 및 Java `var` 검색 | 공백 오류 및 Java `var` 선언 없음 |

백엔드 통합 실행은 `LABFLOW_MARIADB_BIN`, `LABFLOW_PYTHON`, `LABFLOW_MODEL_PYTHON`, `LABFLOW_STUDIO_NODE`, `LABFLOW_RECORDS_NODE`, `LABFLOW_ADMIN_NODE`를 모두 활성화했습니다. 사용자 로그 보관함과 운영 DB는 사용하지 않았습니다.

자동 로그 검사에서는 완성 로그의 서버 응답을 확인한 뒤 디스크 보관 개수가 미완성 출력 1개인지 검증합니다. Heartbeat와 로그 전송은 별개로 진행하므로 완료 조건을 최대 10초까지 기다리며, 기한 내 조건을 만족하지 않으면 실패합니다.

## 운영 반영 전 DB 전환

저장소의 main 반영은 운영 프로세스 재시작이나 DB 전환을 수행하지 않습니다. 기존 DB는 이력이 없으면 Flyway가 기동을 거부하므로 백업·복제본 검증 후 `schemaCheck`와 명시적 `schemaBaseline`이 필요합니다. [DB 전환 절차](../backend/database/README.md)를 따릅니다. 신규 DB에는 V1을 자동 적용합니다.
