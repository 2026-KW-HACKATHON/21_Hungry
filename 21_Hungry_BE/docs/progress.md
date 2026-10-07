# 구현 진행표

갱신: 2026-10-07, Asia/Seoul

## 현재 단계

중단 당시 저장돼 있던 5단계 전체와 6단계 OpenAI/R01~R04/MEDICATION 골격을 복구해 계약 누락을 완성했다. Docker Desktop의 기존 PostgreSQL 18.6 볼륨을 초기화하지 않고 재사용했으며 전체 80개 자동 테스트와 bootJar가 통과했다. 실제 OpenAI 호출 코드는 연결됐지만 환경에 `OPENAI_API_KEY`가 없어 유료 실호출·한국어 모델 품질/지연/사용량 평가는 BLOCKED다.

복구 당시 문서에는 실제 코드보다 뒤처진 “5단계/fake AI/R01~R04 미구현” 상태가 남아 있었고 README도 2단계까지만 구현됐다고 적혀 있었다. 코드·테스트를 기준으로 재분류해 이 문서와 README를 갱신했다. 요청에 적힌 `AGENTS.md`와 `docs/ai/processing-contract-v1.0.md`의 실제 위치는 각각 `docs/AGENTS.md`, `docs/processing-contract-v1.0.md`였다. 중단 세션의 미커밋 파일을 재사용했으며 적용된 migration은 변경하지 않았다.

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
| 일반·복약 일정 조회·생성 | DONE | T01~T04 일반 반복과 확정 처방만 사용하는 MEDICATION 생성·조회·동일 패턴 병합·날짜별 snapshot 검증 |
| 배정·완료·이력 | DONE | T09/T12/T13/T15, 전 공동체 충돌, 대리 완료, 재열기, audit/outbox |
| 4단계 일정 기능 | DONE | 일반 일정 T05~T08, 4시간 묶음, T10/T11/T14, G07과 실제 current revision 기반 G08 집계 검증 |
| 5단계 기록·파일·작업 | DONE | E01~E15, PostgreSQL·임시 저장소·파기·작업 fencing 전체 회귀 완료 |
| 6단계 AI·후보·처방 | CODE DONE / LIVE BLOCKED | 실제 OpenAI adapter, schema/prompt 1.0, 서버 의미·근거 검증, TASK 자동 적용, R01~R04와 복약 일정 연결 완료. API 키 부재로 실호출만 미검증 |
| 비공개 파일 저장소 | PARTIAL | 서버 생성 object key, 경로 이탈 방지, 임시→검증→승격, 실패 보상·고아 임시 파일 청소와 삭제 재시도 검증. 운영 영속 볼륨 미검증 |
| AI 작업 워커 | CODE DONE / LIVE BLOCKED | SKIP LOCKED, lease 회수/fencing, stale OBSOLETE, 고정 동시성, timeout/lease 시작 검증, 제한 재시도와 fake/mock 자동 검증. 실제 공급자 호출은 키 부재 |
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
| `gradlew.bat test --tests com.kw.knowone.Phase4IntegrationTests --tests com.kw.knowone.PreviewTokenServiceTests --no-daemon` | PASS, 4단계 PostgreSQL 통합 9개·Clock 단위 1개(탈퇴/수락 경쟁 포함) |
| `gradlew.bat clean test bootJar --no-daemon` (4단계 최종) | PASS, 전체 34개 테스트·실행 JAR 생성; 이후 추가한 탈퇴/수락 경쟁 1개도 단독 PASS |
| `gradlew.bat classes testClasses --no-daemon` (5단계) | PASS, 신규 E01~E15·파일/worker·4단계 보강 테스트 컴파일 |
| `gradlew.bat test --tests com.kw.knowone.FileValidationServiceTests --tests com.kw.knowone.PreviewTokenServiceTests --no-daemon` | PASS, 파일 signature/decoder·손상/빈 파일·10/11페이지·10MB 초과와 Clock preview 5개 |
| `docker compose up -d --wait postgres`, `docker compose ps` (5단계) | PASS, PostgreSQL 18.6 컨테이너 healthy, localhost:54329 |
| `gradlew.bat test --tests com.kw.knowone.Phase4IntegrationTests --no-daemon` | PASS, 4단계 PostgreSQL 통합 10개; 재실행/동시/서비스 재생성 중복 방지, 취소 복구, 탈퇴 후 타 공동체·세션·구독 보존 포함 |
| `gradlew.bat test --tests com.kw.knowone.Phase5IntegrationTests --tests com.kw.knowone.FileValidationServiceTests --no-daemon` | PASS, PostgreSQL·임시 저장소·fake AI 통합 11개와 파일 parser 경계 6개 |
| `gradlew.bat clean test bootJar --no-daemon` (5단계 최종) | PASS, 전체 53개 테스트(실패·오류·skip 0)·실행 JAR 생성 |
| `gradlew.bat test --tests "com.kw.knowone.encounter.processing.*" --tests "com.kw.knowone.Phase6IntegrationTests"` | PASS, 6단계 집중 24개 테스트 |
| `gradlew.bat test bootJar` (6단계 최종) | PASS, 11 suites·전체 80개(실패·오류·skip 0)·실행 JAR 생성 |
| `docker compose ps` (6단계 최종) | PASS, 기존 `21_hungry_be-postgres-1` / PostgreSQL 18.6 healthy, 포트 54329 |
| `scripts/openai-live-api-smoke.ps1` | BLOCKED, `OPENAI_API_KEY` 없음. 기존 `EncounterId` 우선 조회 경로와 신규 WAV·이미지·PDF→E03→R02→T01 경로의 구문만 검증 |
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
| G07 | DONE | CAREGIVER 탈퇴·LEFT 보존·미래 담당 해제·MEMBER_LEFT 인계·미발송 알림 차단·재가입 행 재사용 |
| G08 | DONE | 일정 3분류와 실제 encounter/current revision의 NEEDS_REVIEW 집계를 R02 상태 전이까지 PostgreSQL 검증 |
| E01 | DONE | 실제 encounter 생성·멱등·ACTIVE 공동체 검사와 PostgreSQL 검증 |
| E02 | DONE | 날짜/type/occurredOn DESC NULLS LAST cursor 목록과 실제 행 검증 |
| E03 | CODE DONE / LIVE BLOCKED | 실제 provider metadata를 포함한 current revision/source/job/review item/연결 업무 결합. 실호출은 키 부재 |
| E04 | DONE | 메타 version 변경·inputVersion 유지와 PostgreSQL 검증 |
| E05 | CODE DONE / LIVE BLOCKED | 실제 signature/parser/decoder, 원자 source·Responses OCR job, 10MB/10페이지/10장 잠금 검증. 실호출은 키 부재 |
| E06 | CODE DONE / LIVE BLOCKED | WAV parser·25MB·1200초·24시간 expiresAt·Audio Transcriptions 연결. 실기기/실제 전사는 미검증 |
| E07 | DONE | 활성 source 원문·상태와 타 공동체 차단 검증 |
| E08 | CODE DONE / LIVE BLOCKED | READY AUDIO 전사 수정·text/input version 증가·stale OBSOLETE·재분석 연결 검증. 실호출은 키 부재 |
| E09 | DONE | 활성 DOCUMENT+AVAILABLE 비공개 스트리밍, no-store/nosniff/안전 filename·타 공동체 차단 검증 |
| E10 | DONE | 제거·inputVersion 증가·오래된 lease 무효화·남은 입력 재큐잉·DELETE_PENDING·재분석 중복 차단 검증 |
| E11 | DONE | 입력 버전별 작업 목록과 lease 비노출 구현·검증 |
| E12 | DONE | FAILED 포함 작업 상세·canRetry 파생 구현·검증 |
| E13 | CODE DONE / LIVE BLOCKED | 최신 입력·소스/원본 상태, 429 Retry-After/timeout만 제한 재시도, 입력한도/refusal/incomplete 비재시도, 저장 텍스트 재사용 검증 |
| E14 | DONE | 소스·기록 유래 약·단독/공유 미래 업무 영향 preview를 확정 처방/R04와 함께 검증 |
| E15 | DONE | 즉시 접근 차단·OBSOLETE·공유 복약 업무 보존·단독 업무 취소·실제 파일/민감 데이터 파기 회귀 검증 |
| R01 | DONE | 현재 revision 후보, 상태 필터·cursor·근거와 version·공동체 권한 검증 |
| R02 | DONE | expectedInputVersion/후보 version, 일괄 롤백, SOURCE/MANUAL 충돌 해소, 처방·시리즈·발생·snapshot·배정·audit·outbox·멱등 원자 적용 |
| R03 | DONE | 후보 version 확인·DISMISSED 전이·audit·멱등. 기존 처방 중단과 분리 |
| R04 | DONE | 확정 처방 encounter/onDate 필터·cursor·공동체 권한 검증 |
| V01 | DONE | 활동시간·정렬된 근무 구간·user/work config version 조회 |
| V02 | DONE | 설정 변경 영향·직접 날짜 clipping·담당 해제 preview |
| V03 | DONE | guard 재검증·원자 저장·미래 부적합 담당 해제·멱등성 |
| V04 | DONE | 반열린 날짜 범위의 등록·미등록 날짜 전체 반환 |
| V05 | DONE | expectedDays 정확성·최종 구간·영향 preview |
| V06 | DONE | null version 경쟁·전체 원자 저장·담당 해제·멱등성 |
| T01 | DONE | 범위·상태·담당·미배정·경과·기록 필터, 안정 커서 페이지네이션 |
| T02 | DONE | 권한 검사 후 Task DTO 상세와 파생 overdue 반환 |
| T03 | DONE | 일반 ONCE/DAILY/WEEKLY와 같은 공동체 확정 처방 기반 MEDICATION 생성, 동일 시각/패턴 병합, 중복·기간 snapshot 검증 |
| T04 | DONE | 현재 revision 반복 규칙과 확정 처방 조회, 처방 변경의 미래 snapshot 갱신·시작/완료 이력 보존 검증 |
| T05 | DONE | cutoff·영향 버전·예외 덮어쓰기·미생성 미래 반복분·담당 해제 미리보기 |
| T06 | DONE | 일반 일정 이번만 anchor 유지 수정 및 SERIES_ALL_PENDING 새 revision·RULE_CHANGED 복구·사용자 취소 보존 |
| T07 | DONE | OCCURRENCE/SERIES_FROM_SELECTED의 anchor 기준 취소 영향·완료 보존·이동 예외 미리보기 |
| T08 | DONE | 단건 포함 previewToken 필수·USER_ONE/USER_FUTURE tombstone·stopFromDate·인계/outbox/delivery 원자 종료 |
| T09 | DONE | ACTIVE 구성원·가능 시간·전 공동체 충돌·version·열린 인계 종료 |
| T10 | DONE | 담당 해제·이전 담당자·USER_REQUEST OPEN·audit/outbox 원자 반영, 경과/중복 거부 |
| T11 | DONE | 두 version·ACTIVE·가능 시간·전 공동체 충돌 재검증, 동시 수락 한 명 성공, EXPIRED 별도 커밋 |
| T12 | DONE | 미배정/경과 포함 완료, performedBy/completedBy 분리, 열린 인계 종료 |
| T13 | DONE | 완료 snapshot audit, 담당 재검증, 부적합 해제·미경과 OPEN 사건 |
| T14 | DONE | 상태 필터·createdAt/id 역순 커서·현재 Task snapshot·권한 검사 |
| T15 | DONE | audit 기반 최소 변경 DTO, createdAt/id 역순 커서 |

