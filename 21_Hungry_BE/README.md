# 21 Hungry Backend

가족 돌봄 서비스의 Spring Boot 백엔드입니다. 1단계 기반 위에 2단계 인증 A01~A04와 공동체 G01~G06을 구현했습니다. 나머지 업무 API는 아직 구현하지 않았습니다.

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
- `FILE_STORAGE_ROOT`, `AUDIO_TEMP_ROOT`: 후속 파일 기능용 비공개 경로
- `PREVIEW_SIGNING_SECRET`: 32바이트 이상의 preview HMAC 비밀값. demo/prod에서 반드시 별도 주입
- `AUTH_SESSION_TTL_SECONDS`, `IDEMPOTENCY_TTL_SECONDS`, `PREVIEW_TTL_SECONDS`: 기본 86400/86400/300초

AI·음성·VAPID 관련 값은 후속 단계 계약을 위한 빈 자리이며 현재 코드에서 사용하지 않는다. 비밀값을 저장소에 커밋하지 않는다.

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
- VAPID 키와 실제 기기 구독이 없어 Web Push 시험을 실행하지 않았다.
- 위 항목은 입력이 확보된 뒤 별도 기술 시험으로 검증한다. 현재 로컬 성공을 외부 연동 성공으로 간주하지 않는다.
