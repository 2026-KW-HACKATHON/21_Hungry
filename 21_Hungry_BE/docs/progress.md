# 구현 진행표

갱신: 2026-10-09, Asia/Seoul

## 현재 단계

2026-10-09: PRD v0.16 / API v1.3의 V07을 구현했다. 같은 공동체 ACTIVE 구성원끼리 날짜별 가능 시간을 조회하며, 원본 설정과 건강 일정은 반환하지 않는다. 기존 V04 계산과 DTO를 재사용하고 수정 경로는 /me만 유지한다. PostgreSQL 통합 회귀를 포함한 gradlew.bat test bootJar --no-daemon에서 16 suites / 109 tests / 실패·오류 0 및 bootJar 생성을 확인했다.

API 명세 기준을 v1.2로 올렸다. v1.1 대비 변경은 E06 녹음 업로드 정정이며, AUDIO metadata에는 `expectedVersion`과 `expectedInputVersion`만 보내고 문서 분류를 보내지 않는다. 현재 구현은 두 버전을 encounter 잠금 후 검사하고, 불일치 시 등록 없이 409를 반환하며, 실제 RIFF/WAVE PCM과 `audio/wav`·`audio/x-wav`, 25,000,000 bytes, 1,200초 제한을 이미 적용하고 있다. 따라서 이 개정에는 애플리케이션 동작 변경이 필요하지 않았고 기준 문서·지침만 v1.2로 전환했다.

PRD v0.15 / DB v1.3 / API v1.1 전환을 시작했다. 제공된 새 기준 문서와 검증 산출물을 보존했고, 기존 V1 및 demo V2를 수정하지 않는 V3 추가 migration을 작성했다. 전화번호 가입·로그인, A03 온보딩 상태, 부모 공동체 원자 생성, 첫 자녀 즉시 연결/후속 승인 대기, 신청 조회·승인·거절·취소, 단일 현재 공동체, priority 1/2, 부모 정보 불변 DB 방어, 개인 미지정 거절과 담당자 본인 인계를 1차 반영했다. 직접 일정은 EXAM/HOSPITAL/OTHER 단건만 허용하고 레거시 PICKUP은 migration에서 OTHER로 이관한다. E05는 파일별 PRESCRIPTION/DIAGNOSIS/MEDICINE_BAG 분류를 검증·저장·응답하며 기존 문서는 LEGACY_UNCLASSIFIED로 이관한다.

`gradlew.bat classes testClasses --no-daemon`은 통과했다. Docker Desktop이 실행 중이 아니어서 V3의 실제 PostgreSQL 적용, 기존 데이터 사전 조회, 전체 통합 테스트는 아직 실행하지 못했다. 전체 테스트 실행은 26개 비DB 테스트 통과 후 DB 연결 불가로 76개 통합 테스트가 시작 단계에서 실패했다. T01 날짜/cursor 정렬, T17 월 캘린더, 09시 개인 digest, 알림 재검증 등은 계속 남아 있으며 이전 102개 성공을 신규 계약 완료로 간주하지 않는다.