## 다음 단계

서버 런타임에 실제 키를 주입한 뒤 가상 WAV·이미지·PDF로 별도 smoke를 1회 실행해 모델 접근·한국어 약명/숫자 품질·지연·token 사용량을 측정한다. 이어 실제 iPhone/Android 출력 표본으로 컨테이너·코덱을 확정한다. Web Push·프론트·배포는 7단계 범위다.

## 6단계 미검증·제한

- `OPENAI_API_KEY`는 존재 여부만 확인했으며 현재 없음. 유료 실호출, 모델 접근 가능 여부, 실제 사용량·지연·한국어 의료 텍스트 정확도는 BLOCKED이고 fake/mock 성공으로 대체하지 않았다.
- 코드 기본 모델은 `gpt-transcribe`와 `gpt-5.4-mini-2026-03-17`이다. 2026-10-07 공식 문서상 전사, 이미지 입력, Responses Structured Outputs 지원을 확인했지만 실제 모델 평가는 위 사유로 잠정이다. URL과 선택 근거는 `decisions.md` D38~D41에 기록했다.
- OpenAI Java SDK 4.78.0 호환 범위는 공식 문서로 확인했지만 이 구현은 JDK `HttpClient`를 사용한다. 공급자 SDK 자동 재시도는 없으며 DB 작업 재시도만 적용한다.
- Responses는 `store=false`이고 provider 오류 본문·의료 원문·키를 일반 로그에 남기지 않는다. 성공 로그는 job/type/model/token/지연만 기록한다. 실제 조직의 data controls/abuse monitoring 설정은 계정 접근이 없어 미확인이다.
- 음성은 실제 WAV parser가 검증하는 WAV만 지원한다. OpenAI 지원 MIME 목록을 이유로 모바일 형식을 넓히지 않았으며 iPhone/Android와 변환/임시 파일 정리는 미구현·미검증이다.
- 제품 한도(문서 10,000,000 bytes/PDF 10페이지/이미지 10장, 음성 25,000,000 bytes/1200초)와 공급자 한도는 별개다. 분석 text context는 300,000 Unicode codepoint를 넘으면 명시적으로 `AI_INPUT_LIMIT_EXCEEDED` 처리하며 몰래 자르지 않는다.

