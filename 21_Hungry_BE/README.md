# 21 Hungry Backend

가족 돌봄 서비스의 Spring Boot 백엔드입니다. 인증·공동체·가능시간·일정·기록/파일·AI 후보 처리·처방 확인·복약 일정·알림함과 Web Push 전송 파이프라인을 구현했습니다. 제품 프론트와 실제 배포는 포함하지 않습니다.

## 기술 버전

| 구성 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Framework | 7.0.9 |
| Spring Security | 7.1.1 |
| Gradle Wrapper | 9.7.1 |
| PostgreSQL | 18.6 |
| Flyway | 12.4.0 |
| PostgreSQL JDBC | 42.7.13 |
| Hibernate ORM | 7.4.5.Final |
| web-push-java | 5.1.2 |

Spring Boot 4.1.1은 Java 17~26과 Gradle 8.14 이상 또는 9.x를 지원한다. 이 프로젝트는 Java 21과 Gradle 9.7.1로 고정했다.

- Spring Boot 시스템 요구사항: https://docs.spring.io/spring-boot/system-requirements.html
- Gradle Java 호환성: https://docs.gradle.org/9.7.1/userguide/compatibility.html
- PostgreSQL 18 문서: https://www.postgresql.org/docs/18/

## 로컬 실행

Docker Desktop을 실행한 뒤 백엔드 디렉터리에서 다음 명령을 사용한다.

```powershell
docker compose up -d --wait postgres
.\gradlew.bat --no-daemon test
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

상태 확인:

```powershell
Invoke-RestMethod http://localhost:8080/internal/status
```

`/internal/status`는 배포 및 개발 검증 전용이며 API 명세의 `/api/v1` 업무 API 60개에 포함되지 않는다. `/internal/status`와 Actuator liveness/readiness 외 경로는 기본적으로 인증이 필요하다. 2단계에서 opaque Bearer 인증을 연결하기 전에는 보호 경로가 401을 반환한다.

종료:

```powershell
docker compose down
```

위 명령은 DB 볼륨을 보존한다. 로컬 DB 데이터를 삭제하는 `docker compose down -v`는 명시적으로 초기화할 때만 사용한다.

## 프로필과 DB

| 프로필 | Flyway 위치 | 시연 데이터 |
|---|---|---|
| `local` | `db/migration`, `db/demo` | 포함 |
| `demo` | `db/migration`, `db/demo` | 포함 |
| `test` | `db/migration` (`hungry_test` 스키마) | 미포함 |
| `prod` | `db/migration` | 미포함 |

`V1__family_care_schema.sql`은 DB 설계 v1.2의 28개 테이블 DDL과 동일하다. `V2__demo_seed.sql`은 local/demo 프로필에만 포함되며 다음 가상 데이터를 만든다.

- 사전 등록 계정 4개
- 공동체 1개와 ACTIVE 멤버십 4개
- 알림 설정 4개
- 보호자 3명의 주간 근무 구간
- 실행일 기준 14일의 FULL/PARTIAL/UNAVAILABLE 가능 시간

`schedule_guard(id=1)`은 업무 스키마의 필수 잠금 행이므로 V1에 포함된다. 적용된 Flyway migration은 수정하지 않고 이후 변경은 새 버전 migration으로 추가한다. Hibernate는 `ddl-auto=validate`만 사용한다.

환경별 DB는 분리한다. 기본값은 local이 `hungry`, demo가 `hungry_demo`, test가 `hungry` DB 안의 `hungry_test` 전용 스키마이며, prod는 기본 URL 없이 별도의 `DB_URL`을 반드시 주입한다. local/demo의 V2는 가상 계정 네 개(`demo-recipient`, `demo-caregiver-1`~`3`)와 가상 전화번호를 넣지만 prod migration 경로에는 V2가 없다.

로컬 Compose는 `hungry`만 자동 생성한다. demo 프로필을 별도 기본 DB로 실행하려면 최초 한 번 `docker compose exec postgres createdb -U hungry hungry_demo`로 만들거나 별도 demo `DB_URL`을 지정한다.

demo DB를 prod DB로 전환하거나 prod 프로필에서 재사용하지 않는다. 이미 적용된 V2의 Flyway 이력과 가상 데이터가 남고, prod의 `db/migration` 경로에서는 V2를 해석할 수 없어 검증 실패 또는 데이터 혼입 위험이 있다. 운영 전환은 새 빈 prod DB에 V1과 이후 운영 migration만 적용하고 필요한 실데이터는 별도 검증된 이관 절차로 옮긴다. V1/V2 파일이나 Flyway 이력을 수정·삭제해서 전환하지 않는다.

## 환경변수

전체 계약은 `.env.example`에 있다. Spring Boot가 `.env` 파일을 직접 읽는 것은 아니므로 IDE 실행 구성, 셸 환경 또는 배포 플랫폼에서 주입한다. local 프로필에는 로컬 DB와 `http://localhost:5173` 기본값이 있지만 prod 프로필에는 다음 값이 반드시 필요하다.

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `CORS_ALLOWED_ORIGINS`: 쉼표로 구분한 정확한 Origin 목록. `*` 금지
- `FILE_STORAGE_ROOT`, `AUDIO_TEMP_ROOT`: 비공개 파일 경로
- `PREVIEW_SIGNING_SECRET`: 32바이트 이상의 preview HMAC 비밀값. demo/prod에서 반드시 별도 주입
- `AUTH_SESSION_TTL_SECONDS`, `IDEMPOTENCY_TTL_SECONDS`, `PREVIEW_TTL_SECONDS`: 기본 86400/86400/300초

