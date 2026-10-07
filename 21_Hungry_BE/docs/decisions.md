# 구현 결정과 미정 설정 v1.0

작성: 2026-10-05, Asia/Seoul. 원문 PRD·DB·API는 수정하지 않았다.
상태: 사용자 확정 요구사항, 이번 작업의 되돌릴 수 있는 구현 기본값, 기술 시험이 필요한 미정을 구분한다. 기본값은 실서비스 정책 확정이 아니다.

## 1. 유지하는 확정 요구사항

Bearer opaque token·4계정·ACTIVE 공동체 인가, Vercel/EC2 분리, 기존 28개 테이블, 문서 10MB/10페이지/10장, 실제 녹음·전사·요약·OCR·푸시, 가능시간·반복·배정·인계·완료, 가상 데이터 시연. 4시간 묶음은 첫 시작 기준이며 AI 배정은 하지 않는다.

## 2. 이번 개발의 구현 기본값

| ID | 항목 | 기본값 | 근거·변경 범위 |
|---|---|---|---|
| D01 | 백엔드 | Java 21 / Spring Boot / Gradle, 단일 앱 | PRD 추천을 구현 기본값으로 채택. 정확한 버전은 1단계 |
| D02 | 이미지 초과 오류 | IMAGE_COUNT_LIMIT_EXCEEDED, HTTP 422 | API v1.0의 프론트 계약으로 통일. PRD/DB의 DOCUMENT_IMAGE_LIMIT_EXCEEDED는 이전 표현 |
| D03 | 멱등 보관 | 24시간, 설정 가능 | API v1.0. DB의 7일은 추천값이며 DDL 변경 없음. 업무 고유키는 계속 필요 |
| D04 | 세션·preview | 세션 24시간, preview 5분 | 기존 추천값. 절대 만료, 자동 연장 없음 |
| D05 | 시간·반복 | Asia/Seoul, 14일 horizon | 기존 계약. Clock 주입으로 테스트 가능한 구조 |
| D06 | AI 인터페이스 | 파일 전사 + Responses 기반 OCR/분석, 구조화 출력 | 구현 경로 제안. 모델명·모델별 지원은 시험 후 고정 |
| D07 | 파일 저장소 | StoragePort 뒤에 로컬 비공개 디렉터리 구현으로 시작 | EC2 영속 볼륨 사용 추천. 공개 정적 경로 금지. 실제 운영 저장 방식은 배포 전 확인 |
| D08 | 개발 조회 | polling 기본 2초, 종료/실패/화면 이탈 시 중지 | 기존 API 추천. 서버 재시도와 별개 |
| D09 | 배정 점수·계획 시간 | 해당 KST 주의 미취소 assignee 건수; 복약/수령 30분·동행 120분 | DB/API 추천값을 기본값으로 채택. OTHER는 사용자 입력. 의료 사실 아님 |

## 3. 미정 목록과 결정 시점