## 5단계에서 이어지는 미검증·제한

- 실제 OpenAI adapter는 6단계에서 추가했지만 이번 환경의 키 부재로 호출 결과는 없다. test 프로필만 명시적 fake를 쓰며 prod/demo 기본값은 AI worker 비활성이다.
- AI 비활성 환경의 접수 작업은 영구 QUEUED로 두지 않고 `FAILED/AI_NOT_CONFIGURED`로 노출한다.
- 음성은 서버가 실제로 파싱 가능한 WAV(`audio/wav`, `audio/x-wav`)만 임시 지원 설정에 넣었다. iPhone/Android 컨테이너·코덱과 실제 기기는 미검증이므로 최종 지원 목록이 아니다.
- 로컬 비공개 파일 저장은 구현했지만 EC2 영속 볼륨·백업·권한은 미검증이다. 공개 정적 경로는 제공하지 않는다.
- PDFBox parser와 ImageIO/TwelveMonkeys decoder로 PDF/JPEG/PNG/WEBP를 검증한다. WEBP decoder 단위 검증은 통과했으나 실제 모바일 생성 파일 표본은 미검증이다.
- 10장 동시 업로드, 멱등 replay 객체 정리, lease 만료 회수/이전 worker 차단, 소스 추가·제거·전사 수정 stale 차단, 부분 실패·재시도, 음성 만료·삭제 실패 재시도, 공유 복약 업무 보존과 민감 데이터 파기를 PostgreSQL에서 검증했다.