중단 당시 저장돼 있던 5단계 전체와 6단계 OpenAI/R01~R04/MEDICATION 골격을 복구해 계약 누락을 완성했다. Docker Desktop의 기존 PostgreSQL 18.6 볼륨을 초기화하지 않고 재사용했다. 8단계에서 worker 경합을 수정하고 배포·복원 리허설을 완료했으며, Git 제외 로컬 키로 완전 합성 OpenAI 전사/OCR/분석/R02/TASK 자동 적용까지 실호출했다.

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
| 가능 시간 V01~V07 | DONE | 본인 조회·수정과 같은 공동체 ACTIVE 구성원 날짜별 가능 시간 조회; FULL/PARTIAL/UNAVAILABLE/미등록, 근무 제외·자정 분할·병합 |
| 일반·복약 일정 조회·생성 | DONE | T01~T04 일반 반복과 확정 처방만 사용하는 MEDICATION 생성·조회·동일 패턴 병합·날짜별 snapshot 검증 |
| 배정·완료·이력 | DONE | T09/T12/T13/T15, 전 공동체 충돌, 대리 완료, 재열기, audit/outbox |
| 4단계 일정 기능 | DONE | 일반 일정 T05~T08, 4시간 묶음, T10/T11/T14, G07과 실제 current revision 기반 G08 집계 검증 |
| 5단계 기록·파일·작업 | DONE | E01~E15, PostgreSQL·임시 저장소·파기·작업 fencing 전체 회귀 완료 |
| 6단계 AI·후보·처방 | LIVE VERIFIED | 실제 OpenAI adapter, schema/prompt 1.0, 서버 의미·근거 검증, TASK 자동 적용, R01~R04와 복약 일정 연결 완료. 합성 WAV/JPEG/PDF와 실제 provider로 확인 |
| 비공개 파일 저장소 | DONE | 서버 생성 object key, 경로 이탈 방지, 임시→검증→승격, 실패 보상·고아 임시 파일 청소와 삭제 재시도 검증. 운영 `/data/private-files` named volume mount와 재시작 독립성을 확인 |
| AI 작업 워커 | LIVE VERIFIED | SKIP LOCKED, lease 회수/fencing, stale OBSOLETE, 고정 동시성, timeout/lease 시작 검증, 제한 재시도 자동 검증과 실제 provider 작업 완료 확인 |
| CI/Docker/운영 배포 | LIVE VERIFIED | main CD의 test/bootJar, 고정 SHA image build, EC2 readiness 배포 성공. 직전 이미지 복구와 current/previous image 상태 파일을 유지 |
| 외부 HTTPS/CORS | LIVE VERIFIED | DNS A, Nginx 80/443, HTTP 301, HTTPS readiness 200, production Origin OPTIONS 200, 인증 오류 CORS, Certbot 갱신 dry-run 확인 |
| 실기기 녹음 | BLOCKED | iPhone/Android, 확정 MIME·코덱, HTTPS Origin 필요 |
| 실제 Web Push | PARTIAL / DEVICE BLOCKED | 운영 VAPID·worker·HTTPS·CORS 연결 완료. 실제 사용자 PushSubscription, provider 수락, 기기 표시와 click은 미검증 |

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
| `scripts/openai-live-api-smoke.ps1` | PASS, 실제 WAV·이미지·PDF→E03→R02→복약 일정 실행. 기존 `EncounterId` 경로의 Windows PowerShell Nullable Guid 문제와 multipart 호환을 수정 |
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
| E03 | LIVE VERIFIED | 실제 provider metadata를 포함한 current revision/source/job/review item/연결 업무 결합을 합성 실호출로 확인 |
| E04 | DONE | 메타 version 변경·inputVersion 유지와 PostgreSQL 검증 |
| E05 | LIVE VERIFIED | 실제 signature/parser/decoder, 원자 source·Responses OCR job, 10MB/10페이지/10장 잠금 검증과 합성 JPEG·2-page PDF 실호출 |
| E06 | LIVE VERIFIED / MOBILE BLOCKED | WAV parser·25MB·1200초·24시간 expiresAt·Audio Transcriptions와 합성 한국어 WAV 전사 확인. iPhone/Android 실기기는 미검증 |
| E07 | DONE | 활성 source 원문·상태와 타 공동체 차단 검증 |
| E08 | CODE DONE / AUTOMATED TESTED | READY AUDIO 전사 수정·text/input version 증가·stale OBSOLETE·재분석 연결 검증. 실호출은 E06 기본 전사 경로로 확인 |
| E09 | DONE | 활성 DOCUMENT+AVAILABLE 비공개 스트리밍, no-store/nosniff/안전 filename·타 공동체 차단 검증 |
| E10 | DONE | 제거·inputVersion 증가·오래된 lease 무효화·남은 입력 재큐잉·DELETE_PENDING·재분석 중복 차단 검증 |
| E11 | DONE | 입력 버전별 작업 목록과 lease 비노출 구현·검증 |
| E12 | DONE | FAILED 포함 작업 상세·canRetry 파생 구현·검증 |
| E13 | CODE DONE / MIXED VERIFIED | 최신 입력·소스/원본 상태와 저장 텍스트 재사용을 live encounter에서 확인. 429 Retry-After/timeout, 입력한도/refusal/incomplete 비재시도는 자동 테스트 |
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
| V07 | DONE | G05 Member.id로 대상 조회, 양쪽 ACTIVE 검증, V04 응답 재사용, 타인 수정 경로 없음 |
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

다음 외부 검증은 실제 iPhone/Android 출력 표본으로 컨테이너·코덱을 확정하고, 운영 VAPID·브라우저 구독으로 Web Push provider 수락과 실제 표시/click을 분리 확인하는 것이다. 운영 배포는 자격증명·도메인·Origin이 제공된 뒤 runbook으로 수행한다.