| ID | 미정 | 담당 | 필요한 시점 | 결정 방법 |
|---|---|---|---|---|
| U01 | 기존 레포·백엔드 디렉터리·base package | 백엔드 | 1단계 시작 | 실제 레포 확인; 없으면 backend/와 임시 패키지 사용 후 기록 |
| U02 | Boot·Gradle·PostgreSQL·SDK 버전 | 백엔드 | 1단계 | 공식 호환성 확인, 실행·Flyway 검증 후 고정 |
| U03 | React 등 프론트 기술·포트·환경변수 이름 | 프론트 | 최초 연동 | 프론트 팀 확인; VITE 이름을 확정처럼 사용하지 않음 |
| U04 | API 도메인·Vercel production/preview Origin | 팀/배포 | 최초 외부 연동 | 실제 URL 확보; preview는 명시 목록 |
| U05 | 음성 MIME·컨테이너·코덱 | 프론트+백엔드 | 음성 endpoint 완성 전 | iPhone/Android 녹음 → 서버 검사 → 전사 시험 |
| U06 | 음성 20분·25,000,000 bytes 최종 채택 | 백엔드 | U05 시험 후 | 기존 추천 유지; 선택 endpoint 경계·실제 인코딩 확인 |
| U07 | 전사/OCR/분석 모델 ID | 백엔드 | 실제 AI 연결 전 | 한국어·약명·숫자·오류 샘플로 평가; 모델명 자동 추측 금지 |
| U08 | AI 예산·context/output 한도·timeout·lease·동시 워커 수 | 백엔드 | 실제 AI 연결 전 | 측정 후 설정. lease는 timeout보다 여유 있게 또는 heartbeat |
| U09 | 브라우저 토큰 보관 | 프론트 | 로그인 연결 | localStorage는 가상 데이터 MVP 추천만. 실서비스 정책 아님 |
| U10 | EC2 사양·비용·볼륨·백업 | 팀/배포 | 운영 배포 전 | 용량/복원/재배포시 보존 확인 |
| U11 | 요청 전체 크기·범위 변경 최대량·rate limit | 백엔드 | 관련 API 연결 전 | 파일당 정책과 분리; multipart overhead 포함 |
| U12 | VAPID·프론트 고정 Origin·실제 푸시 시험 기기 | 팀 | 1단계 기술 시험 | 키는 서버, 공개키만 프론트 제공 |
| U13 | AI 업체 보관 조건·프로젝트 설정 | 백엔드 | 실제 AI 시험 전 | 공식 Your data와 계정 설정 확인; 가상 데이터만 사용 |

아직 결정되지 않은 필수 배포 값은 빈 placeholder로 두고 설정 validation으로 실패시킨다. 운영에서 전체 Origin 허용이나 임의 모델 선택으로 우회하지 않는다.

## 4. 환경변수 계약 초안

| 변수 | 용도 | 공개 여부/상태 |
|---|---|---|
| SPRING_PROFILES_ACTIVE | local/test/prod | 비밀 아님 |
| DB_URL, DB_USERNAME, DB_PASSWORD | DB 연결 | 서버 전용, PASSWORD 비밀 |
| CORS_ALLOWED_ORIGINS | 정확한 Origin 목록 | `https://knowone-eight.vercel.app` |
| FILE_STORAGE_ROOT, AUDIO_TEMP_ROOT | 비공개 영속/임시 저장 경로 | 서버 설정 |
| OPENAI_API_KEY | 공급자 인증 | 서버 비밀 |
| OPENAI_TRANSCRIBE_MODEL, OPENAI_OCR_MODEL, OPENAI_ANALYSIS_MODEL | 명시적 모델 선택 | 서버 설정. 6단계 기본값은 아래 D38 참조 |
| OPENAI_TIMEOUT(또는 기존 AI_CALL_TIMEOUT_SECONDS), AI_JOB_LEASE_SECONDS, AI_WORKER_CONCURRENCY, AI_JOB_MAX_ATTEMPTS | timeout·lease·처리량·DB 재시도 상한 | 6단계 안전 기본값은 아래 D40 참조 |
| OPENAI_MAX_OUTPUT_TOKENS(또는 기존 AI_MAX_OUTPUT_TOKENS) | 모델 출력 상한 | 기본 12000, 실호출 측정 전 잠정 |
| AUDIO_ALLOWED_MEDIA_TYPES, AUDIO_MAX_BYTES, AUDIO_MAX_DURATION_SECONDS | 음성 실제 정책 | U05/U06 후 고정 |
| MULTIPART_MAX_REQUEST_BYTES | 전체 요청 한도 | 파일당 10MB와 구별 |
| PREVIEW_SIGNING_SECRET | preview 무결성 | 서버 비밀, 인증 토큰과 무관 |
| AUTH_SESSION_TTL_SECONDS | 86400 기본 | 서버 설정 |
| IDEMPOTENCY_TTL_SECONDS, PREVIEW_TTL_SECONDS | 86400 / 300 기본 | 서버 설정 |
| VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT | 실제 Web Push | PRIVATE만 비밀 |
| FRONTEND_ORIGIN | 푸시 클릭의 허용 프론트 주소 | `https://knowone-eight.vercel.app` |
| 프론트 API Origin 변수 | API Origin만, /api/v1은 경로 | 변수명 팀 결정; Vite면 VITE_API_BASE_URL 예시 |