## 4단계 미검증·제한

- preview 만료는 주입 Clock 단위 테스트로 검증했고 실제 5분 대기는 의도적으로 수행하지 않았다.
- 날짜 범위 전체 롤백은 실제 PostgreSQL 강제 실패로 확인했으나 수백 날짜 부하 한계는 아직 측정하지 않았다.
- MEDICATION 생성·처방 확인·미래 변경은 6단계에서 구현했고 과거/시작/완료 occurrence snapshot을 보존한다.
- G08 진료 확인 건수는 현재 revision의 실제 NEEDS_REVIEW 후보와 R02/R03 전이를 집계한다.
- 외부 AI·파일 실호출은 수행하지 않았다. 7단계에서 Web Push 전송 코드까지 연결했지만 VAPID/브라우저 구독이 없어 실제 provider 호출은 수행하지 않았다.

## 7단계 알림함·outbox·Web Push

갱신: 2026-10-07, Asia/Seoul.

| 영역 | 상태 | 근거 |
|---|---|---|
| N01~N08 | CODE DONE | 본인/ACTIVE 공동체 알림함, cursor·unreadCount, 최초 readAt, DAILY/ONCE version, 공개 VAPID config, 구독 등록·계정 전환·목록·해제 구현 |
| outbox 확장 | CODE DONE | V1 `notification_event`를 SKIP LOCKED claim하고 수신자별 `notification`, 활성 기기별 `notification_delivery`를 한 트랜잭션에서 unique 제약과 함께 생성 |
| 예약·digest | CODE DONE | 시작 30분 전/시작/경과 event_key, task version 변경 시 구예약 취소·미래 예약 재생성, 09:00 KST DAILY digest와 당일 최초 OPEN 알림 제외 구현 |
| Web Push | CODE DONE / LIVE BLOCKED | web-push-java 5.1.2 암호화/VAPID + redirect 없는 JDK HttpClient, HTTPS/provider allowlist/DNS 사설주소 차단, 404/410·429·timeout·lease fencing 구현. VAPID/브라우저 구독 부재로 외부 provider 호출 안 함 |
| DB migration | NOT NEEDED | DB 설계 v1.2의 기존 28개 테이블·부분 unique·composite FK로 계약 충족. V1/V2 변경 없음 |
| AI worker lease 재검토 | VERIFIED | claim 한 번에 provider 호출 1회이고 backoff는 DB 재예약이다. 90초 call timeout/180초 lease 검증을 유지하며 앱 내부 다중 provider retry는 없음. OpenAI 실호출은 키 부재로 계속 BLOCKED |

