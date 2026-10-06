# 구현 진행표

갱신: 2026-10-06, Asia/Seoul

## 현재 단계

1·2단계 결과를 유지하면서 3단계 가능 시간 V01~V06, 단발 일정 T01~T04/T09/T12/T13/T15와 공통 일정 변경·자동 배정을 구현했다. T03/T04는 ONCE 일반 일정 계약만 지원하므로 PARTIAL이며 반복·처방 일정은 4단계 이후 범위다.

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
| 공통 멱등·preview 기반 | DONE | guard → 최신 인가 → replay 순서, HMAC preview의 사용자·operation·payload·state·만료 검증 |
| 가능 시간 V01~V06 | DONE | FULL/PARTIAL/custom/미등록, 근무 제외·자정 분할·병합, preview/save·미래 담당 해제 |
| 단발 일정 조회·생성 | PARTIAL | T01/T02 DONE, T03/T04는 ONCE 일반 일정만 구현. 반복·MEDICATION 계약은 미구현 |
| 배정·완료·이력 | DONE | T09/T12/T13/T15, 전 공동체 충돌, 대리 완료, 재열기, audit/outbox |
| 4단계 일정 기능 | NOT_STARTED | 반복·일괄 수정/삭제·4시간 묶음·T10/T11/T14·G07 이후 |
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
| `gradlew.bat test --no-daemon` | PASS, 2단계까지 전체 16개 |
| opaque token 저장 | PASS, 32바이트 난수/base64url 43자 및 DB SHA-256 32바이트 비교 |
| 멱등 동시 요청·권한 상실 replay | PASS, 동일 응답·업무 version 1회 증가·LEFT 전환 뒤 403 |
| 실제 PostgreSQL 제약 | PASS, V1 CHECK 제약 및 트랜잭션 내 audit/outbox/idempotency 확인 |
| `gradlew.bat test --tests com.kw.knowone.Phase3IntegrationTests --no-daemon` | PASS, 3단계 PostgreSQL 통합 테스트 9개 |
| `gradlew.bat test --no-daemon` | PASS, 최종 전체 25개 테스트 |
| `gradlew.bat clean test bootJar --no-daemon` | PASS, 전체 회귀·실행 JAR 생성 |
| 가능 시간 계산 | PASS, FULL/PARTIAL/custom/미등록, 인접 병합, 자정 넘는 근무·업무, null version 경쟁·강제 DB 실패 전체 롤백 |
| preview/save | PASS, preview 무변경, 위변조·상태 경쟁 409, 미래 부적합 담당만 해제, OPEN AVAILABILITY 사건 |
| 자동 배정·충돌 | PASS, priority→KST 주간 건수→member ID, 전 공동체 충돌, 맞닿은 구간 비충돌, 동시 생성 직렬화 |
| 일정 상태 변경 | PASS, 미배정 정상 생성, 수동 RECIPIENT 배정, 대리 완료, 재열기, 변경 이력, 멱등 replay |

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
| V01 | DONE | 활동시간·정렬된 근무 구간·user/work config version 조회 |
| V02 | DONE | 설정 변경 영향·직접 날짜 clipping·담당 해제 preview |
| V03 | DONE | guard 재검증·원자 저장·미래 부적합 담당 해제·멱등성 |
| V04 | DONE | 반열린 날짜 범위의 등록·미등록 날짜 전체 반환 |
| V05 | DONE | expectedDays 정확성·최종 구간·영향 preview |
| V06 | DONE | null version 경쟁·전체 원자 저장·담당 해제·멱등성 |
| T01 | DONE | 범위·상태·담당·미배정·경과·기록 필터, 안정 커서 페이지네이션 |
| T02 | DONE | 권한 검사 후 Task DTO 상세와 파생 overdue 반환 |
| T03 | PARTIAL | ONCE 일반 일정·14일 horizon·결정적 자동 배정 구현; 반복·MEDICATION 미구현 |
| T04 | PARTIAL | 현재 revision 규칙·약 연결 조회 구현; 반복/처방 생성 계약은 다음 단계 |
| T09 | DONE | ACTIVE 구성원·가능 시간·전 공동체 충돌·version·열린 인계 종료 |
| T12 | DONE | 미배정/경과 포함 완료, performedBy/completedBy 분리, 열린 인계 종료 |
| T13 | DONE | 완료 snapshot audit, 담당 재검증, 부적합 해제·미경과 OPEN 사건 |
| T15 | DONE | audit 기반 최소 변경 DTO, createdAt/id 역순 커서 |

## 다음 단계

4단계에서는 반복 생성, 일괄 수정/삭제, 4시간 묶음, 인계 요청/수락/목록과 G07 이후를 구현한다. 현재 범위에는 JWT, refresh token, 프론트, 처방 확인, AI, 푸시 발송, 실제 배포가 포함되지 않았다.

## 3단계 미검증·제한

- preview 만료는 주입 Clock 단위 테스트 기반 서비스 검증만 유지했고 실제 5분 대기 시험은 수행하지 않았다.
- 날짜 범위 전체 롤백은 실제 PostgreSQL 강제 실패로 확인했으나 수백 날짜 부하 한계는 아직 측정하지 않았다.
- 반복·MEDICATION 생성, 4시간 묶음, T10/T11/T14는 의도적으로 미구현이며 T03/T04 전체 계약 완료로 표시하지 않았다.
- 외부 AI·파일·실제 Web Push 호출은 수행하지 않았고 notification_event outbox 적재까지만 검증했다.

## 환경별 DB 주의사항

- local/demo는 `db/migration`과 `db/demo`를 함께 읽어 V2 가상 시드를 적용한다. prod/test는 `db/demo`를 읽지 않는다.
- local 기본 DB는 `hungry`, demo 기본 DB는 `hungry_demo`, test는 `hungry_test` 전용 스키마, prod는 필수 `DB_URL`의 별도 DB를 사용한다.
- demo DB를 prod로 재사용하지 않는다. V2 가상 데이터와 적용 이력이 남으며 prod migration 경로에서 V2가 누락된 것으로 검증될 수 있다.
- 운영 시작은 빈 prod DB에 운영 migration만 적용한다. 이미 적용된 V1/V2나 `flyway_schema_history`를 수정·삭제하지 않는다.