## 6단계 미검증·제한

- `OPENAI_API_KEY`는 Git 제외 `secrets/openai.env`에서 프로세스에만 주입했고 값은 출력하지 않았다. 완전 합성 실호출 결과와 개별 token/지연은 8단계 기록에 있다.
- 코드 기본 모델은 `gpt-transcribe`와 `gpt-5.4-mini-2026-03-17`이다. 전사, 이미지/PDF 입력, Responses Structured Outputs 접근을 실호출로 확인했지만 합성 소량 결과를 일반 의료 정확도로 확대 해석하지 않는다. URL과 선택 근거는 `decisions.md` D38~D41에 기록했다.
- OpenAI Java SDK 4.78.0 호환 범위는 공식 문서로 확인했지만 이 구현은 JDK `HttpClient`를 사용한다. 공급자 SDK 자동 재시도는 없으며 DB 작업 재시도만 적용한다.
- Responses는 `store=false`이고 provider 오류 본문·의료 원문·키를 일반 로그에 남기지 않는다. 성공 로그는 job/type/model/token/지연만 기록한다. 실제 조직의 data controls/abuse monitoring 설정은 계정 접근이 없어 미확인이다.
- 음성은 실제 WAV parser가 검증하는 WAV만 지원한다. OpenAI 지원 MIME 목록을 이유로 모바일 형식을 넓히지 않았으며 iPhone/Android와 변환/임시 파일 정리는 미구현·미검증이다.
- 제품 한도(문서 10,000,000 bytes/PDF 10페이지/이미지 10장, 음성 25,000,000 bytes/1200초)와 공급자 한도는 별개다. 분석 text context는 300,000 Unicode codepoint를 넘으면 명시적으로 `AI_INPUT_LIMIT_EXCEEDED` 처리하며 몰래 자르지 않는다.

## 5단계에서 이어지는 미검증·제한

- 실제 OpenAI adapter는 합성 자료로 실호출했다. test 프로필만 명시적 fake를 쓰며 prod/demo 기본값은 AI worker 비활성이다.
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
- 외부 AI·파일은 8단계에서 완전 합성 자료로 실호출했다. Web Push는 VAPID/브라우저 구독이 없어 실제 provider 호출을 수행하지 않았다.

## 7단계 알림함·outbox·Web Push

갱신: 2026-10-07, Asia/Seoul.

| 영역 | 상태 | 근거 |
|---|---|---|
| N01~N08 | CODE DONE | 본인/ACTIVE 공동체 알림함, cursor·unreadCount, 최초 readAt, DAILY/ONCE version, 공개 VAPID config, 구독 등록·계정 전환·목록·해제 구현 |
| outbox 확장 | CODE DONE | V1 `notification_event`를 SKIP LOCKED claim하고 수신자별 `notification`, 활성 기기별 `notification_delivery`를 한 트랜잭션에서 unique 제약과 함께 생성 |
| 예약·digest | CODE DONE | 시작 30분 전/시작/경과 event_key, task version 변경 시 구예약 취소·미래 예약 재생성, 09:00 KST DAILY digest와 당일 최초 OPEN 알림 제외 구현 |
| Web Push | CODE DONE / LIVE BLOCKED | web-push-java 5.1.2 암호화/VAPID + redirect 없는 JDK HttpClient, HTTPS/provider allowlist/DNS 사설주소 차단, 404/410·429·timeout·lease fencing 구현. VAPID/브라우저 구독 부재로 외부 provider 호출 안 함 |
| DB migration | NOT NEEDED | DB 설계 v1.2의 기존 28개 테이블·부분 unique·composite FK로 계약 충족. V1/V2 변경 없음 |
| AI worker lease 재검토 | LIVE VERIFIED | claim 한 번에 provider 호출 1회이고 backoff는 DB 재예약이다. 90초 call timeout/180초 lease 검증을 유지하며 앱 내부 다중 provider retry는 없음. 최대 관찰 성공 지연 9,534ms |

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

## 8단계 통합 검증·배포 준비

갱신: 2026-10-09, Asia/Seoul.