일괄 비밀 값 예시 파일은 실제 구현 단계에서 생성하며 값은 비워 둔다. 현재 문서에 키·비밀번호를 요구하거나 넣지 않는다.

## 5. 변경 기록 규칙

결정 변경 시 날짜·이유·영향 API/설정·검증 결과를 기록한다. API 공개 계약 변경은 프론트에 전달하고 기준 문서는 새 버전으로 맞춘다. 이 문서를 이유로 관련 없는 PRD 기능을 변경하지 않는다.

## 6. 1단계에서 확정한 기술 기준

작성: 2026-10-05, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D10 | 기존 `21_Hungry_BE`와 base package `com.kw.knowone`을 유지 | 중복 앱을 만들지 않고 기존 Gradle/Spring Boot 프로젝트 확장 |
| D11 | Java 21 / Spring Boot 4.1.1 / Gradle 9.7.1 | Spring Boot 4.1.1 공식 요구사항은 Java 17~26, Gradle 8.14+ 또는 9.x. 로컬 컴파일·테스트 통과 |
| D12 | PostgreSQL 18.6 Alpine | 2026-10-05 기준 지원되는 PostgreSQL 18의 최신 패치. 실제 Docker DB에서 Flyway·앱 실행 검증 |
| D13 | Boot BOM 해석 버전 유지 | Spring Framework 7.0.9, Security 7.1.1, Flyway 12.4.0, JDBC 42.7.13, Hibernate 7.4.5.Final |
| D14 | 공개 검증 경로는 `/internal/status`, Actuator liveness/readiness | 60개 업무 API와 구분. 그 외 경로는 기본 보호 |
| D15 | 시드는 `db/demo` location의 V2로 분리 | local/demo만 포함하고 test/prod에는 미포함. 실제 데이터 환경에 강제 주입하지 않음 |

U01과 U02는 위 결정으로 해소했다. U03~U13은 기존 상태를 유지한다. 공식 확인 URL은 README에 기록했다.

## 7. 2단계에서 확정한 기술 기준

작성: 2026-10-06, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D16 | 인증은 32바이트 난수의 base64url 무패딩 opaque token이며 DB에는 토큰 UTF-8 SHA-256만 저장 | `auth_session`의 32바이트 제약과 실제 PostgreSQL 통합 테스트로 확인. JWT/refresh token은 도입하지 않음 |
| D17 | 세션, 멱등, preview 기본 TTL은 각각 24시간, 24시간, 5분이며 환경변수로 초 단위 조정 | 기존 D03/D04와 API v1.0을 코드 설정에 연결 |
| D18 | G04/G06은 `schedule_guard`를 트랜잭션의 첫 DB 잠금으로 획득한 뒤 최신 권한을 재검사하고 멱등 replay를 판정 | 권한 상실 후 replay 차단 및 동시 동일 요청 1회 변경을 PostgreSQL에서 검증 |
| D19 | G04 가입/재가입과 G06 우선순위 변경은 업무 변경, audit, notification_event, 멱등 성공 저장을 한 트랜잭션에 기록 | 실패 시 전체 롤백되고 replay에서는 부수효과가 재생성되지 않음 |
| D20 | local/demo V2와 prod migration 경로를 분리하고 demo DB를 prod로 승격하지 않음 | V2 가상 데이터 및 Flyway 이력 혼입을 막기 위해 prod는 새 전용 DB에서 시작 |

## 8. 3단계에서 확정한 기술 기준