N01~N08은 모두 코드 및 PostgreSQL 자동 테스트 완료다. 실제 push provider 2xx 수락, Service Worker 표시, notificationclick, iPhone/Android 실기기는 VAPID 키·HTTPS origin·브라우저 구독이 없어 BLOCKED이며 DONE으로 간주하지 않는다.

7단계 집중 테스트는 `gradlew.bat test --tests com.kw.knowone.Phase7IntegrationTests`로 실행한다. 설정 version 경쟁, 악성 endpoint/키, 계정 전환과 과거 FK, outbox/delivery 중복 방지, readAt, 탈퇴 필터, 404/410, 429, 최대 시도, lease fencing, task version 예약, DAILY 중복 제외, KST 09:00 경계를 실제 PostgreSQL에서 검증한다.

최종 `gradlew.bat test bootJar`는 12 suites / 89 tests / failures 0 / errors 0 / skipped 0으로 통과했고, 기존 PostgreSQL 18.6 컨테이너는 healthy였다.

| API | 상태 |
|---|---|
| N01 | CODE DONE / PostgreSQL TESTED |
| N02 | CODE DONE / PostgreSQL TESTED |
| N03 | CODE DONE / PostgreSQL TESTED |
| N04 | CODE DONE / PostgreSQL TESTED |
| N05 | CODE DONE / LIVE CONFIG BLOCKED |
| N06 | CODE DONE / PostgreSQL TESTED |
| N07 | CODE DONE / PostgreSQL TESTED |
| N08 | CODE DONE / PostgreSQL TESTED |

## 환경별 DB 주의사항

- local/demo는 `db/migration`과 `db/demo`를 함께 읽어 V2 가상 시드를 적용한다. prod/test는 `db/demo`를 읽지 않는다.
- local 기본 DB는 `hungry`, demo 기본 DB는 `hungry_demo`, test는 `hungry_test` 전용 스키마, prod는 필수 `DB_URL`의 별도 DB를 사용한다.
- demo DB를 prod로 재사용하지 않는다. V2 가상 데이터와 적용 이력이 남으며 prod migration 경로에서 V2가 누락된 것으로 검증될 수 있다.
- 운영 시작은 빈 prod DB에 운영 migration만 적용한다. 이미 적용된 V1/V2나 `flyway_schema_history`를 수정·삭제하지 않는다.