실제 AI worker는 기본적으로 꺼져 있다. 서버 런타임에만 `OPENAI_API_KEY`를 주입하고 `AI_WORKER_ENABLED=true`로 켠다. 기본 모델은 `gpt-transcribe`, `gpt-5.4-mini-2026-03-17`이며 `OPENAI_TRANSCRIBE_MODEL`, `OPENAI_OCR_MODEL`, `OPENAI_ANALYSIS_MODEL`로 명시 변경한다. timeout/lease는 기본 90초/180초이고 lease가 timeout보다 최소 15초 길지 않으면 시작을 거부한다. `OPENAI_TIMEOUT`, `AI_JOB_LEASE_SECONDS`, `AI_WORKER_CONCURRENCY`, `AI_JOB_MAX_ATTEMPTS`, `OPENAI_MAX_OUTPUT_TOKENS`를 조정할 수 있다. 기존 `OPENAI_ANALYZE_MODEL`, `AI_CALL_TIMEOUT_SECONDS`, `AI_MAX_OUTPUT_TOKENS`도 하위 호환으로 읽는다. 키와 비밀값을 저장소에 커밋하지 않는다.

유료 실호출은 일반 테스트에 포함하지 않는다. 가상 파일 smoke는 `scripts/openai-live-api-smoke.ps1`을 별도로 사용하며, 중단 복구 시 `-EncounterId`를 주면 기존 처리 상태를 먼저 확인하고 새 업로드를 만들지 않는다.

알림함/outbox worker와 외부 push worker는 각각 `NOTIFICATION_EVENT_WORKER_ENABLED`, `PUSH_WORKER_ENABLED`로 켠다. 실제 push worker를 켜려면 서버 런타임에 `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`를 모두 주입해야 하며 누락되거나 잘못된 키는 시작 실패다. `NOTIFICATION_LEASE_SECONDS`는 `PUSH_TIMEOUT_SECONDS`보다 10초 넘게 길어야 한다. 허용 endpoint는 `PUSH_ALLOWED_HOST_SUFFIXES`의 HTTPS 443 provider로 제한되고 DNS 결과의 loopback·사설·link-local 주소와 redirect를 거부한다. 기본 목록은 FCM, Mozilla Autopush, Apple Web Push다. N05는 worker 비활성 또는 VAPID 미설정이면 `{enabled:false,applicationServerKey:null}`을 반환한다.

Web Push payload는 `notificationId`, `eventId`, 일반적인 `title`/`body`, `tag=eventId`, `url=/notifications`만 포함한다. provider의 2xx 수락은 delivery `SENT`일 뿐 기기 표시나 읽음을 의미하지 않는다. 브라우저에서는 보안 컨텍스트에서 사용자 버튼으로 권한을 요청하고 `PushSubscription.toJSON()`을 N06에 등록한 뒤, Service Worker가 `eventId`를 notification tag로 사용하고 `notificationclick`에서 알림 화면을 연 후 인증된 N01 target을 다시 조회해야 한다.

## 공통 HTTP 계약

- JSON 오류는 `error.code`, `error.message`, `error.requestId`, `error.details`를 항상 반환한다.
- 요청의 유효한 UUID `X-Request-Id`를 이어 쓰고, 없거나 잘못되면 새 UUID를 생성한다.
- 애플리케이션 `Clock`은 `Asia/Seoul`로 주입한다.
- CORS는 설정된 Origin만 허용하고 `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Request-Id`를 처리한다.
- preflight `OPTIONS`는 토큰 없이 처리하지만 실제 보호 요청은 인증이 필요하다.
- 인증 Cookie와 CSRF Cookie 흐름은 사용하지 않는다.

## 빌드와 배포 초안

```powershell
.\gradlew.bat --no-daemon clean test bootJar
docker build -t 21-hungry-be:local .
```

상위 저장소의 `.github/workflows/BE-CI.yml`은 PostgreSQL 18.6 서비스에서 테스트 후 boot JAR을 빌드한다. `Dockerfile`은 Java 21 다단계 빌드이며 `compose.prod.yaml`은 EC2에서 API를 loopback 포트에 띄우는 초안이다. `deploy/nginx-api.conf.example`은 HTTPS reverse proxy 자리표시자이며 실제 도메인·인증서·전체 multipart 크기를 확정한 뒤 사용한다.

## 현재 BLOCKED

- 실제 API 도메인, EC2 사양·자격증명, 인증서가 없어 외부 HTTPS와 배포를 실행하지 않았다.
- 실제 Vercel production/preview Origin과 개발 포트 최종값이 없어 외부 CORS 시험을 실행하지 않았다.
- iPhone/Android 기기, 확정 음성 MIME·코덱이 없어 녹음 기술 시험을 실행하지 않았다.
- VAPID 키와 실제 브라우저 구독이 없어 push provider 수락 및 실제 기기 표시 시험을 실행하지 않았다. 구현·PostgreSQL fake 전송 검증과 실제 기기 검증은 구분한다.
- 위 항목은 입력이 확보된 뒤 별도 기술 시험으로 검증한다. 현재 로컬 성공을 외부 연동 성공으로 간주하지 않는다.