| 영역 | 상태 | 근거 |
|---|---|---|
| Phase 5 worker 경합 | FIXED / REGRESSION TESTED | test 프로필의 자동 scheduling을 끄고 worker를 활성 상태로 유지해 test가 `runOnce`/claim을 통제한다. 자동 poller 부재→QUEUED→명시 처리→후속 ANALYZE QUEUED를 10회 반복 검증했다. lease 만료 회수/fencing 테스트는 유지했다. |
| 설정 전달 | DONE | `.env` 자동 로딩을 가정하지 않고 IntelliJ/PowerShell/Compose 전달법, 전체 AI/알림/VAPID/CORS/DB/storage 계약을 문서화했다. `FILE_STORAGE_ROOT` 이름 불일치를 호환 수정했다. |
| VAPID 도구 | DONE | P-256 생성, base64url 길이/공개점-개인 scalar 짝 검증, 기존 파일 비덮어쓰기, Git 제외 local secret 저장을 구현했다. 운영 subject로 생성·짝 검증한 키를 mode 600 EC2 `.env`에 연결했고 private key는 출력하지 않았다. |
| 실제 OpenAI | LIVE VERIFIED / 일부 경계 자동 테스트 | Git 제외 `secrets/openai.env`의 키를 프로세스에만 주입해 완전 합성 WAV·JPEG·2-page PDF를 호출했다. 전사/OCR/Structured Outputs/E03/R02/복약 일정/명확한 TASK 자동 적용과 page·quote·codepoint 근거를 확인했다. refusal/incomplete/입력 한도는 실제 과금 호출 대신 adapter 자동 테스트로 확인했다. |
| 실제 Web Push | PARTIAL / DEVICE BLOCKED | 운영 VAPID와 push worker를 연결했고 N05 익명 401 및 production Origin preflight 200을 확인했다. 실제 사용자 로그인·브라우저 PushSubscription이 없어 provider 수락/기기 표시/click은 아직 실행하지 않았으며 N05/N06/N08 smoke page를 준비했다. |
| 프론트 연결 | PARTIAL | 제품 프론트는 골격 단계로 담당자 파일은 수정하지 않았다. API/header/file Blob/PWA cache/logout 계약을 체크리스트로 제공했다. |
| 모바일 녹음 | BLOCKED | 저장소 내 iPhone/Android 표본 0개. WAV 외 컨테이너·코덱 지원 또는 변환을 구현하지 않았다. 표본 수집/ffprobe 절차를 기록했다. |
| 운영 배포 | LIVE VERIFIED | PR #112/#114/#115를 main에 병합하고 Backend CD로 고정 SHA image를 배포했다. PostgreSQL/private-file volume, non-root API, loopback 8080, worker 설정, readiness, production CORS를 확인했다. Nginx 80/443, HTTP 301, HTTPS 200, Certbot 갱신 dry-run도 통과했다. |
| 백업·복원 | REHEARSED | API/worker가 없는 write-stop 상태에서 PostgreSQL custom dump와 private archive를 만들고 별도 `hungry-restore` project에 복원했다. worker 4종 false, readiness/auth/공동체·진료 조회/E09 파일 SHA-256 일치를 확인하고 임시 volume·backup을 정리했다. |

세부 산출물은 `live-smoke-runbook.md`, `frontend-mobile-checklist.md`, `deployment-runbook.md`, `backup-restore-runbook.md`에 있다. 적용된 V1/V2는 수정하지 않았다.

### 8단계 실행 기록

| 명령/검사 | 결과 |
|---|---|
| `gradlew.bat test --tests com.kw.knowone.Phase5IntegrationTests` | PASS, 경합 회귀 10회 포함 21 tests |
| Phase 7 집중 테스트 3회 `--rerun-tasks` | PASS, 매회 9 tests; DB `now()`와 application Clock 혼용을 제거하고 due/claim 시각 명시 |
| `gradlew.bat test --tests com.kw.knowone.ProductionContextTests` | PASS, prod profile의 실제 OpenAI adapter 생성 확인 |
| `gradlew.bat clean test bootJar` | PASS, 14 suites / 102 tests / 실패·오류·skip 0. UTC PostgreSQL 격리 컨테이너에서도 test JDBC session을 Asia/Seoul로 고정해 동일 결과 확인 |
| local/prod/restore `docker compose config --quiet` | PASS |
| `docker build -t 21-hungry-api:phase8-validation .` | PASS, non-root image 생성 |
| VAPID 임시 생성→짝 검증→비덮어쓰기→삭제 | PASS, private key 출력 없음 |
| 격리 DB+파일 복원 | PASS, readiness UP, 인증, 공동체 1·진료 1 조회, 인증 파일 hash 일치, scheduler/AI/event/push worker 비활성 |

