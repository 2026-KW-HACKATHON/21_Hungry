# 구현 진행표

갱신: 2026-10-06, Asia/Seoul

## 현재 단계

1단계 결과를 유지하면서 2단계 인증 A01~A04, 공동체 G01~G06과 공통 인증·인가·멱등성·preview 기반을 구현했다. JWT, refresh token, G07 이후 기능은 구현하지 않았다.

| 영역 | 상태 | 근거 |
|---|---|---|
| 기존 저장소·프론트 확인 | DONE | 기존 `21_Hungry_BE`, `21_Hungry_FE` 유지; 프론트 변경 없음 |
| Java/Boot/Gradle 기반 | DONE | Java 21, Boot 4.1.1, Gradle 9.7.1 컴파일·빌드 성공 |
| PostgreSQL/Flyway V1 | DONE | PostgreSQL 18.6 빈 DB에 V1 적용, 애플리케이션 테이블 28개 확인 |
| local/demo 시드 | DONE | 계정 4, 공동체 1, 멤버십 4, 알림 설정 4, 14일 가능 시간 확인 |
| JPA 설정 | DONE | `ddl-auto=validate`, Open Session in View 비활성화 |
| 오류/requestId/Clock/CORS | DONE | 통합 테스트 및 실행 중 HTTP 확인 |
| 기본 보안 | DONE | 상태/health만 공개, `/api/v1/**` 미인증 401 공통 envelope 확인 |
| 인증 A01~A04 | DONE | opaque token, 사용자별 복수 세션, 현재 세션 로그아웃, ACTIVE 계정 검사 |
| 공동체 G01~G06 | DONE | 실제 group_id ACTIVE 멤버십, 가입 예외 조건, 우선순위 버전 검사 |
| 공통 멱등·preview 기반 | DONE | G04/G06 replay·충돌·동시성, HMAC preview 발급/검증 서비스. 3단계 endpoint 없음 |
| 업무 API G07 이후 | NOT_STARTED | 3단계 이후 범위 |
| CI/Docker/배포 초안 | DONE | BE CI, Dockerfile, prod Compose, Nginx 예시 추가; 실제 배포는 미실행 |
| 외부 HTTPS/CORS | BLOCKED | 실제 API 도메인·인증서·Vercel Origin·배포 자격증명 필요 |
| 실기기 녹음 | BLOCKED | iPhone/Android, 확정 MIME·코덱, HTTPS Origin 필요 |
| 실제 Web Push | BLOCKED | VAPID 키, 실제 기기 구독, HTTPS Origin 필요 |

## 실행한 검증

| 명령/검사 | 결과 |
|---|---|
| `gradlew.bat --no-daemon classes testClasses` | PASS |
| `docker compose up -d --wait postgres` | PASS, PostgreSQL 18.6 healthy |
| `gradlew.bat --no-daemon clean test bootJar` | PASS, 테스트 6개·bootJar 성공 |
| `docker compose config -q` / prod `--no-interpolate -q` | PASS |
| `docker build -t 21-hungry-be:phase1 .` | PASS, Java 21 다단계 이미지 생성 |
| DB 설계서 DDL과 V1 줄 단위 비교 | PASS, 582/582줄 차이 0 |
| 빈 DB V1 migration | PASS, Flyway schema version 1·애플리케이션 테이블 28개 |
| local profile V2 migration | PASS, Flyway schema version 2 |
| local 웹 앱 실행 | PASS, 포트 18080에서 Spring Boot 시작 |
| `GET /internal/status` | PASS, 200·동일 requestId·KST `+09:00` |
| 보호된 `/api/v1/care-groups` | PASS, 401 공통 오류 envelope |
| demo seed SQL 조회 | PASS, 사용자 4·멤버 4·guard 1·가능 날짜 2026-10-05~2026-10-18 |
| `gradlew.bat test --no-daemon` | PASS, 전체 16개(2단계 PostgreSQL 통합 테스트 10개 포함) |
| opaque token 저장 | PASS, 32바이트 난수/base64url 43자 및 DB SHA-256 32바이트 비교 |
| 멱등 동시 요청·권한 상실 replay | PASS, 동일 응답·업무 version 1회 증가·LEFT 전환 뒤 403 |
| 실제 PostgreSQL 제약 | PASS, V1 CHECK 제약 및 트랜잭션 내 audit/outbox/idempotency 확인 |

초기 PostgreSQL 18 Compose는 예전 데이터 마운트 경로 때문에 실패했고 `/var/lib/postgresql`로 수정했다. demo seed 첫 실행은 UNAVAILABLE 행의 `custom_intervals` 조건 때문에 롤백됐고, 조건 수정 후 전체 적용에 성공했다. 실패한 V2는 트랜잭션 롤백되어 적용 이력에 남지 않았다.

## API 진행표

| ID | 상태 | 검증 요약 |
|---|---|---|
| A01 | DONE | ACTIVE DEMO 계정 최소 정보만 반환 |
| A02 | DONE | 4계정 로그인, opaque token hash 저장, 다중 세션 |
| A03 | DONE | 현재 ACTIVE 사용자 정보와 version 반환 |
| A04 | DONE | 현재 세션만 revoke, 다른 세션 유지 |
| G01 | DONE | 내 ACTIVE 멤버십 공동체 전체 조회 |
| G02 | DONE | 실제 groupId ACTIVE 멤버십, 타 공동체/LEFT 403 |
| G03 | DONE | demo 전화번호 최소 조회와 사용자별 분당 제한 |
| G04 | DONE | 신규·중복·재가입, guard/audit/outbox/idempotency |
| G05 | DONE | ACTIVE 구성원만 priority/ID 순 정렬 |
| G06 | DONE | 권한·중첩 ID 소속·expectedVersion·원자 변경·멱등성 |

## 다음 단계

다음 요청 범위는 3단계에서 별도로 정한다. 현재 2단계에는 JWT, refresh token, 프론트 구현, G07 이후 업무 API와 실제 배포가 포함되지 않았다.

## 환경별 DB 주의사항

- local/demo는 `db/migration`과 `db/demo`를 함께 읽어 V2 가상 시드를 적용한다. prod/test는 `db/demo`를 읽지 않는다.
- local 기본 DB는 `hungry`, demo 기본 DB는 `hungry_demo`, test는 `hungry_test` 전용 스키마, prod는 필수 `DB_URL`의 별도 DB를 사용한다.
- demo DB를 prod로 재사용하지 않는다. V2 가상 데이터와 적용 이력이 남으며 prod migration 경로에서 V2가 누락된 것으로 검증될 수 있다.
- 운영 시작은 빈 prod DB에 운영 migration만 적용한다. 이미 적용된 V1/V2나 `flyway_schema_history`를 수정·삭제하지 않는다.