작성: 2026-10-06, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D21 | 모든 일정 관련 명령은 `ScheduleMutationService`에서 `schedule_guard(id=1)`을 첫 SQL로 잠근 뒤 인가·멱등 replay·업무 변경을 수행 | 동시 겹침 일정 생성에서 같은 사용자의 중복 배정을 차단하고 기존 G04/G06도 같은 경로로 통일 |
| D22 | 가능 시간은 DB의 최종 날짜 구간을 단일 판정 원천으로 사용하며 미등록 날짜는 불가로 처리 | FULL/PARTIAL/custom, 자정 넘는 근무·업무, 인접 구간 병합을 동일 계산기로 PostgreSQL 통합 검증 |
| D23 | 단발 일정도 `task_series` → `task_series_revision` → `task_occurrence`를 사용하고 별도 상태 모델을 만들지 않음 | 14일 밖 ONCE는 시리즈만 저장하고 발생 배열이 비어 있는 계약 유지 |
| D24 | 3단계 T03/T04는 일반 ONCE만 PARTIAL로 공개하고 반복·MEDICATION 입력은 오류로 거부 | 미구현 입력을 일반 단발로 변환하거나 처방 데이터 없이 승인하지 않음 |

## 9. 4단계에서 확정한 기술 기준

작성: 2026-10-07, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D25 | 요청 처리와 매일 scheduler가 같은 `TaskGenerationService`를 사용하고 모두 `schedule_guard` 뒤에서 14일 horizon을 생성 | `(series_id,anchor_date)` 충돌 무시와 기존 행 비갱신으로 재실행·동시 실행에도 예외/완료/취소 tombstone 보존 |
| D26 | 4시간 묶음은 한 생성 배치의 같은 공동체·KST 날짜 신규 업무만 대상으로 하며 `startsAt→seriesId→anchorDate→id` 순으로 고정 | 13/17/21 연쇄 금지, 중간 전체 가능 시간·전 공동체 충돌·최상위 순위/최소 건수 교집합을 PostgreSQL 통합 검증 |
| D27 | 반복 일괄 수정의 token 발급 시각을 cutoff와 동일하게 사용하고 영향 ID/version·seriesVersion·추가 날짜를 state fingerprint에 포함 | 수 밀리초 cutoff 차이로 정상 저장이 stale이 되는 문제를 제거하고 저장 시 상태·현재 시각을 재검증 |
| D28 | 경과 OPEN 인계는 수락 트랜잭션 전에 독립 guard 트랜잭션으로 EXPIRED 커밋 | 오류 응답 롤백으로 만료 기록이 사라지지 않으며 미래 이동 시 새 미배정 사건을 별도로 생성 |
| D29 | 4단계 생성기는 일반 일정만 처리하고 MEDICATION 생성·일괄 수정은 처방 확인 단계까지 명시적으로 거부 | 약 snapshot 없이 일반 반복처럼 생성해 성공으로 보이지 않도록 T03/T04/G08을 필요한 범위에서 PARTIAL 유지 |

## 10. 5단계에서 확정한 기술 기준

작성: 2026-10-07, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D30 | 기존 28개 테이블과 적용된 V1/V2를 수정하지 않고 encounter/file_asset/source/job/revision 구조를 그대로 사용 | DB v1.2가 5단계에 필요한 상태·lease·dedup·파기 필드를 이미 포함하며 새 migration이 필요하지 않음 |
| D31 | `StoragePort` 뒤의 로컬 비공개 저장소는 서버 생성 key만 사용하고 임시 저장→검증→승격→DB 등록, DB 실패/replay 시 객체 보상 삭제를 수행 | 파일시스템과 DB의 비원자성을 명시적으로 보상하고 원본 filename·경로 이탈·공개 정적 제공을 차단 |
| D32 | 문서는 PDFBox와 ImageIO/TwelveMonkeys의 실제 parser/decoder를 통과해야 하며 signature와 수신 byte 수를 함께 검사 | 확장자·클라이언트 MIME/pageCount를 신뢰하지 않고 10,000,000 bytes·10페이지·10장 정책을 적용. WEBP 실제 decode도 자동 검증 |
| D33 | 음성 자동 검증 지원은 우선 WAV parser로 제한하고 25,000,000 bytes·1200초·24시간 만료를 설정화 | U05/U06은 실기기 시험 전 미정이므로 WAV를 최종 모바일 지원 형식으로 확정하지 않음 |
| D34 | 실제 AI adapter는 미연결 상태로 두고 test 프로필에만 fake를 등록한다. prod/demo에서 worker가 꺼져 있으면 작업을 `FAILED/AI_NOT_CONFIGURED`로 명시 | fake 성공을 실제 처리처럼 저장하거나 처리 불가능한 작업을 영구 QUEUED로 숨기지 않음 |
| D35 | processing worker는 짧은 SKIP LOCKED claim 트랜잭션과 외부 처리, lease/inputVersion/source 상태를 재검증하는 결과 트랜잭션으로 분리 | 만료 lease 재소유 후 이전 worker 쓰기와 입력 변경 후 stale 결과를 차단 |
| D36 | 기록 삭제는 202 접수와 실제 객체 파기를 구분하고, 즉시 접근 차단 후 공유 약 업무는 다른 약 연결을 유지하며 단독 미래 업무만 RECORD_DELETED 취소 | soft delete만으로 파기 완료로 표시하지 않고 원본 삭제 확인 뒤 key 제거·민감 텍스트/요약/후보 redaction 수행 |
| D37 | ANALYZE dedup key는 `(encounterId,inputVersion,ANALYZE)`로 고정하고 E10 멱등 operation은 sourceId 기반으로 60자 안에 유지 | 여러 준비 경로에서 동일 요약 작업/현재 revision이 중복되지 않으며 적용된 DB 컬럼 길이를 변경하지 않고 소스 제거 replay를 지원 |