### 실제 OpenAI 실행 기록

완전 합성 자료만 사용했다. `gpt-transcribe` 한국어 WAV 전사, `gpt-5.4-mini-2026-03-17` JPEG OCR, 2-page PDF OCR 및 분석이 성공했다. PDF 근거 page 1·2, sourceId/textVersion, quote와 Unicode codepoint start/end 9건을 원문과 대조해 모두 일치했다. 전사 응답은 현재 adapter가 읽을 token 필드를 주지 않아 0/0으로 기록됐고 호출 지연은 2,115ms였다.

주 시연 분석은 input/output 1,381/1,581 tokens, 9,534ms였다. 관련 이미지 OCR은 2,029/165 tokens, 3,205ms, PDF OCR은 2,605/121 tokens, 2,464ms였다. R02에서 중복 합성 처방 후보 하나를 page-backed source 기준으로 수동 해소하고 08:00·20:00을 확인해 처방 1개, DAILY series 2개, 3일간 occurrence 6개를 만들었다. revision 1개와 처방 1개만 존재함을 DB로 확인했다.

별도 명확 TASK 이미지에는 2026-10-11, 오전 10시, 30분, 단발, 비조건부를 명시했다. 최종 OCR은 2,605/90 tokens, 2,725ms, 분석은 1,378/674 tokens, 4,338ms였으며 TASK가 `READY`에서 즉시 `APPLIED`되어 series 1개와 10:00~10:30 occurrence 1개를 생성했다. 이 과정에서 실제 provider가 거절한 원인은 schema metadata 정리 함수가 TASK의 실제 `title` 속성까지 재귀 삭제한 것이었고, 루트 metadata만 제거하도록 수정했다. provider subset 밖 `format`, 길이·범위·배열 유일성 제약은 전송 schema에서 제거하되 서버 validator가 계속 검사한다.

스키마 거절과 근거/시간/반복 계약을 진단하는 합성 재호출이 있었으므로 위 수치는 성공 로그에서 관찰한 개별 호출 값이지 OpenAI 계정 전체 청구량 합계가 아니다. 실패 응답 원문·API 키·실데이터는 로그에 기록하지 않았다. 실제 모델 출력은 매번 서버 검증을 통과해야 하며 이번 성공을 임의 입력의 정확도 보장으로 일반화하지 않는다.

전체 테스트 중 Phase 7의 한 테스트가 DB `now()`로 due_at을 만들고 Java Clock으로 즉시 claim해 한 번 빈 claim이 발생했다. 실제 lease/state 결함이나 background poller 경합은 아니며, 같은 `Instant`를 insert/claim/expand에 전달하도록 수정한 뒤 집중 3회와 전체 회귀를 통과했다. 복원 첫 기동에서는 prod profile의 실제 OpenAI adapter가 다중 생성자 중 주입 생성자를 선택하지 못하는 결함을 발견해 `@Autowired`를 명시하고 prod-context 회귀를 추가했다.

## API v1.2 전체 계약 전환

갱신: 2026-10-09, Asia/Seoul.

- A01/A02, G08, N03/N04 폐기 경로는 `410 ENDPOINT_RETIRED`로 전환했고 A05/A06 전화번호 가입·로그인에는 `PHONE_AUTH_ENABLED` 설정을 연결했다.
- G01~G12의 단일 공동체, 첫 자녀/승인 대기, 재가입, 역할 1/2, 탈퇴, 승인 목록 페이지네이션, 승인·거절·취소 멱등 응답과 audit를 반영했다.
- T01 날짜별 개인 rank/cursor, T17 월 전체 캘린더, T03 수동 단건·horizon 밖 즉시 생성, T10 담당자 본인 해제, T11/T16 개인 수락·거절 필드와 capability를 반영했다.
- 최초 `NO_CANDIDATE`는 즉시 인계 이벤트를 만들지 않고, 09시 digest 및 `HANDOFF_OPEN` 확장/전송 단계에서 사용자별 거절 상태를 재검증한다.
- E05 파일별 `documentType` 계약과 로컬 Web Push smoke의 A06 로그인 흐름을 회귀 테스트 및 문서에 맞췄다.
- 격리 PostgreSQL 18.6의 `hungry_test` 스키마에서 `gradlew.bat clean test bootJar`를 실행해 Flyway V1~V3, 15 suites / 105 tests / 실패·오류·skip 0 및 배포 JAR 생성을 확인했다.
