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
| CORS_ALLOWED_ORIGINS | 정확한 Origin 목록 | 서버 설정, 실제 값 미정 |
| FILE_STORAGE_ROOT, AUDIO_TEMP_ROOT | 비공개 영속/임시 저장 경로 | 서버 설정 |
| OPENAI_API_KEY | 공급자 인증 | 서버 비밀 |
| OPENAI_TRANSCRIBE_MODEL, OPENAI_OCR_MODEL, OPENAI_ANALYZE_MODEL | 명시적 모델 선택 | 서버 설정, 미정 |
| AI_CALL_TIMEOUT_SECONDS, AI_JOB_LEASE_SECONDS, AI_WORKER_CONCURRENCY | timeout·lease·처리량 | 시험 후 값 결정 |
| AI_MAX_OUTPUT_TOKENS | 모델 출력 상한 | 모델 지원 확인 후 |
| AUDIO_ALLOWED_MEDIA_TYPES, AUDIO_MAX_BYTES, AUDIO_MAX_DURATION_SECONDS | 음성 실제 정책 | U05/U06 후 고정 |
| MULTIPART_MAX_REQUEST_BYTES | 전체 요청 한도 | 파일당 10MB와 구별 |
| PREVIEW_SIGNING_SECRET | preview 무결성 | 서버 비밀, 인증 토큰과 무관 |
| AUTH_SESSION_TTL_SECONDS | 86400 기본 | 서버 설정 |
| IDEMPOTENCY_TTL_SECONDS, PREVIEW_TTL_SECONDS | 86400 / 300 기본 | 서버 설정 |
| VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, VAPID_SUBJECT | 실제 Web Push | PRIVATE만 비밀 |
| FRONTEND_ORIGIN | 푸시 클릭의 허용 프론트 주소 | 실제 Origin 미정 |
| 프론트 API Origin 변수 | API Origin만, /api/v1은 경로 | 변수명 팀 결정; Vite면 VITE_API_BASE_URL 예시 |

일괄 비밀 값 예시 파일은 실제 구현 단계에서 생성하며 값은 비워 둔다. 현재 문서에 키·비밀번호를 요구하거나 넣지 않는다.

## 5. 변경 기록 규칙

결정 변경 시 날짜·이유·영향 API/설정·검증 결과를 기록한다. API 공개 계약 변경은 프론트에 전달하고 기준 문서는 새 버전으로 맞춘다. 이 문서를 이유로 관련 없는 PRD 기능을 변경하지 않는다.