## 11. 6단계에서 확정한 기술 기준

작성: 2026-10-07, Asia/Seoul. 공식 문서 확인일도 2026-10-07이다.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D38 | 파일 전사는 `v1/audio/transcriptions`의 `gpt-transcribe`, OCR·분석은 Responses API의 고정 snapshot `gpt-5.4-mini-2026-03-17`을 기본값으로 사용 | [Speech to text](https://developers.openai.com/api/docs/guides/speech-to-text), [gpt-transcribe](https://developers.openai.com/api/docs/models/gpt-transcribe), [GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini)를 확인했다. 2026-10-08 완전 합성 한국어 WAV·JPEG·PDF 실호출에서 모델 접근, 전사/OCR/Structured Outputs를 확인했다. 개별 token·지연과 품질 한계는 progress에 기록하며 임의 의료 입력 정확도를 보장하지 않는다. |
| D39 | JDK `HttpClient`로 공식 REST 계약을 직접 호출하고 OpenAI Java SDK를 추가하지 않는다 | [공식 Java SDK](https://developers.openai.com/api/reference/java)는 4.78.0과 설정 가능한 timeout을 확인했다. 현재 adapter는 SDK 내부 재시도가 없어 DB 작업 재시도와 중첩되지 않으며 `Retry-After`를 존중한다. SDK를 사용하지 않으므로 SDK 호환성 검증 완료로 주장하지 않는다. |
| D40 | 기본 provider timeout 90초, lease 180초, concurrency 2, 작업 최대 시도 4회이며 활성화 시 lease가 timeout보다 최소 15초 길지 않으면 시작을 거부한다 | claim한 작업은 고정 크기 executor에서 병렬 처리한다. 429/일시 장애만 제한 재시도하고 provider 입력 한도·refusal·incomplete·schema/의미 오류는 같은 입력으로 자동 재호출하지 않는다. 외부 호출은 DB 트랜잭션과 schedule guard 밖이다. |
| D41 | Responses 요청은 `store=false`, 신뢰 프롬프트는 developer message, 원문과 파일은 user content로 분리한다 | [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [File inputs](https://developers.openai.com/api/docs/guides/file-inputs), [Images and vision](https://developers.openai.com/api/docs/guides/images-vision), [Data controls](https://developers.openai.com/api/docs/guides/your-data)를 확인했다. `store=false`는 Responses 상태 저장을 끄지만 조직의 abuse monitoring/보존 설정까지 자동 보장한다는 뜻은 아니다. |
| D42 | 분석 계약 1.0의 schema/prompt를 버전 관리하고 모델 출력 뒤 서버가 exact-field, null·날짜·근거 quote·source·Unicode codepoint offset·교차 소스 충돌을 다시 판정 | 모델은 DB ID·담당자·확인자·reviewState/application key를 정하지 않는다. `supersedesMedicationId`는 schema에서 null만 허용한다. 공급자 요청에서는 루트 metadata와 provider subset 밖 `uniqueItems`/format/길이·범위 제약만 제거하고 해당 검증은 서버가 유지한다. 실제 payload 속성 `title`은 보존한다. 실제 변경 관계는 R02가 같은 공동체의 확정 처방으로 매핑한다. |
| D43 | 일반 TASK만 명확·미래·비조건부·근거 유효 시 자동 적용하고 MEDICATION은 항상 R02 사용자 확인 뒤 처방·시리즈·발생·배정에 반영 | R02는 guard 뒤 version/현재 revision/충돌 해소를 재검증하고 처방·snapshot·audit·outbox·멱등 성공을 한 트랜잭션에 기록한다. 확인은 사용자 확인이지 의료진 검증이 아니다. |
| D44 | 음성 서버 지원은 계속 실제 WAV parser를 통과한 `audio/wav`, `audio/x-wav`로 제한 | OpenAI 전사 API 자체의 지원 컨테이너가 더 넓어도 MIME만 확장하지 않는다. iPhone/Android 출력과 변환 경로는 실기기 표본이 없어 BLOCKED다. |

## 12. 7단계에서 확정한 기술 결정

작성 및 공식 문서 확인: 2026-10-07, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D45 | V1의 `notification_event` → `notification` → `notification_delivery`를 그대로 사용하고 migration을 추가하지 않음 | 기존 unique event_key, `(event_id,user_id)`, `(notification_id,subscription_id)`, 활성 endpoint 부분 unique와 소유자 composite FK가 재처리·계정 전환 계약을 충족한다. 적용된 V1/V2는 변경하지 않았다. |
| D46 | outbox와 delivery는 각각 짧은 SKIP LOCKED claim, lease_token fencing, 잠금 밖 외부 HTTP로 처리 | 만료 lease는 새 token으로 회수하고 이전 worker의 결과 UPDATE는 token 조건으로 차단한다. 알림함 생성 성공과 기기별 delivery 성공은 분리한다. |
| D47 | Java Web Push는 Maven Central 공개 버전 `nl.martijndwars:web-push:5.1.2`의 RFC 8291 payload 암호화/VAPID 생성과 JDK HttpClient를 조합 | [webpush-java](https://github.com/web-push-libs/webpush-java), [Maven Central](https://central.sonatype.com/artifact/nl.martijndwars/web-push/5.1.2), [RFC 8030](https://datatracker.ietf.org/doc/html/rfc8030), [RFC 8291](https://datatracker.ietf.org/doc/html/rfc8291), [RFC 8292](https://datatracker.ietf.org/doc/html/rfc8292)를 확인했다. JDK client는 redirect NEVER와 호출 timeout을 강제한다. jose4j 0.9.6, Bouncy Castle 1.81을 명시해 library의 오래된 runtime metadata 의존성을 대체한다. |
| D48 | push endpoint는 등록 시와 전송 직전 모두 HTTPS/443, 명시 provider host suffix, DNS 공인 주소를 검사 | localhost, 사설/link-local/multicast/metadata 경로와 userinfo·redirect를 거부한다. DNS 검증과 실제 connect 사이 rebinding 경쟁은 완전히 제거할 수 없으므로 provider allowlist와 no-redirect를 함께 사용하며 이 잔여 한계를 기록한다. |
| D49 | N05 비활성 응답은 `enabled=false, applicationServerKey=null` | push worker가 꺼졌거나 VAPID 3종 중 하나라도 없으면 브라우저가 구독을 시도하지 않는다. private key와 subject는 API에 노출하지 않는다. worker 활성인데 설정이 누락·오류면 서버 시작을 실패시킨다. |
| D50 | 404/410은 해당 subscription 비활성 및 그 기기의 대기 delivery 취소, 429는 Retry-After 최대 1시간 반영, 408/5xx/transport timeout은 제한 exponential backoff | 다른 기기는 독립 delivery라 유지한다. provider 2xx만 SENT이며 기기 표시·읽음은 별도다. 응답 유실 후 재시도에 따른 외부 중복 가능성은 Web Push의 exactly-once 보장이 없어 `eventId` tag로 표시 중복을 줄인다. |
| D51 | 시작 예약은 `task:{id}:v{version}:30m\|now\|overdue`, digest는 `digest:{groupId}:{userId}:{KST date}`를 key로 사용 | 업무 version 변경 트랜잭션에서 구 event/delivery를 취소하고 아직 지나지 않은 새 시각만 생성한다. DAILY는 09:00 KST 이후 한 번 생성하며 당일 같은 OPEN의 최초 inbox가 있으면 제외한다. |
| D52 | AI worker lease 설정은 변경하지 않음 | 현재 claim 안 provider 호출은 1회이고 재시도/backoff는 DB `available_at` 재예약이다. 앱 내부 다중 호출 retry가 없어 기본 90초 timeout/180초 lease와 15초 최소 margin 검증이 현재 구조에 맞다. [OpenAI rate-limit 지침](https://developers.openai.com/api/docs/guides/rate-limits)의 Retry-After·bounded retry·attempt timeout/total deadline 구분을 재확인했다. live 성공 호출의 최대 관찰 지연은 9,534ms로 margin 안이었다. |

## 13. 8단계에서 확정한 기술 결정

작성: 2026-10-08, Asia/Seoul.

| ID | 결정 | 근거·검증 |
|---|---|---|
| D53 | `@EnableScheduling`은 `app.scheduling.enabled` 조건부 구성으로 분리하고 test/restore에서 끈다 | Phase 5가 직접 claim하는 큐를 테스트 컨텍스트의 최초 scheduled poll이 먼저 claim할 수 있었다. worker 자체는 활성으로 유지해 명시 `runOnce`와 lease/fencing assertion을 그대로 검증한다. |
| D54 | 운영 파일 저장소는 API 이미지 밖 named volume `/data/private-files` 하나를 사용한다 | staging과 승격 대상이 같은 파일시스템이어야 atomic move가 유지된다. 기존 문서의 별도 `AUDIO_TEMP_ROOT`는 실제 코드가 읽지 않아 제거했다. |
| D55 | 배포 이미지는 commit SHA tag로 고정하고 readiness 실패 시 직전 image ID로 API만 복구한다 | source 재빌드 결과가 바뀌는 배포를 피하고 DB/파일 volume을 보존한다. DB migration 호환성은 이미지 롤백과 별도로 판단한다. |
| D56 | 백업은 API를 중지한 일관성 구간에서 PostgreSQL custom dump와 private volume archive를 함께 만든다 | DB object key와 파일을 같은 시점에 보존한다. 복원은 별도 Compose project에서 모든 scheduler/AI/push worker를 끄고 검증한다. |
| D57 | provider용 JSON Schema 정리는 루트 metadata와 명시된 불지원 validation keyword에만 적용하고 payload의 실제 속성명은 삭제하지 않는다 | 실호출에서 재귀 `title` 삭제가 `required`와 불일치해 `invalid_json_schema`가 발생했다. TASK `title` 보존 회귀 테스트와 실제 Structured Outputs 성공으로 수정 근거를 확인했다. |
| D58 | N05는 공개키를 반환하더라도 API v1.0의 `권한: 인증`을 유지한다 | 공개키의 비밀성 여부와 API 인증 계약은 별개다. 익명 permit 목록에서 `/api/v1/push/config`를 제거하고 401 회귀 테스트를 추가했다. |
| D59 | production frontend/API/VAPID subject를 각각 `https://knowone-eight.vercel.app`, `api.gaebalmani.shop`, `mailto:js48765348@gmail.com`으로 고정 | DNS A는 52.35.249.169로 확인했다. Vercel HTTPS, API HTTP→HTTPS, readiness, production CORS와 Certbot 갱신 dry-run을 실서버에서 확인했다. |
| D60 | test profile JDBC session timezone을 `Asia/Seoul`로 고정 | 컨테이너 OS의 `TZ`만으로 PostgreSQL JDBC session timezone이 보장되지 않아 UTC 날짜 경계에서 일정 fixture가 달라졌다. UTC PostgreSQL 18.6 격리 환경과 GitHub CI에서 102 tests 통과로 검증했다. |
