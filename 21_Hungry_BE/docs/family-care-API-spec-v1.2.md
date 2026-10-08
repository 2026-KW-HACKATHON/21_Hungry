# 가족 돌봄 서비스 API 명세 v1.2

갱신: 2026-10-08 KST. 기준: PRD v0.15 / DB v1.3.
현재 제품 계약으로 v1.1을 대체한다. v1.2는 E06 녹음 metadata 오기 제거, 버전 검사 명시, 현재 WAV 지원 설명 정정을 반영한다. 유지되는 진료·파일·처방·가능시간·푸시 API의 상세 계약은 본문에 함께 수록했다. 문서 갱신은 서버 구현·배포 완료를 의미하지 않는다.

기존 ID를 재사용하지 않는다. A01/A02와 N03/N04/G08은 폐기 계약으로 명시하고 신규 가입·로그인은 A05/A06, 캘린더는 T17로 추가한다. 기존 경로 /api/v1은 유지하지만 DTO·정책 변경이 있어 프론트와 백엔드를 함께 전환해야 한다.

## 1. 공통 계약

### 1.1 주소·배포·버전

- 프론트: `https://knowone-eight.vercel.app`, 백엔드: `https://api.gaebalmani.shop`.
- 모든 업무 API 경로는 `/api/v1`로 시작한다. 실제 시연 주소는 위와 같다.
- 프론트의 API base는 **API Origin만** 담고 각 경로에 `/api/v1/...`를 붙인다. Vite 사용 시 `VITE_API_BASE_URL`은 환경변수 이름 예시다.
- 브라우저는 별도 Origin의 API를 직접 호출한다. Nginx는 백엔드 HTTPS reverse proxy 역할이다. 프론트 정적 파일은 Vercel이 제공한다.
- 서버는 실제 production Origin·등록한 preview Origin·개발 Origin만 허용한다. `*` 또는 모든 Vercel preview 자동 허용 금지.
- CORS 요청 헤더: `Authorization`, `Content-Type`, `Idempotency-Key`. 메서드: GET, POST, PUT, PATCH, DELETE, OPTIONS. OPTIONS는 인증 토큰 없이 preflight 처리하며 업무 데이터는 반환하지 않는다.
- 응답의 `Content-Disposition`, `Retry-After`를 JS에서 읽을 수 있게 CORS expose headers로 설정한다. 인증 Cookie·`credentials: include`·`withCredentials`를 요구하지 않는다.
- 보건 데이터·토큰·파일 응답은 `Cache-Control: no-store`. Service Worker 캐시에서 제외한다.

### 1.2 인증·권한

```http
Authorization: Bearer <access-token>
```

로그인 응답의 opaque token 원문을 그대로 전달한다. JWT가 아니며 프론트는 디코딩·SHA-256 변환하지 않는다. 서버는 안전한 32바이트 난수를 base64url(패딩 없음)로 직렬화하는 구현 기본값을 사용하며, 토큰 문자열 UTF-8 바이트의 SHA-256만 `auth_session.token_hash`에 저장한다. token hash·원문·API 키·비공개 object_key는 DTO와 로그에 노출하지 않는다.

서버는 세션 존재·절대 만료·revoked·ACTIVE 계정을 검사한다. 세션 수명 24시간은 기존 추천 설정값이며 `expiresAt`이 실제 기준이다. 자동 연장·refresh token 없음. 로그아웃은 현재 세션만 폐기한다.

권한 표기: **인증** = 유효 토큰, **공동체** = 리소스 실제 group_id의 ACTIVE 멤버십, **본인** = 로그인 사용자 소유 데이터. 공동체 역할은 RECIPIENT/CAREGIVER이며 같은 화면·건강 기록 접근을 허용한다. 개인 가능 시간·구독·설정은 본인만 변경한다. 모든 중첩·일괄 ID도 실제 공동체 소속을 검사한다.

인증 실패는 401, 인증 후 다른 공동체·PENDING·LEFT·개인 소유권 위반은 403. 존재하는 다른 공동체 리소스를 UUID로 요청해도 403이다. 존재하지 않는 UUID는 404. 서버는 데이터 본문을 반환하기 전에 권한을 검사한다. 삭제된 리소스는 권한 검사 가능한 tombstone이 있으면 먼저 인가하고 404 처리한다. 랜덤 UUID는 접근 제어가 아니다.

### 1.3 JSON 규약·시간·페이지네이션

- JSON 필드는 camelCase. ID는 UUID 문자열, enum은 대문자 문자열.
- 필수값은 각 DTO에 정의. 예시의 `null` 필드는 nullable, 배열은 빈 경우 `[]`다. 읽기 전용 필드를 요청으로 보내면 400. 본문에 정의되지 않은 필드는 400으로 거부하는 기본값이다.
- 일반 성공: `{"data": ...}`. 목록: `{"data":{"items":[],"nextCursor":null,"hasMore":false}}`. 204·파일 바이너리는 envelope 없음.
- 실제 순간: ISO-8601 offset 필수. 서버 응답은 `+09:00`으로 통일하는 계약. 날짜는 YYYY-MM-DD, 반복 시각 HH:mm, 업무 시간대 Asia/Seoul.
- 시각 범위 `[from,to)`는 시작 포함·끝 제외. 날짜 범위 입력도 `[fromDate,toDateExclusive)`로 통일한다. 반복 `lastDate`·처방 `endsOn`은 **포함**이다.
- 업무 겹침은 `[startsAt,endsAt)` 기준. 동일 끝/시작 시각은 충돌하지 않는다.
- 목록 `limit` 기본 20, 최대 100은 API 기술 기본값. cursor는 서버가 인코딩한 불투명 문자열로 프론트가 생성하지 않는다. 필터 변경 시 폐기한다.
- 정렬: T01 업무는 개인 미지정 rank ASC,startsAt ASC,id ASC; 기록 occurredOn DESC NULLS LAST,createdAt DESC,id DESC; 알림 createdAt DESC,id DESC. 커서는 각 정렬 키·필터를 포함한다. 잘못된 커서는 400 `INVALID_CURSOR`.
- 기록/알림 목록의 cursor는 업무 개인rank/startsAt cursor와 구별한다. 조회 도중 변경으로 인한 목록 변동은 가능하며 스냅샷 격리를 보장하지 않는다.

### 1.4 오류 envelope

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "다른 가족이 변경한 내용이 있습니다. 새로고침 후 다시 확인해주세요.",
    "requestId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "details": {
      "currentVersion": 2,
      "fieldErrors": []
    }
  }
}
```

`code`, `message`, `requestId`, `details`는 항상 존재. details는 추가 정보 없으면 `{}`. fieldErrors가 있으면 `[{"field":"items[0].payload.endsOn","code":"REQUIRED","message":"종료일을 확인해주세요."}]`. 오류에 토큰·파일 본문·공급자 원문 오류·다른 공동체 일정 ID/내용을 넣지 않는다. 충돌 안내는 현재 공동체 업무만 제한적으로 표시하고 외부 공동체 충돌은 `TIME_CONFLICT`와 일반 안내만 반환한다.

| HTTP | 코드 | 의미 |
|---|---|---|
| 400 | VALIDATION_ERROR, INVALID_CURSOR, IDEMPOTENCY_KEY_REQUIRED | JSON·형식·필수 필드·헤더 오류 |
| 401 | UNAUTHORIZED | 없음·잘못됨·만료·폐기된 토큰, 비활성 계정 |
| 403 | NOT_MEMBER, FORBIDDEN, DEMO_ONLY | 공동체·개인 소유권·역할 또는 시연 범위 위반 |
| 404 | RESOURCE_NOT_FOUND | 없는/제거된 기록·파일·작업 등 |
| 409 | VERSION_CONFLICT, PREVIEW_STALE | 오래된 버전·변경된 미리보기 |
| 409 | TIME_CONFLICT, NOT_AVAILABLE, ALREADY_ASSIGNED | 배정 충돌·가능 시간 부적합·이미 배정 |
| 409 | INVALID_STATE, TASK_OVERDUE, JOB_NOT_RETRYABLE | 상태상 실행할 수 없음 |
| 409 | IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS | 같은 키 다른 요청·같은 요청 진행 중 |
| 409 | REVIEW_REQUIRED, DUPLICATE_MEDICATION_CONFLICT | 확인 필요·암묵 약 병합 불가 |
| 413 | FILE_TOO_LARGE, REQUEST_TOO_LARGE | 파일 자체 또는 인프라 전체 요청 크기 초과 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 미지원 실제 파일 형식 |
| 422 | PDF_PAGE_LIMIT_EXCEEDED, IMAGE_COUNT_LIMIT_EXCEEDED, INVALID_DOCUMENT | 페이지·장수·손상/읽기 불가 |
| 422 | VALIDATION_ERROR | 파싱 이후 필수 의료·일정 정보의 누락 또는 의미 검증 실패 |
| 422 | AUDIO_DURATION_LIMIT_EXCEEDED, INVALID_AUDIO | 음성 길이 초과·읽기 불가(기술 시험 후 고정) |
| 429 | RATE_LIMITED | 서버 요청 제한, Retry-After 있으면 따름 |
| 500 | INTERNAL_ERROR | 예상치 못한 내부 오류 |
| 503 | TEMPORARILY_UNAVAILABLE | 일시적 서비스 장애 |

표 중 기존 코드 외 추가 코드는 이번 API 구현 계약이다. 400은 요청 형식 오류, 422는 파싱 가능한 요청의 콘텐츠 검증 실패, 409는 현 상태/동시성으로 명령 적용 불가로 구분한다. 인프라 413이 JSON이 아닐 수도 있으므로 프론트는 HTTP만으로도 안내한다.

### 1.5 멱등성·버전

- **모든 POST/PUT/PATCH/DELETE 업무 변경**은 `Idempotency-Key` 필수라는 구현 기본값으로 통일한다. 예외: 가입/로그인/로그아웃, 읽기 목적 preview·전화번호 lookup은 불필요. 가입은 정규화 번호 UNIQUE로 중복 방지하며 응답 유실 후 재가입 409이면 로그인으로 이어간다. key는 1~100자 ASCII 식별 문자열, 새 사용자 동작마다 UUID 권장.
- 동일 사용자+operation(메서드·정규 경로/대상 포함)+key, 동일 요청이면 성공 결과의 ID·버전·상태코드를 재반환. 같은 key 다른 내용은 409. multipart hash는 메타 JSON 정규화+파일 순서+각 실제 바이트 해시로 계산하고 boundary는 제외한다.
- 멱등 성공 저장·업무 변경은 한 트랜잭션. 만료 청소 후에도 DB 업무 고유키로 중복 방지. 보관 24시간은 API 기본값 제안. 로그아웃/회원 탈퇴 뒤 기존 멱등 응답으로 권한을 우회하지 못하도록 replay 전에 현재 인증·인가부터 수행한다.
- 같은 요청 동시 실행은 잠금 후 저장 결과 재조회. 대기 한도 초과 시 409 REQUEST_IN_PROGRESS, 같은 키로 재조회/재시도. 임시 업로드는 실패·replay 시 정리한다.
- DB에 version 있는 변경 대상은 `expectedVersion` 필수. 범위 변경은 해당 `seriesVersion`, 인계 수락/거절은 업무와 인계 버전, 확인은 각 후보 버전을 별도로 받는다. 버전 없는 source/job/weekly_work_period는 아래 정의한 inputVersion·textVersion·fingerprint로 검증한다. 파일 저장 상태는 사용자 임의 변경 불가.
- 멱등 성공 replay가 오래된 expectedVersion 때문에 실패하지 않게 성공 기록을 먼저 찾는다(현재 인증·인가 이후). replay 응답은 현재 최신 상태가 아니라 최초 성공 결과일 수 있어 후속 GET으로 갱신한다.

### 1.6 preview 계약

반복 일괄 수정·삭제, 기록 삭제, 가능 시간/활동시간/근무 변경은 저장 전 미리보기. 응답의 `previewToken`은 서버 서명한 불투명 토큰이라는 **이번 구현 제안**이며 새 테이블을 요구하지 않는다. 사용자·operation·대상·정규화 patch·조회 범위·대상 버전·영향 행과 버전의 fingerprint·cutoff·만료를 포함해 서버가 검증한다. 토큰 안에 의료 원문을 넣지 않는다. 수명 5분은 추천 설정.

저장 시 guard/필요 행 잠금 후 권한·버전·영향 범위를 다시 계산한다. 변경된 대상/가능 시간/상태, cutoff 이동으로 영향 건수 변화, 만료는 409 PREVIEW_STALE. 프론트가 전송한 건수나 token만 믿고 저장하지 않는다. 시리즈 버전만 검사해서 중간 발생 건·예외 변경을 놓치지 않는다. 미래 시작 검사는 커밋 시 현재 시각에도 수행한다.

preview는 읽기 전용이며 변경·예약·담당자 해제를 수행하지 않는다. 반환 전체 ID 목록은 이미 생성된 발생 건에 한정. 14일 뒤 생성되지 않은 반복분은 `affectsFutureUnmaterialized=true`와 규칙으로 안내한다.

## 2. 공통 DTO·상태·검증

이 절의 DTO는 각 API 응답에서 재사용한다. 요청 예시에 등장하는 UUID는 가상 값이며 실제 응답 ID를 사용한다. 제약은 예시뿐 아니라 아래 설명을 따른다.

각 API의 예시는 서로 독립된 가상 스냅샷이며 여러 예시 사이의 UUID 재사용이 동일 시점의 데이터 관계를 뜻하지 않는다. 실제 연동에서는 서버가 반환한 ID·버전을 사용한다.

### 2.1 DTO 필드 계약

| DTO | 필수 필드·검증 | 선택/nullable |
|---|---|---|
| UserRef | id UUID | displayName: 부모 정보 미입력일 때 null, 그 외 1~50자 |
| Group | id, name 1~80자, recipient UserRef, status, myMembership, version | parentProfile: 최초 입력 전 null |
| Member | id, user, role, status, version | priority: RECIPIENT는 null, CAREGIVER는 1 또는 2 |
| Encounter | id, groupId, recordType VISIT/DOCUMENT, title 1~150자, createdBy, inputVersion, version, createdAt, processingState, hasReviewItems, isSummaryStale | occurredOn 날짜, hospitalName 최대150자 |
| Source | id, encounterId, sourceType AUDIO/DOCUMENT, documentType, status PENDING/READY/FAILED, textVersion, file, contentPath | documentType은 AUDIO=null; removedAt; contentPath는 원본 unavailable면 null |
| File | id, mediaType, byteSize 양의 정수, state | originalName 최대255자 |
| Job | id, encounterId, jobType TRANSCRIBE/OCR/ANALYZE, inputVersion, status, attemptCount, canRetry | sourceId(ANALYZE=null), error |
| Medication | id, name 1~150자, doseText 1~100자, frequencyText 1~150자, startsOn, endsOn, confirmedBy, confirmedAt | instructions, supersedesId |
| Task | id, groupId, seriesId, seriesVersion, revisionNo, anchorDate, kind, myUnassignedState, canAccept, canDecline, canRelease, title 1~150자, startsAt, endsAt, dueAt, executionStatus, isOverdue, isOverride, medications, sourceEncounterIds, version | description, assignee, assignmentOrigin, openHandoff, completion, cancellation |
| Handoff | id, occurrenceId, reason, status, version | previousAssignee, requestedBy, acceptedBy, closedAt, closeReason |
| ReviewItem | id, encounterId, revisionId, itemType TASK/MEDICATION, reviewState, reviewReasons, payload, evidence, version | reviewedBy, reviewedAt |

`dueAt`는 endsAt의 응답 별칭이며 DB 새 컬럼이 아니다. summary/derived 상태·페이지 수·sourceEncounterIds는 조회 결과다. 세션·lease·outbox 내부 필드는 반환하지 않는다. 텍스트 description/instructions 등 별도 DB 길이 상한 없는 필드는 임의 제품 상한을 추가하지 않으며 서버 요청 크기 제한을 따른다.

### 2.2 반복·업무·근거

- kind: EXAM, MEDICATION, HOSPITAL, OTHER.
- RecurrenceRule 필수: recurrence ONCE/DAILY/WEEKLY, firstDate, lastDate(nullable), weekdays, localTime, durationMinutes(1~1440).
- ONCE: lastDate=firstDate, weekdays=[]; DAILY: weekdays=[]; WEEKLY: 중복 없는 ISO 요일 1~7 배열, 1~7개. lastDate가 있으면 firstDate 이상. MEDICATION은 유한 종료일 필수.
- 한 series는 하루 1회. 하루 2회는 서로 다른 localTime의 두 plan/시리즈. 같은 시각·같은 반복 패턴 약은 서버가 기간을 평가해 하나의 업무로 묶는다. 일반 일정을 약과 합치지 않는다.
- Evidence: sourceId, textVersion, page(nullable, PDF는 1-based), startOffset,endOffset(nullable, 함께 존재). Unicode 코드포인트 [start,end) 기준. JS UTF-16 인덱스로 바로 사용하지 않는다. isStale는 현재 source textVersion과 비교한 파생 필드.
- 완료 completion: performedBy UserRef, completedBy UserRef, completedAt. 취소 cancellation: reason USER_ONE/USER_FUTURE/RULE_CHANGED/RECORD_DELETED,canceledAt.
- assignmentOrigin: AUTO/MANUAL/HANDOFF 또는 assignee가 없으면 null.

### 2.3 처리 상태와 작업 오류

| DTO | 상태 |
|---|---|
| Job.status | QUEUED, RUNNING, SUCCEEDED, FAILED, OBSOLETE |
| Source.status | PENDING, READY, FAILED |
| File.state | UPLOADING, AVAILABLE, DELETE_PENDING, DELETED, FAILED |
| ReviewItem.reviewState | NEEDS_REVIEW, READY, APPLIED, DISMISSED, SUPERSEDED |
| Task.executionStatus | PENDING, COMPLETED, CANCELED |
| Handoff.status | OPEN, ACCEPTED, CLOSED, EXPIRED |

Encounter.processingState는 파생 값: 필요한 source/job 실패 → FAILED; AUDIO 전사 대기/실행 → TRANSCRIBING; 문서 OCR 대기/실행 → OCR_PROCESSING(이번 API 화면용 추가 라벨); 모든 소스 READY이고 현 inputVersion 분석 대기/실행 → ANALYZING; 현 분석+확인 후보 존재 → NEEDS_REVIEW; 현 분석+후보 없음 → READY. 여러 단계가 함께 있으면 실패 우선, 이어 전사→OCR→분석 순으로 대표값을 선택하고 세부 jobs/sources는 모두 반환한다. 소스 없는 기록은 EMPTY(이번 API 화면용 라벨). 브라우저 UPLOADING은 업로드 전 로컬 상태이며 DB 업무 상태가 아니다.

추가 소스 때문에 이전 요약이 있으면 그대로 표시하되 `isSummaryStale=true`. 현재 inputVersion 분석 결과가 없음을 명시한다. 요약 없음은 summary=null이지 기록 404가 아니다. OCR 준비 전 일부 자료를 누락한 성공으로 표시하지 않는다.

Job.error는 null 또는 `{code,message,retryable,userAction}`. `AI_INPUT_LIMIT_EXCEEDED`면 FAILED·retryable=false·파일 조정/분할 안내, 동일 입력 자동 재시도 금지. 일시 실패 `AI_RATE_LIMITED`, `AI_TIMEOUT`, `AI_PROVIDER_UNAVAILABLE`은 재시도 가능 여부를 서버가 판정한다. 공급자 원문 오류는 노출하지 않는다. **GET 작업 조회 HTTP 200의 FAILED와 업로드 validation 4xx는 별개**다.

### 2.4 문서 검증

JPG/JPEG(image/jpeg), PNG(image/png), WEBP(image/webp), PDF(application/pdf). 파일 각각 1~10,000,000 bytes, PDF 1~10페이지, 기록의 제거되지 않은 이미지 DOCUMENT source 합계 최대10장. PDF/AUDIO는 이미지 장수 제외. 실패 OCR source도 삭제 전에는 센다. PDF 파일 개수/기록 전체 PDF 합계에 새 제품 상한을 추가하지 않는다.

서버 실제 signature+parser/decoder+수신 바이트로 검증한다. 빈/손상/암호화되어 열 수 없는 PDF·이미지는 422 INVALID_DOCUMENT. 10MB 정확히 허용, 초과 413; 10페이지/10장 허용, 초과422. 확장자/클라이언트 MIME/pageCount를 신뢰하지 않는다. 여러 파일은 전체 검사 후 하나의 DB 등록 트랜잭션으로 반영. encounter 잠금에서 현재 장수+신규 장수 검사. 실패 시 partial 등록 없음.

기본 업로드는 브라우저→EC2 API multipart로 정의한다. 여러 파일의 합계+multipart overhead를 고려해 reverse proxy·Spring 전체 요청 한도를 따로 정한다. 인프라 한도는 제품 파일 제한·OpenAI 요청 제한과 다르다. 전체 요청이 크면 같은 기록에 순차 업로드할 수 있지만 기록 이미지10장 제한은 유지한다. 과도한 이미지 압축/다운샘플링 금지.

10MB·10페이지·10장은 서비스 MVP 정책. 외부 AI 제한은 실제 선택 모델의 최신 공식 문서로 구현 시 확인한다. 이번 개정에서 공급자 최대치를 재검증했다고 주장하지 않는다. 이 API 문서는 외부 공급자 최대치를 제품 정책으로 사용하지 않는다. 외부 모델 입력 전에 서비스 검증·선택 모델의 실제 입력/context 제한을 재검사한다.

### 2.5 v1.2 공통 DTO와 enum

- accountRole: PARENT / CHILD (가입 후 변경 불가).
- Member.role: RECIPIENT / CAREGIVER. priority1=주돌봄자녀, priority2=공동돌봄자녀, 부모=null.
- Member.status: PENDING / ACTIVE / LEFT. 그룹 정보 전체는 ACTIVE만 가능.
- UserRef.displayName: 정보 입력 전 부모는 null. 프론트 placeholder='부모 정보 입력 전'.
- Group.parentProfile: `{relation:"MOTHER"|"FATHER",name,birthYear,completedAt}` 또는 미입력시 null. completed이면 수정 API 없음.
- Source.documentType: DOCUMENT는 PRESCRIPTION/DIAGNOSIS/MEDICINE_BAG, AUDIO=null. 기존 데이터만 LEGACY_UNCLASSIFIED 응답 가능.
- Task에 `myUnassignedState`(NOT_APPLICABLE/UNRESOLVED/RESOLVED), `canAccept`, `canDecline`, `canRelease`를 추가한다. 부모도 ACTIVE 구성원으로 응답·수락 가능하다.
- Handoff는 내부 미지정 episode다. reason=NO_CANDIDATE/USER_REQUEST/AVAILABILITY/MEMBER_LEFT. UI는 reason별 별도 상태 대신 모두 미지정으로 표시한다.
- 미완료 무담당이면 현재 또는 만료된 최신 episode에 본인 DECLINED가 있으면 RESOLVED, 없으면 UNRESOLVED. 나머지는 NOT_APPLICABLE. 상태 판단은 서버가 한다.
- canAccept/canDecline은 유효 OPEN·미경과·PENDING·미지정·본인 미응답일 때만 true; canAccept는 본인 가능시간/충돌도 만족해야 한다. canRelease는 현재 본인이 담당한 미경과 PENDING만 true. 최종 저장 시 재검사한다.
- 해결 미지정에는 수락 버튼을 제공하지 않는다. T09에서 본인 또는 다른 구성원 담당 지정이 가능하다.
- 가입 신청 DTO: id, groupId, status(PENDING/APPROVED/REJECTED/CANCELED), version, createdAt, decidedAt, membershipStatus, priority. 본인 조회는 최소 부모 이름/관계만 제공하고 건강정보를 넣지 않는다.
- 전화번호: 하이픈/공백 제거 후 `^010[0-9]{8}$`. 이름 trim 후1~50자, 출생연도1900~현재 KST연도. 이번 해커톤 입력 기본값이다.

추가 오류: 409 PHONE_ALREADY_REGISTERED / GROUP_ALREADY_CONNECTED / JOIN_REQUEST_PENDING / REQUEST_ALREADY_DECIDED / LAST_PRIMARY_CAREGIVER / PARENT_PROFILE_IMMUTABLE / RESPONSE_ALREADY_DECLINED; 422 PARENT_PROFILE_REQUIRED / MANUAL_MEDICATION_NOT_ALLOWED / MANUAL_REPEAT_NOT_ALLOWED; 410 ENDPOINT_RETIRED; 403 PRIMARY_CAREGIVER_REQUIRED / NOT_ASSIGNEE. 잘못된 입력 enum은400이다.

## 3. 인증과 사용자

### A01. 기존 시연 계정 목록 — 폐기

`GET /api/v1/auth/demo-accounts`

제품 경로에서 제거한다. 전환 후 410 ENDPOINT_RETIRED. 신규 가입·로그인은 A05/A06. 테스트 fixture의 loginKey는 공개 가입 계약이 아니다.

### A02. 기존 선택 로그인 — 폐기

`POST /api/v1/auth/demo-login`

전환 후 410 ENDPOINT_RETIRED. 기존 유효 Bearer 세션은 만료/폐기 전까지 유지할 수 있으나 현재 인가는 새 규칙을 따른다.

### A03. 내 정보·가입 진행 상태

`GET /api/v1/me`

권한: 인증. 성공200.
응답 data: id, displayName(nullable), accountRole, phoneNumber, membership(null 또는 아래 구조), onboardingState.

```json
{
  "data": {
    "id": "22222222-2222-4222-8222-222222222222",
    "displayName": "자녀 1",
    "accountRole": "CHILD",
    "phoneNumber": "01000000002",
    "membership": {
      "groupId": "11111111-1111-4111-8111-111111111111",
      "status": "PENDING",
      "priority": 2,
      "joinRequestId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    },
    "onboardingState": "WAITING_APPROVAL"
  }
}
```

onboardingState=NO_GROUP/WAITING_APPROVAL/READY/PARENT_PROFILE_PENDING. 부모 미입력 또는 ACTIVE 그룹의 부모 정보 미입력은 마지막 상태다. 승인 전에도 이 API와 본인 신청 조회/취소·개인 설정·로그아웃은 허용한다. ACTIVE 건강정보 권한은 별개다.

### A04. 로그아웃

`POST /api/v1/auth/logout`

- 권한: 인증
- 성공: `204` / 본문 없음

**요청**

본문 없음

**응답**

없음.

**처리·검증**

현재 세션 revoked_at 기록. 다른 세션 유지. 프론트 토큰·건강정보 메모리/Blob 정리. 구독 비활성은 N08로 별도 수행해 다른 기기 영향 없음. 응답 유실 재시도는 이미 폐기되어401일 수 있으며 프론트는 로컬 로그아웃을 마친다.

**주요 오류**

401 UNAUTHORIZED도 프론트에서 로컬 토큰 제거.

### A05. 전화번호 회원가입

`POST /api/v1/auth/signup`

공개, 성공201. 가입/로그인 경로는 해커톤 전화번호 인증 생략 설정으로 제어하며 prod에서도 명시적으로 활성화 가능하다. 가입 disabled면403 DEMO_ONLY. 기본 요청 제한과 공통 no-store/CORS를 적용한다.

부모 요청:
```json
{
  "phoneNumber": "01000000001",
  "accountRole": "PARENT"
}
```
자녀 요청:
```json
{
  "phoneNumber": "01000000002",
  "accountRole": "CHILD",
  "displayName": "자녀 1"
}
```

부모의 displayName/parentProfile은 받지 않는다. 자녀 displayName은 필수다. 부모 계정·공동체·RECIPIENT ACTIVE 멤버를 원자 생성한다. 자녀는 계정만 생성하며 연결은 로그인 후 G03/G04다. 응답 data={user:UserRef,accountRole,groupId(nullable),nextAction:"LOGIN"}. 토큰을 반환하거나 기존 번호를 자동 로그인하지 않는다. 중복 정규화 번호409 PHONE_ALREADY_REGISTERED; 형식400; 비활성 기존 번호도 재가입으로 계정 덮어쓰기 금지.

### A06. 전화번호 로그인

`POST /api/v1/auth/login`

공개, 성공200. 요청:
```json
{
  "phoneNumber": "01000000002"
}
```
응답 data={accessToken,tokenType:"Bearer",expiresAt,user:UserRef,accountRole}. ACTIVE 계정만 허용, 없는/비활성 번호401 UNAUTHORIZED. 비밀번호·SMS 코드 없음. 현재 세션 발급 이후 A03으로 화면 분기한다. 전화번호 조회를 가입으로 자동 전환하지 않는다. secret/전화번호 전체/토큰을 로그에 남기지 않는다.

## 4. 공동체·구성원·연결 승인

### G01. 내 ACTIVE 공동체 목록(최대 1건)

`GET /api/v1/me/care-groups`

인증,200. data.items는 ACTIVE Group 0~1개. PENDING은 이 목록에 넣지 않고 A03/G09에서 상태를 조회한다. 다중 공동체 전환 UI는 없다. 다른 공동체 ID를 추측하여 조회하면403.

### G02. 공동체 상세

`GET /api/v1/care-groups/{groupId}`

ACTIVE 공동체,200. data는 Group(id,name,recipient,parentProfile,status,myMembership,version). recipient.displayName은 최초 정보 미완료면null, parentProfile도null. 완료 후 부모 정보 수정 endpoint는 제공하지 않는다. PENDING/LEFT403.

### G03. 부모 전화번호 조회

`POST /api/v1/care-groups/recipient-lookup`

인증 CHILD,200. 읽기 요청이라 Idempotency-Key 불필요. 요청 `{phoneNumber}`. 없는 부모404, CHILD 번호422 VALIDATION_ERROR. 요청 제한429. 응답 data={recipientUserId,groupId,displayName(nullable),parentRelation(nullable),parentProfileCompleted,joinMode:"FIRST_JOIN"|"APPROVAL_REQUIRED",groupVersion}. 전화번호는 마스킹하고 건강정보/전체 멤버를 반환하지 않는다. joinMode는 미리보기이며 G04 잠금 후 다시 판정한다. 이미 다른 ACTIVE/PENDING 관계가 있으면409 GROUP_ALREADY_CONNECTED.

### G04. 부모 연결 신청·첫 자녀 즉시 가입

`POST /api/v1/care-groups/{groupId}/join`

인증 CHILD, Idempotency-Key 필수. 요청:
```json
{
  "recipientUserId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  "parentProfile": {
    "relation": "MOTHER",
    "name": "가상 부모",
    "birthYear": 1960
  }
}
```

parentProfile은 최초 부모 정보 미입력에서 첫 자녀로 합류할 경우에만 필수. 이미 입력 완료면 생략한다. 결정적 순서 잠금 후 현재 자녀 수/단일 공동체 상태를 다시 검사한다. ACTIVE 자녀0이면 priority1 ACTIVE(201), 그 외 priority2 PENDING(202). 동시 첫 요청의 늦은 요청은 대기로 전환하고 제출한 parentProfile을 적용하지 않는다. 응답 data={request:JoinRequest,membership:Member,nextAction:"READY"|"WAITING_APPROVAL"}; 같은 ACTIVE 관계의 새 요청은200 기존 관계, 같은 PENDING의 새 요청은200 기존 신청을 반환한다. 다른 공동체 관계409.

최초 부모 성함·관계·출생연도 저장과 즉시 가입은 원자적이다. 첫 가입인데 필요한 정보가 없으면422 PARENT_PROFILE_REQUIRED. parentProfile 완료 후 다른 값을 제출하여 변경하려는 요청은409 PARENT_PROFILE_IMMUTABLE(동시 첫 연결의 늦은 요청은 대기 전환만 수행). 신청인은 부모 정보를 변경하지 않는다. 승인 대기에는 건강정보 접근 없음.

### G05. 구성원 목록

`GET /api/v1/care-groups/{groupId}/members`

ACTIVE 공동체,200. data.items에 ACTIVE 구성원만 반환. 각 Member={id,user,role,priority,status,version}; priority1/2를 주돌봄자녀/공동돌봄자녀로 표시한다. PENDING 승인 목록은 G10으로 분리한다. 담당 선택 체크는 Task.assignee.id와 user.id를 비교한다. 목록 노출이 배정 가능성을 보장하지 않으며 저장에서 검사한다.

### G06. 자녀 역할 변경

`PUT /api/v1/care-groups/{groupId}/member-priorities`

모든 ACTIVE 구성원(부모 포함),200. 요청:
```json
{
  "items": [
    {
      "memberId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "expectedVersion": 0,
      "priority": 1
    }
  ]
}
```
items는 변경할 ACTIVE CAREGIVER 목록이며 중복 ID 금지. 각 priority는1/2만 가능. 최종 상태에서 ACTIVE 자녀가 있으면 priority1이 최소1명이어야 한다. 한 트랜잭션에서 전부 검증·적용·version 증가·audit. 기존 담당자 유지. 응답 data={items:Member[]}. 부모 변경403 FORBIDDEN, 버전409 VERSION_CONFLICT, 최종 주돌봄자녀0이면409 LAST_PRIMARY_CAREGIVER.

### G07. 공동체 탈퇴

`POST /api/v1/care-groups/{groupId}/memberships/me/leave`

본인 ACTIVE CAREGIVER,200. 요청 `{expectedVersion}`. 다른 ACTIVE 자녀가 남는데 본인이 마지막 priority1이면409 LAST_PRIMARY_CAREGIVER; 먼저 G06으로 승격한다. 유일한 자녀면 탈퇴 가능하다. PENDING은 G12를 사용한다.

미래 배정 해제+MEMBER_LEFT episode+즉시 인계 알림+본인 미발송 건강정보 알림 차단을 원자 처리한다. 과거 이력·부모 정보·세션·구독은 보존한다. 부모 탈퇴403. 응답 data={membership:Member,releasedOccurrenceIds:UUID[]}.

### G08. 기존 Today 홈 — 폐기

`GET /api/v1/care-groups/{groupId}/home`

전환 후410 ENDPOINT_RETIRED. 메인은 T17 월 캘린더와 T01 날짜별 목록을 사용한다. 진료 목록은 E02, 개인 가능 시간은 V01~V06. 구형 Today 집계 DTO를 계속 확장하지 않는다.

### G09. 내 가입 신청 상태

`GET /api/v1/me/join-requests/current`

인증,200. data.request=최근 신청 JoinRequest 또는null, data.membership=현재 상태 최소 메타. PENDING 신청자 본인도 접근 가능. 취소/거절 상태는 다시 신청할 때까지 최신 이력으로 확인 가능하다. 건강정보 없음. 대기 화면은 이 API를 제한된 polling 또는 재진입 시 조회한다.

### G10. 승인 대기 목록

`GET /api/v1/care-groups/{groupId}/join-requests`

ACTIVE priority1 자녀만,200. limit/cursor, createdAt ASC,id ASC. data.items는 PENDING 요청(id,user:{id,displayName},createdAt,version), nextCursor/hasMore. 비주돌봄자녀403 PRIMARY_CAREGIVER_REQUIRED. 전체 전화번호는 반환하지 않는다.

### G11. 가입 승인·거절

`POST /api/v1/care-groups/{groupId}/join-requests/{requestId}/decision`

현재 ACTIVE priority1 자녀만,200. 요청 `{expectedVersion,decision:"APPROVE"|"REJECT"}`. 잠금 후 권한·PENDING·신청자의 단일 공동체 관계를 검사한다. 승인시 priority2 ACTIVE, 거절시 멤버LEFT, 신청 결정자/시각/버전을 남긴다. 응답 data={request:JoinRequest}. 이미 결정409 REQUEST_ALREADY_DECIDED, 버전409, 권한403. 같은 멱등 키 replay는 권한 재검사 후 최초 성공 반환한다.

### G12. 내 가입 신청 취소

`POST /api/v1/me/join-requests/{requestId}/cancel`

본인 신청,200. 요청 `{expectedVersion}`. PENDING→CANCELED와 멤버LEFT 원자 적용, 응답 data={request:JoinRequest}. 다른 사람403. 이미 APPROVED면409 REQUEST_ALREADY_DECIDED이며 일반 탈퇴 절차로 전환한다. 뒤로 가기는 취소 성공 확인 후 이동한다. 네트워크 실패/브라우저 종료를 취소 성공으로 가정하지 않는다.

## 5. 진료 기록·파일·AI 작업

### E01. 기록 생성

`POST /api/v1/care-groups/{groupId}/encounters`

- 권한: 공동체
- 성공: `201`

**요청**

```json
{
  "recordType": "VISIT",
  "title": "가상 진료 기록",
  "occurredOn": "2026-10-02",
  "hospitalName": null
}
```

**응답**

```json
{
  "data": {
    "id": "33333333-3333-4333-8333-333333333333",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "recordType": "VISIT",
    "title": "가상 진료 기록",
    "occurredOn": "2026-10-02",
    "hospitalName": null,
    "createdBy": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    },
    "inputVersion": 1,
    "version": 0,
    "createdAt": "2026-10-02T18:00:00+09:00",
    "processingState": "EMPTY",
    "hasReviewItems": false,
    "isSummaryStale": false
  }
}
```

**처리·검증**

recordType/title 필수, occurredOn/hospitalName 선택 nullable. DOCUMENT는 독립 문서 묶음이며 이후 업로드와 동일 파이프라인. 소스 없는 기록은 분석 시작하지 않는다. 파일 실패 시 빈 기록은 재사용/삭제 가능. 서버 createdBy 지정.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER,409 INVALID_STATE.

### E02. 기록 목록

`GET /api/v1/care-groups/{groupId}/encounters`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `fromDate`, `toDateExclusive` 선택(occurredOn 필터), `recordType` 선택, `limit`, `cursor`.

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "33333333-3333-4333-8333-333333333333",
        "groupId": "11111111-1111-4111-8111-111111111111",
        "recordType": "VISIT",
        "title": "가상 진료 기록",
        "occurredOn": "2026-10-02",
        "hospitalName": null,
        "createdBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "inputVersion": 1,
        "version": 0,
        "createdAt": "2026-10-02T18:00:00+09:00",
        "processingState": "ANALYZING",
        "hasReviewItems": false,
        "isSummaryStale": false
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

**처리·검증**

미삭제 기록만 조회한다. 날짜 필터를 사용하면 occurredOn=null인 기록은 제외하며, 필터가 없으면 포함한다. 실패·확인 필요·요약 최신 여부를 표시한다. 정렬과 cursor는 1.3절을 따른다.

**주요 오류**

400 VALIDATION_ERROR/INVALID_CURSOR,403 NOT_MEMBER.

### E03. 진료/문서 기록 상세

`GET /api/v1/encounters/{encounterId}`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "33333333-3333-4333-8333-333333333333",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "recordType": "VISIT",
    "title": "가상 진료 기록",
    "occurredOn": "2026-10-02",
    "hospitalName": null,
    "createdBy": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    },
    "inputVersion": 1,
    "version": 0,
    "createdAt": "2026-10-02T18:00:00+09:00",
    "processingState": "NEEDS_REVIEW",
    "hasReviewItems": true,
    "isSummaryStale": false,
    "summary": {
      "revisionId": "66666666-6666-4666-8666-666666666666",
      "inputVersion": 1,
      "text": "가상 자료에 기록된 다음 진료 일정을 정리했습니다.",
      "details": {
        "schemaVersion": 2,
        "symptoms": [],
        "tests": [],
        "medicationMentions": [],
        "precautions": [],
        "followUps": []
      },
      "evidence": [
        {
          "sourceId": "44444444-4444-4444-8444-444444444444",
          "textVersion": 1,
          "page": null,
          "startOffset": 0,
          "endOffset": 9,
          "isStale": false
        }
      ]
    },
    "sources": [
      {
        "id": "44444444-4444-4444-8444-444444444444",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "sourceType": "DOCUMENT",
        "status": "READY",
        "textVersion": 1,
        "removedAt": null,
        "file": {
          "id": "88888888-8888-4888-8888-888888888888",
          "originalName": "가상처방.png",
          "mediaType": "image/png",
          "byteSize": 82000,
          "state": "AVAILABLE"
        },
        "contentPath": "/api/v1/sources/44444444-4444-4444-8444-444444444444/content",
        "documentType": "PRESCRIPTION"
      }
    ],
    "jobs": [
      {
        "id": "99999999-9999-4999-8999-999999999999",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "sourceId": "44444444-4444-4444-8444-444444444444",
        "jobType": "OCR",
        "inputVersion": 1,
        "status": "SUCCEEDED",
        "attemptCount": 0,
        "error": null,
        "canRetry": false
      }
    ],
    "reviewItems": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "revisionId": "66666666-6666-4666-8666-666666666666",
        "itemType": "MEDICATION",
        "reviewState": "NEEDS_REVIEW",
        "reviewReasons": [
          "MED_CHANGE"
        ],
        "payload": {
          "schemaVersion": 2,
          "name": "가상 A약",
          "doseText": "1회 1정",
          "frequencyText": "하루 1회",
          "startsOn": "2026-10-05",
          "endsOn": "2026-10-10",
          "instructions": null,
          "supersedesMedicationId": null,
          "schedulePlans": [
            {
              "recurrence": "DAILY",
              "firstDate": "2026-10-05",
              "lastDate": "2026-10-10",
              "weekdays": [],
              "localTime": "08:00",
              "durationMinutes": 30
            }
          ]
        },
        "evidence": [
          {
            "sourceId": "44444444-4444-4444-8444-444444444444",
            "textVersion": 1,
            "page": null,
            "startOffset": 0,
            "endOffset": 9,
            "isStale": false
          }
        ],
        "reviewedBy": null,
        "reviewedAt": null,
        "version": 0
      }
    ],
    "linkedTasks": [
      {
        "id": "55555555-5555-4555-8555-555555555555",
        "groupId": "11111111-1111-4111-8111-111111111111",
        "seriesId": "66666666-6666-4666-8666-666666666666",
        "seriesVersion": 0,
        "revisionNo": 1,
        "anchorDate": "2026-10-05",
        "kind": "MEDICATION",
        "title": "아침 약 챙기기",
        "description": null,
        "startsAt": "2026-10-05T08:00:00+09:00",
        "endsAt": "2026-10-05T08:30:00+09:00",
        "dueAt": "2026-10-05T08:30:00+09:00",
        "executionStatus": "PENDING",
        "assignee": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "assignmentOrigin": "AUTO",
        "openHandoff": null,
        "isOverdue": false,
        "isOverride": false,
        "medications": [
          {
            "id": "77777777-7777-4777-8777-777777777777",
            "name": "가상 A약",
            "doseText": "1회 1정",
            "frequencyText": "하루 1회",
            "startsOn": "2026-10-05",
            "endsOn": "2026-10-10",
            "instructions": null,
            "confirmedBy": {
              "id": "22222222-2222-4222-8222-222222222222",
              "displayName": "자녀 1"
            },
            "confirmedAt": "2026-10-02T18:00:00+09:00",
            "supersedesId": null
          }
        ],
        "completion": null,
        "cancellation": null,
        "sourceEncounterIds": [
          "33333333-3333-4333-8333-333333333333"
        ],
        "version": 0,
        "myUnassignedState": "NOT_APPLICABLE",
        "canAccept": false,
        "canDecline": false,
        "canRelease": false
      }
    ],
    "linkedTasksHasMore": false
  }
}
```

**처리·검증**

Encounter, 현재 revision, 활성 source, jobs, 현재 후보, 연결 업무를 결합한다. summary=null도 정상 응답이다. details의 각 섹션은 [{text,evidence}] 배열이다. linkedTasks는 시작 시각순 기본 20개이며 더보기는 T01의 encounterId 필터를 사용한다. sources·reviewItems·jobs는 해당 기록의 현재 입력에 필요한 전체 목록이다. 과거 입력의 요약은 isSummaryStale로 표시한다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### E04. 기록 메타 수정

`PATCH /api/v1/encounters/{encounterId}`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "title": "가상 진료 기록 수정",
  "occurredOn": "2026-10-02",
  "hospitalName": null
}
```

**응답**

```json
{
  "data": {
    "id": "33333333-3333-4333-8333-333333333333",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "recordType": "VISIT",
    "title": "가상 진료 기록 수정",
    "occurredOn": "2026-10-02",
    "hospitalName": null,
    "createdBy": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    },
    "inputVersion": 1,
    "version": 1,
    "createdAt": "2026-10-02T18:00:00+09:00",
    "processingState": "ANALYZING",
    "hasReviewItems": false,
    "isSummaryStale": false
  }
}
```

**처리·검증**

title·occurredOn·hospitalName 중 최소 한 필드를 제공한다. 생략은 유지, nullable 필드의 null은 지움을 의미한다. 메타 정보는 분석 입력으로 사용하지 않는 구현 계약으로 정하며 inputVersion을 증가시키거나 재분석하지 않는다. 진료일 변경만으로 날짜 미상 후보를 자동 확정하지 않는다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER,409 VERSION_CONFLICT.

### E05. 문서 일괄 업로드

`POST /api/v1/encounters/{encounterId}/documents`

- 권한: 공동체
- 성공: `202`

**요청**

`multipart/form-data`

| part | 타입 | 필수 | 값 |
|---|---|---|---|
| metadata | application/json | 예 | `{ "expectedVersion":0, "expectedInputVersion":1, "documents":[{"documentType":"PRESCRIPTION"}] }` |
| files | binary 반복 part | 예 | 하나 이상의 원본 문서 |

브라우저가 multipart boundary를 생성하도록 Content-Type을 수동 설정하지 않는다. metadata.documents 길이는 files 개수와 같고 인덱스로 대응한다. 각 documentType은 PRESCRIPTION/DIAGNOSIS/MEDICINE_BAG 중 하나 필수다. 누락/미지원 값400, LEGACY_UNCLASSIFIED 신규 제출금지. 파일 순서는 응답 sources 순서와 같다.

**응답**

```json
{
  "data": {
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "inputVersion": 2,
    "version": 1,
    "sources": [
      {
        "id": "44444444-4444-4444-8444-444444444444",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "sourceType": "DOCUMENT",
        "status": "PENDING",
        "textVersion": 0,
        "removedAt": null,
        "file": {
          "id": "88888888-8888-4888-8888-888888888888",
          "originalName": "가상처방.png",
          "mediaType": "image/png",
          "byteSize": 82000,
          "state": "AVAILABLE"
        },
        "contentPath": "/api/v1/sources/44444444-4444-4444-8444-444444444444/content",
        "documentType": "PRESCRIPTION"
      }
    ],
    "jobs": [
      {
        "id": "99999999-9999-4999-8999-999999999999",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "sourceId": "44444444-4444-4444-8444-444444444444",
        "jobType": "OCR",
        "inputVersion": 2,
        "status": "QUEUED",
        "attemptCount": 0,
        "error": null,
        "canRetry": false
      }
    ],
    "activeImageCount": 1
  }
}
```

**처리·검증**

2.4절의 파일 검증을 모두 마친 후 encounter 행을 잠그고 권한·expectedVersion·expectedInputVersion·이미지 합계를 재검사한다. 모든 소스를 등록하고 inputVersion을 한 번 증가시키며, 소스별 OCR 작업을 QUEUED로 생성하는 과정을 원자적으로 처리한다. 신규 textVersion은 0이다. 202는 접수를 의미하며 OCR 성공이 아니다. PDF와 이미지 혼합을 허용한다. PDF pageCount는 업로드 시 계산해 file DTO에 선택적으로 반환할 수 있지만 영속 컬럼 또는 상세 응답의 필수 필드가 아니다.

**주요 오류**

413 FILE_TOO_LARGE/REQUEST_TOO_LARGE,415 UNSUPPORTED_MEDIA_TYPE,422 PDF_PAGE_LIMIT_EXCEEDED/IMAGE_COUNT_LIMIT_EXCEEDED/INVALID_DOCUMENT,409 VERSION_CONFLICT,403 NOT_MEMBER.

### E06. 사용 확인한 음성 업로드

`POST /api/v1/encounters/{encounterId}/audio`

- 권한: 공동체
- 성공: `202`

**요청**

`multipart/form-data`: metadata(application/json) `{ "expectedVersion":0, "expectedInputVersion":1 }`, file(binary) 1개. ‘이 녹음을 사용할까요?’에서 사용을 선택한 후 호출. 삭제 선택은 서버 호출 없이 로컬 Blob 해제.

**응답**

```json
{
  "data": {
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "inputVersion": 2,
    "version": 1,
    "source": {
      "id": "44444444-4444-4444-8444-444444444444",
      "encounterId": "33333333-3333-4333-8333-333333333333",
      "sourceType": "AUDIO",
      "status": "PENDING",
      "textVersion": 0,
      "removedAt": null,
      "file": {
        "id": "88888888-8888-4888-8888-888888888888",
        "originalName": "가상녹음.wav",
        "mediaType": "audio/wav",
        "byteSize": 82000,
        "state": "AVAILABLE"
      },
      "contentPath": null,
      "documentType": null
    },
    "job": {
      "id": "99999999-9999-4999-8999-999999999999",
      "encounterId": "33333333-3333-4333-8333-333333333333",
      "sourceId": "44444444-4444-4444-8444-444444444444",
      "jobType": "TRANSCRIBE",
      "inputVersion": 2,
      "status": "QUEUED",
      "attemptCount": 0,
      "error": null,
      "canRetry": false
    }
  }
}
```

**처리·검증**

metadata의 expectedVersion은 encounter.version, expectedInputVersion은 encounter.inputVersion과 비교한다. 잠금 후 둘 중 하나라도 불일치하면409 VERSION_CONFLICT로 거부하며 자료를 등록하지 않는다. 성공 시 새 AUDIO source를 등록하고 version과 inputVersion을 증가시킨다. 응답 data.version/data.inputVersion을 다음 요청에 사용하며 프론트가 직접 증가시키지 않는다.

현재 검증된 형식은 실제 RIFF/WAVE PCM의 audio/wav 또는 audio/x-wav다. 서버 parser로 실제 구조·크기·길이를 검사하며 duration<=1200초, byteSize<=25,000,000이다. 모바일 추가 형식은 실기기 검증 후 별도 반영한다. 지원하지 않는 실제 형식은415다. 전사 후 모든 소스가 준비되면 ANALYZE를 실행한다. 전사 저장 후 원본 삭제를 예약하고, 업로드 시 최대 24시간 expiresAt을 설정한다. 문서의 10MB 제한을 음성에 적용하지 않는다.

**주요 오류**

413 FILE_TOO_LARGE,415 UNSUPPORTED_MEDIA_TYPE,422 INVALID_AUDIO/AUDIO_DURATION_LIMIT_EXCEEDED,409 VERSION_CONFLICT,403 NOT_MEMBER.

### E07. 원문 텍스트 조회

`GET /api/v1/sources/{sourceId}/text`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "sourceId": "44444444-4444-4444-8444-444444444444",
    "sourceType": "DOCUMENT",
    "status": "READY",
    "textVersion": 1,
    "text": "가상 문서 원문",
    "documentType": "PRESCRIPTION"
  }
}
```

**처리·검증**

READY이면 추출 텍스트를 반환한다. PENDING·FAILED이면 text=null일 수 있으며 200으로 상태를 반환한다. 활성 소스와 기록의 공동체 권한을 검사한다. 프론트는 원문을 HTML로 실행하지 않고 텍스트로 렌더링한다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### E08. 전사 원문 수정

`PATCH /api/v1/sources/{sourceId}/text`

- 권한: 공동체
- 성공: `202`

**요청**

```json
{
  "expectedInputVersion": 2,
  "expectedTextVersion": 1,
  "text": "수정한 가상 전사 원문"
}
```

**응답**

```json
{
  "data": {
    "sourceId": "44444444-4444-4444-8444-444444444444",
    "textVersion": 2,
    "inputVersion": 3,
    "encounterVersion": 2,
    "analysisJobId": "99999999-9999-4999-8999-999999999999"
  }
}
```

**처리·검증**

READY인 AUDIO 전사에 한정하는 구현 계약이다. source에는 version 컬럼이 없으므로 부모 inputVersion과 source textVersion을 검사한다. 빈 텍스트는 거부한다. 저장, textVersion·inputVersion 증가, ANALYZE 접수를 원자적으로 처리한다. 이전 근거에는 stale을 표시한다. OCR 결과 정정은 후보 payload 수정으로 처리하며 OCR 텍스트 편집 기능은 추가하지 않는다.

**주요 오류**

400 VALIDATION_ERROR,409 VERSION_CONFLICT/INVALID_STATE,403 NOT_MEMBER.

### E09. 문서 원본 열람

`GET /api/v1/sources/{sourceId}/content`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

바이너리 원본. `Content-Type`=서버 확인 MIME, `Content-Disposition: inline`+안전한 파일명, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.

**처리·검증**

활성 DOCUMENT source와 AVAILABLE asset만 반환. 비공개 파일에서 스트리밍. 전사 후 삭제되는 AUDIO 다운로드는 MVP공개 API로 제공하지 않는다. 인증 fetch→Blob URL→표시, 언마운트/계정전환 revokeObjectURL. 토큰 query·공개 URL 금지. 큰 PDF Range 기능은 이번 명세에서 요구하지 않는다.

**주요 오류**

403 NOT_MEMBER/FORBIDDEN(AUDIO),404 RESOURCE_NOT_FOUND(삭제/사용불가).

### E10. 소스 제거

`DELETE /api/v1/encounters/{encounterId}/sources/{sourceId}`

- 권한: 공동체
- 성공: `202`

**요청**

```json
{
  "expectedVersion": 1,
  "expectedInputVersion": 2
}
```

**응답**

```json
{
  "data": {
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "sourceId": "44444444-4444-4444-8444-444444444444",
    "inputVersion": 3,
    "version": 2,
    "fileState": "DELETE_PENDING",
    "analysisJobId": "99999999-9999-4999-8999-999999999999"
  }
}
```

**처리·검증**

활성 source와 파일의 제거 접수, inputVersion 증가, 기존 작업 무효화, 재분석 접수를 원자적으로 처리한다. 장수 계산과 제거는 같은 encounter 잠금 규약을 따른다. 모든 소스를 제거하면 analysisJobId=null이고 processingState=EMPTY다. 객체 파기는 비동기이며 202가 삭제 완료를 뜻하지 않는다. 기적용 처방을 조용히 중단하지 않고 필요하면 새 분석에서 재검토 후보로 제시한다. 실행 정보 변경은 확인 흐름을 따른다. removedAt이 기록된 소스는 이후 조회 시 404다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND,409 VERSION_CONFLICT.

### E11. 기록 작업 목록

`GET /api/v1/encounters/{encounterId}/jobs`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `inputVersion` 선택(없으면 현재).

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "99999999-9999-4999-8999-999999999999",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "sourceId": "44444444-4444-4444-8444-444444444444",
        "jobType": "OCR",
        "inputVersion": 1,
        "status": "QUEUED",
        "attemptCount": 0,
        "error": null,
        "canRetry": false
      }
    ]
  }
}
```

**처리·검증**

입력 버전별 작업 전체를 반환한다. Job DTO만 제공하며 lease·공급자 민감 오류는 노출하지 않는다. 오래된 작업은 OBSOLETE일 수 있다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND,400 VALIDATION_ERROR.

### E12. 작업 상세 조회

`GET /api/v1/processing-jobs/{jobId}`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "99999999-9999-4999-8999-999999999999",
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "sourceId": "44444444-4444-4444-8444-444444444444",
    "jobType": "OCR",
    "inputVersion": 1,
    "status": "FAILED",
    "attemptCount": 0,
    "error": {
      "code": "AI_INPUT_LIMIT_EXCEEDED",
      "message": "입력 제한으로 처리하지 못했습니다.",
      "retryable": false,
      "userAction": "필요한 페이지만 준비해 다시 업로드해주세요."
    },
    "canRetry": false
  }
}
```

**처리·검증**

실패 작업도 200으로 반환한다. canRetry와 error를 기준으로 재시도 버튼을 제어한다. 부모 기록 삭제 후에는 404다. polling은 2초 간격을 추천하며 완료·실패·OBSOLETE·화면 이탈 시 중지하고 장기 대기에서는 간격을 늘린다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### E13. 실패 작업 재시도

`POST /api/v1/processing-jobs/{jobId}/retry`

- 권한: 공동체
- 성공: `202`

**요청**

```json
{
  "expectedInputVersion": 2
}
```

**응답**

```json
{
  "data": {
    "id": "99999999-9999-4999-8999-999999999999",
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "sourceId": "44444444-4444-4444-8444-444444444444",
    "jobType": "OCR",
    "inputVersion": 1,
    "status": "QUEUED",
    "attemptCount": 1,
    "error": null,
    "canRetry": false
  }
}
```

**처리·검증**

현재 입력에 유효한 FAILED 작업이고 canRetry=true일 때만 허용한다. 소스 제거·원본 음성 삭제·입력 제한 오류이면 거부한다. 요약 재시도는 보존한 텍스트를 사용한다. 같은 논리 Job을 QUEUED로 되돌리며 새로운 소스 또는 중복 일정을 만들지 않는다. 작업 행 잠금과 상태 조건을 검사한다.

**주요 오류**

409 VERSION_CONFLICT/JOB_NOT_RETRYABLE/INVALID_STATE,403 NOT_MEMBER.

### E14. 기록 삭제 영향 미리보기

`POST /api/v1/encounters/{encounterId}/deletion-preview`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 2
}
```

**응답**

```json
{
  "data": {
    "previewToken": "<signed-preview>",
    "expiresAt": "2026-10-02T18:05:00+09:00",
    "encounterVersion": 2,
    "removeSourceIds": [
      "44444444-4444-4444-8444-444444444444"
    ],
    "cancelOccurrenceIds": [],
    "retainSharedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "removeMedicationIds": [
      "77777777-7777-4777-8777-777777777777"
    ],
    "preserveCompletedHistory": true
  }
}
```

**처리·검증**

연결 약, 공유 복약 업무, 연결 미래 미완료 일정, 원본 파기 영향을 반환한다. 공유 업무에서는 이 기록에서 유래한 약만 제외하고 다른 약은 유지한다. 완료 이력의 최소 표시 보존도 안내한다. 1.6절의 preview 검증을 적용한다.

**주요 오류**

403 NOT_MEMBER,409 VERSION_CONFLICT,404 RESOURCE_NOT_FOUND.

### E15. 기록 삭제·파기 접수

`DELETE /api/v1/encounters/{encounterId}`

- 권한: 공동체
- 성공: `202`

**요청**

```json
{
  "expectedVersion": 2,
  "previewToken": "<signed-preview>"
}
```

**응답**

```json
{
  "data": {
    "encounterId": "33333333-3333-4333-8333-333333333333",
    "deletionAccepted": true,
    "fileDeletionPending": true
  }
}
```

**처리·검증**

deletedAt을 설정해 즉시 접근을 차단하고 작업을 무효화한다. preview를 재검증한 후 약·일정을 정리한다. 별도 파기 작업이 파일·전사·요약·후보·민감 snapshot을 정리한다. 완료 이력은 일정명·수행자·시각 수준을 보존하고 약명 등은 치환한다. 202를 외부 공급자까지 포함한 즉시 삭제 완료로 표시하지 않는다. 삭제 접수 후 상세 조회는 404다.

**주요 오류**

409 VERSION_CONFLICT/PREVIEW_STALE,403 NOT_MEMBER.

## 6. 확인 후보·처방

### R01. 확인 후보 목록

`GET /api/v1/encounters/{encounterId}/review-items`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `reviewState` 선택(기본 NEEDS_REVIEW, 생략과 다른 전체 조회는 `reviewState=ALL`).

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "encounterId": "33333333-3333-4333-8333-333333333333",
        "revisionId": "66666666-6666-4666-8666-666666666666",
        "itemType": "MEDICATION",
        "reviewState": "NEEDS_REVIEW",
        "reviewReasons": [
          "MED_CHANGE"
        ],
        "payload": {
          "schemaVersion": 2,
          "name": "가상 A약",
          "doseText": "1회 1정",
          "frequencyText": "하루 1회",
          "startsOn": "2026-10-05",
          "endsOn": "2026-10-10",
          "instructions": null,
          "supersedesMedicationId": null,
          "schedulePlans": [
            {
              "recurrence": "DAILY",
              "firstDate": "2026-10-05",
              "lastDate": "2026-10-10",
              "weekdays": [],
              "localTime": "08:00",
              "durationMinutes": 30
            }
          ]
        },
        "evidence": [
          {
            "sourceId": "44444444-4444-4444-8444-444444444444",
            "textVersion": 1,
            "page": null,
            "startOffset": 0,
            "endOffset": 9,
            "isStale": false
          }
        ],
        "reviewedBy": null,
        "reviewedAt": null,
        "version": 0
      }
    ],
    "inputVersion": 2,
    "revisionId": "66666666-6666-4666-8666-666666666666",
    "isAnalysisCurrent": true
  }
}
```

**처리·검증**

현재 revision의 후보만 반환한다. ALL은 API 조회 필터이며 DB enum이 아니다. 확인 사유·원문 근거·누락 필드를 표시한다. 미해결 CONFLICT는 승인할 수 없다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### R02. 후보 일괄 확인·수정·적용

`POST /api/v1/encounters/{encounterId}/review-items/confirm`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedInputVersion": 2,
  "revisionId": "66666666-6666-4666-8666-666666666666",
  "items": [
    {
      "itemId": "77777777-7777-4777-8777-777777777777",
      "expectedVersion": 0,
      "payload": {
        "schemaVersion": 2,
        "name": "가상 A약",
        "doseText": "1회 1정",
        "frequencyText": "하루 1회",
        "startsOn": "2026-10-05",
        "endsOn": "2026-10-10",
        "instructions": null,
        "supersedesMedicationId": null,
        "schedulePlans": [
          {
            "recurrence": "DAILY",
            "firstDate": "2026-10-05",
            "lastDate": "2026-10-10",
            "weekdays": [],
            "localTime": "08:00",
            "durationMinutes": 30
          }
        ]
      },
      "conflictResolution": null
    }
  ]
}
```

**응답**

```json
{
  "data": {
    "appliedItemIds": [
      "77777777-7777-4777-8777-777777777777"
    ],
    "medications": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "name": "가상 A약",
        "doseText": "1회 1정",
        "frequencyText": "하루 1회",
        "startsOn": "2026-10-05",
        "endsOn": "2026-10-10",
        "instructions": null,
        "confirmedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "confirmedAt": "2026-10-02T18:00:00+09:00",
        "supersedesId": null
      }
    ],
    "seriesIds": [
      "66666666-6666-4666-8666-666666666666"
    ],
    "occurrences": [
      {
        "id": "55555555-5555-4555-8555-555555555555",
        "groupId": "11111111-1111-4111-8111-111111111111",
        "seriesId": "66666666-6666-4666-8666-666666666666",
        "seriesVersion": 0,
        "revisionNo": 1,
        "anchorDate": "2026-10-05",
        "kind": "MEDICATION",
        "title": "아침 약 챙기기",
        "description": null,
        "startsAt": "2026-10-05T08:00:00+09:00",
        "endsAt": "2026-10-05T08:30:00+09:00",
        "dueAt": "2026-10-05T08:30:00+09:00",
        "executionStatus": "PENDING",
        "assignee": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "assignmentOrigin": "AUTO",
        "openHandoff": null,
        "isOverdue": false,
        "isOverride": false,
        "medications": [
          {
            "id": "77777777-7777-4777-8777-777777777777",
            "name": "가상 A약",
            "doseText": "1회 1정",
            "frequencyText": "하루 1회",
            "startsOn": "2026-10-05",
            "endsOn": "2026-10-10",
            "instructions": null,
            "confirmedBy": {
              "id": "22222222-2222-4222-8222-222222222222",
              "displayName": "자녀 1"
            },
            "confirmedAt": "2026-10-02T18:00:00+09:00",
            "supersedesId": null
          }
        ],
        "completion": null,
        "cancellation": null,
        "sourceEncounterIds": [
          "33333333-3333-4333-8333-333333333333"
        ],
        "version": 0,
        "myUnassignedState": "NOT_APPLICABLE",
        "canAccept": false,
        "canDecline": false,
        "canRelease": false
      }
    ],
    "inputVersion": 2
  }
}
```

**처리·검증**

items는 비어 있지 않아야 하며 ID 중복을 허용하지 않는다. 모든 후보가 같은 기록의 현재 revision에 속하고 분석 결과가 최신이며 inputVersion이 일치해야 한다. payload 생략 시 기존 값을 사용하고 제공 시 전체 교체 후 스키마를 검사한다. 누락 필드·미해결 충돌·오래된 후보가 하나라도 있으면 전체 실패하며 부분 적용하지 않는다. 약은 name·doseText·frequencyText·startsOn·endsOn과 명시한 시각의 schedulePlans가 필수다. 횟수만 보고 시각을 추정하지 않는다. 확인자·시각, 약, 시리즈, 발생 건, 배정, outbox를 한 트랜잭션으로 반영한다. 변경 처방은 supersedesMedicationId와 startsOn을 확인한 후 미래에만 적용한다. 조건부 계획을 확인 없이 확정 일정으로 만들지 않는다. conflictResolution은 6.1절을 따른다.

**주요 오류**

409 VERSION_CONFLICT/REVIEW_REQUIRED/DUPLICATE_MEDICATION_CONFLICT,422 VALIDATION_ERROR,403 NOT_MEMBER.

### R03. 후보 제외

`POST /api/v1/encounters/{encounterId}/review-items/{itemId}/dismiss`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0
}
```

**응답**

```json
{
  "data": {
    "id": "77777777-7777-4777-8777-777777777777",
    "reviewState": "DISMISSED",
    "version": 1
  }
}
```

**처리·검증**

NEEDS_REVIEW·READY만 제외할 수 있다. APPLIED 처방의 삭제·중단 기능이 아니다. “나중에”로 UI를 닫을 때는 호출하지 않고 확인 후보를 유지한다. reviewedBy·reviewedAt은 서버가 기록한다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,403 NOT_MEMBER.

### R04. 확정 처방 조회

`GET /api/v1/care-groups/{groupId}/medications`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `encounterId` 선택, `onDate` 선택, `limit`, `cursor`.

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "name": "가상 A약",
        "doseText": "1회 1정",
        "frequencyText": "하루 1회",
        "startsOn": "2026-10-05",
        "endsOn": "2026-10-10",
        "instructions": null,
        "confirmedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "confirmedAt": "2026-10-02T18:00:00+09:00",
        "supersedesId": null
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

**처리·검증**

정렬은 startsOn DESC,id DESC다. onDate는 startsOn<=date<=endsOn으로 검사한다. 변경 처방은 supersedes 관계와 적용 시작일을 고려해 유효 약을 판단하고 완료 snapshot은 유지한다. encounter 필터의 실제 공동체도 검사한다. 약 범용 PATCH는 제공하지 않고 확인 흐름으로 변경한다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER.

### 6.1 후보 payload·충돌 해소 상세

신규 분석 schemaVersion=2를 사용한다. 기존 schemaVersion1 결과는 조회 호환 및 명시적 PICKUP→OTHER 매핑으로 유지한다. TASK 후보 kind는 EXAM/HOSPITAL/OTHER만, MEDICATION 후보는 itemType=MEDICATION 계약으로 확인한다.

TASK payload 필수값은 schemaVersion=2, kind, title, date(YYYY-MM-DD), time(HH:mm), durationMinutes, recurrence다. ONCE가 아니면 lastDate(nullable)·weekdays도 제공한다. description은 선택이다. 조회 후보에는 미상 날짜·시각을 null로 보존하지만 적용에는 명시값이 필요하다. durationSource는 PLANNING_DEFAULT 또는 USER_INPUT으로 계획 시간을 의료 사실과 구분한다. 복약30분·병원/검진120분은 기존 추천값이며 OTHER는 사용자가 입력한다.

MEDICATION payload는 R02 예시 구조다. schedulePlans는 명시적인 시각을 가진 반복 규칙 목록이며 중복 시각은 허용하지 않는다. 각 계획 기간은 처방의 유효 기간 안에 있어야 한다. instructions·supersedesMedicationId는 nullable이다. 변경 대상과 source 참조는 같은 공동체여야 한다. 중단 지시를 일정 삭제로 조용히 실행하지 않는다.

충돌이 있으면 conflictResolution을 제공한다.

```json
{
  "mode": "SOURCE",
  "selectedSourceId": "44444444-4444-4444-8444-444444444444"
}
```

또는 최종 payload를 직접 수정한 뒤 다음 정보를 제공한다.

```json
{
  "mode": "MANUAL",
  "note": "가족이 원문을 대조해 수정함"
}
```

SOURCE는 해당 후보 evidence에 속한 활성 소스만 선택할 수 있고 최종 payload가 선택한 자료의 해소값과 일치해야 한다. MANUAL은 최종 payload 명시가 필수다. 충돌이 없으면 null 또는 생략한다. 서버는 선택 기록을 후보 payload의 schemaVersion=2 확장 reviewResolution에 저장할 수 있으며 새 컬럼은 필요 없다. CONFLICT 사유만 지워 검증을 우회할 수 없다. 사용자 확인은 의료진 검증을 의미하지 않는다.

## 7. 일정·배정·인계·완료

### T01. 선택 날짜 건강 일정 목록

`GET /api/v1/care-groups/{groupId}/tasks`

ACTIVE 공동체,200. Query date=YYYY-MM-DD 필수, mineOnly=false 기본, limit/cursor. 날짜는 startsAt의 KST 날짜. 취소 제외, 완료 포함. mineOnly=true는 현재 본인 담당만 반환하며 무담당은 제외한다.

정렬 rank0=미완료 무담당·본인UNRESOLVED, rank1=미완료 무담당·본인RESOLVED, rank2=그 외; 각 rank에서 startsAt ASC,id ASC. 같은 날짜 상태 변경 시 클라이언트는 첫 페이지부터 재조회한다. cursor는 user/group/date/mineOnly/rank/startsAt/id를 검증한다. rank는 응답 필수 필드가 아닌 정렬 내부값이다.

응답 data={items:Task[],nextCursor,hasMore}. Task에는2.5절 개인 상태·버튼 capability 포함. 예시의 capability=false는 예시 요청자/가능시간 기준이며 클라이언트가 하드코딩할 값이 아니다. 거절은 본인 응답만 바꾸며 다른 사용자 Task.myUnassignedState에 반영하지 않는다. 조회량을 limit으로 잘라 월 카운트를 계산하지 않는다(T17 사용).

### T02. 일정 상세

`GET /api/v1/tasks/{occurrenceId}`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "55555555-5555-4555-8555-555555555555",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "seriesId": "66666666-6666-4666-8666-666666666666",
    "seriesVersion": 0,
    "revisionNo": 1,
    "anchorDate": "2026-10-05",
    "kind": "MEDICATION",
    "title": "아침 약 챙기기",
    "description": null,
    "startsAt": "2026-10-05T08:00:00+09:00",
    "endsAt": "2026-10-05T08:30:00+09:00",
    "dueAt": "2026-10-05T08:30:00+09:00",
    "executionStatus": "PENDING",
    "assignee": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    },
    "assignmentOrigin": "AUTO",
    "openHandoff": null,
    "isOverdue": false,
    "isOverride": false,
    "medications": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "name": "가상 A약",
        "doseText": "1회 1정",
        "frequencyText": "하루 1회",
        "startsOn": "2026-10-05",
        "endsOn": "2026-10-10",
        "instructions": null,
        "confirmedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "confirmedAt": "2026-10-02T18:00:00+09:00",
        "supersedesId": null
      }
    ],
    "completion": null,
    "cancellation": null,
    "sourceEncounterIds": [
      "33333333-3333-4333-8333-333333333333"
    ],
    "version": 0,
    "myUnassignedState": "NOT_APPLICABLE",
    "canAccept": false,
    "canDecline": false,
    "canRelease": false
  }
}
```

**처리·검증**

원래 반복 위치인 anchorDate와 실제 startsAt을 분리한다. dueAt은 endsAt의 별칭이며 isOverdue는 계산한다. 취소 건도 권한 검사 후 상세 조회할 수 있다. medications는 발생 건에 연결된 약 snapshot이다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### T03. 건강 일정 직접 단건 추가

`POST /api/v1/care-groups/{groupId}/task-series`

ACTIVE 공동체,201. 기존 경로를 유지하되 수동 생성 계약을 제한한다.
```json
{
  "kind": "EXAM",
  "title": "건강검진 동행",
  "description": null,
  "rule": {
    "recurrence": "ONCE",
    "firstDate": "2026-10-10",
    "lastDate": "2026-10-10",
    "weekdays": [],
    "localTime": "10:00",
    "durationMinutes": 120
  }
}
```

kind는 EXAM/HOSPITAL/OTHER만. MEDICATION은422 MANUAL_MEDICATION_NOT_ALLOWED. rule.recurrence=ONCE, lastDate=firstDate, weekdays=[] 필수; 반복이면422 MANUAL_REPEAT_NOT_ALLOWED. medicationIds는 입력 필드에서 제거하며 전달시400. 담당자 입력 없이 자동 배정하고 변경은 T09다. 직접 지정 날짜의 발생1건을 즉시 생성한다(14일 horizon 밖 날짜도 생성). 과거 시작이면422. creationOrigin=MANUAL, sourceItem=null.
응답 data={seriesId,seriesVersion,rule,occurrences:[Task]}이며 정확히1건. 배정 실패는 NO_CANDIDATE episode+개인미해결로 생성하고 즉시 인계 푸시는 만들지 않는다. 중복 클릭은 멱등 키로 막는다. 약 복용 생성은R02만 사용한다.

### T04. 시리즈 규칙 상세

`GET /api/v1/task-series/{seriesId}`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "66666666-6666-4666-8666-666666666666",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "kind": "MEDICATION",
    "title": "아침 약 챙기기",
    "description": null,
    "rule": {
      "recurrence": "DAILY",
      "firstDate": "2026-10-05",
      "lastDate": "2026-10-10",
      "weekdays": [],
      "localTime": "08:00",
      "durationMinutes": 30
    },
    "medications": [
      {
        "id": "77777777-7777-4777-8777-777777777777",
        "name": "가상 A약",
        "doseText": "1회 1정",
        "frequencyText": "하루 1회",
        "startsOn": "2026-10-05",
        "endsOn": "2026-10-10",
        "instructions": null,
        "confirmedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "confirmedAt": "2026-10-02T18:00:00+09:00",
        "supersedesId": null
      }
    ],
    "stopFromDate": null,
    "currentRevisionNo": 1,
    "version": 0
  }
}
```

**처리·검증**

현재 규칙과 약 목록을 반환한다. version은 task_series.version이며 currentRevisionNo와 다르다. stopFromDate 이후 반복 재개 기능은 제공하지 않는다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### T05. 반복 일괄 수정 미리보기

`POST /api/v1/tasks/{occurrenceId}/series-edit-preview`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "expectedSeriesVersion": 0,
  "patch": {
    "title": "아침 약 챙기기",
    "localTime": "09:00",
    "durationMinutes": 30
  }
}
```

**응답**

```json
{
  "data": {
    "previewToken": "<signed-preview>",
    "expiresAt": "2026-10-02T18:05:00+09:00",
    "seriesId": "66666666-6666-4666-8666-666666666666",
    "seriesVersion": 0,
    "cutoff": "2026-10-02T18:00:00+09:00",
    "counts": {
      "changed": 6,
      "canceled": 0,
      "added": 0,
      "overwrittenOverrides": 1
    },
    "affectedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "affectsFutureUnmaterialized": true,
    "nextRule": {
      "recurrence": "DAILY",
      "firstDate": "2026-10-05",
      "lastDate": "2026-10-10",
      "weekdays": [],
      "localTime": "09:00",
      "durationMinutes": 30
    },
    "assignmentReleaseCount": 0
  }
}
```

**처리·검증**

SERIES_ALL_PENDING의 미리보기다. 같은 series에서 현재 이후에 시작하는 PENDING 발생 건과 미생성 미래 반복분에 적용하며 미래 예외도 덮어쓴다. patch는 title·description·localTime·durationMinutes·recurrence·firstDate·lastDate·weekdays 중 최소 1개다. 담당자 또는 약명·용량을 변경할 수 없다. 현재 생성 범위와 그 밖에 이동한 미래 예외도 영향에 포함한다. 완료·취소·이미 시작한 업무는 보존한다. 담당 해제 여부와 이후 반복 규칙도 안내한다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,400 VALIDATION_ERROR,403 NOT_MEMBER.

### T06. 일정 수정

`PATCH /api/v1/tasks/{occurrenceId}`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "scope": "SERIES_ALL_PENDING",
  "expectedVersion": 0,
  "expectedSeriesVersion": 0,
  "previewToken": "<signed-preview>",
  "patch": {
    "title": "아침 약 챙기기",
    "localTime": "09:00",
    "durationMinutes": 30
  }
}
```

**응답**

```json
{
  "data": {
    "seriesId": "66666666-6666-4666-8666-666666666666",
    "seriesVersion": 1,
    "updatedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "canceledOccurrenceIds": [],
    "createdOccurrenceIds": [],
    "releasedOccurrenceIds": []
  }
}
```

**처리·검증**

scope는 OCCURRENCE 또는 SERIES_ALL_PENDING이다. 단건은 expectedVersion과 patch가 필수이고 expectedSeriesVersion·previewToken은 받지 않는다. 단건 patch는 title·description·startsAt·endsAt이며 시각은 한 쌍으로 제공한다. 생략은 유지, description=null은 지움이다. 단건 응답은 {occurrence:Task}다. 기간은 1~1440분이며 완료·취소 건은 수정할 수 없다. 경과한 PENDING은 미래로 이동할 수 있지만 anchorDate는 유지한다. 일괄은 T05와 같은 patch, 두 버전, previewToken이 필수다. 새 시작이 커밋 시 현재 이전이면 409다. 기존 담당자가 가능하면 유지하고 불가능하면 해제·AVAILABILITY 인계를 생성한다. 임의 재배정은 하지 않는다. 새 규칙·알림·이력을 원자 반영한다. RULE_CHANGED 취소는 명시적 규칙 수정으로만 다시 포함할 수 있으며 USER_ONE·USER_FUTURE는 복구하지 않는다. stopFromDate 이후 반복 재개는 금지한다.

**주요 오류**

409 VERSION_CONFLICT/PREVIEW_STALE/INVALID_STATE/TIME_CONFLICT,400 VALIDATION_ERROR,403 NOT_MEMBER.

T06 추가 제한: MANUAL 출처 ONCE를 DAILY/WEEKLY 또는 MEDICATION으로 변경하여 직접 추가 제한을 우회할 수 없다. kind는 T06 patch 허용 필드가 아니다. 기존 AI/REVIEW/LEGACY 반복 수정 범위는 유지한다. 담당자 변경은 T09로 분리하며 수정 화면에서 둘 다 변경하면 순서대로 최신 version을 사용한다. 전체 원자 수정으로 오해하지 않는다.

### T07. 일정 삭제 미리보기

`POST /api/v1/tasks/{occurrenceId}/deletion-preview`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "scope": "SERIES_FROM_SELECTED",
  "expectedVersion": 0,
  "expectedSeriesVersion": 0
}
```

**응답**

```json
{
  "data": {
    "previewToken": "<signed-preview>",
    "expiresAt": "2026-10-02T18:05:00+09:00",
    "scope": "SERIES_FROM_SELECTED",
    "anchorDate": "2026-10-05",
    "canceledOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "preservedCompletedCount": 0,
    "movedOverrideCount": 0,
    "affectsFutureUnmaterialized": true
  }
}
```

**처리·검증**

scope는 OCCURRENCE 또는 SERIES_FROM_SELECTED다. 단건에는 expectedSeriesVersion이 필요 없고 일괄에는 필수다. 앞으로 삭제의 경계는 실제 startsAt이 아니라 선택 건의 anchorDate다. 같은 시리즈의 경계 이후 미완료 건과 이동 예외를 포함하고 완료 건은 보존한다. 단발 업무는 OCCURRENCE만 허용한다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,400 VALIDATION_ERROR,403 NOT_MEMBER.

### T08. 일정 삭제(취소)

`DELETE /api/v1/tasks/{occurrenceId}`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "scope": "SERIES_FROM_SELECTED",
  "expectedVersion": 0,
  "expectedSeriesVersion": 0,
  "previewToken": "<signed-preview>"
}
```

**응답**

```json
{
  "data": {
    "canceledOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "stopFromDate": "2026-10-05",
    "seriesVersion": 1
  }
}
```

**처리·검증**

T07의 previewToken이 단건에도 필수다. USER_ONE·USER_FUTURE 취소 이력을 보존한다. 앞으로 삭제에서는 기존 stopFromDate가 더 이르면 유지한다. 선택 완료 건의 단건 삭제는 거부하며 일괄 범위의 완료 건은 보존한다. 열린 인계·예약 알림·대기 delivery를 원자적으로 종료한다. 취소 건은 점수·충돌에서 제외하고 원처방은 유지한다. 약 복용 중단 명령으로 표시하지 않는다.

**주요 오류**

409 VERSION_CONFLICT/PREVIEW_STALE/INVALID_STATE,403 NOT_MEMBER.

### T09. 수동 담당자 지정·변경

`PUT /api/v1/tasks/{occurrenceId}/assignment`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "assigneeUserId": "22222222-2222-4222-8222-222222222222"
}
```

**응답**

```json
{
  "data": {
    "occurrence": {
      "id": "55555555-5555-4555-8555-555555555555",
      "groupId": "11111111-1111-4111-8111-111111111111",
      "seriesId": "66666666-6666-4666-8666-666666666666",
      "seriesVersion": 0,
      "revisionNo": 1,
      "anchorDate": "2026-10-05",
      "kind": "MEDICATION",
      "title": "아침 약 챙기기",
      "description": null,
      "startsAt": "2026-10-05T08:00:00+09:00",
      "endsAt": "2026-10-05T08:30:00+09:00",
      "dueAt": "2026-10-05T08:30:00+09:00",
      "executionStatus": "PENDING",
      "assignee": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "assignmentOrigin": "MANUAL",
      "openHandoff": null,
      "isOverdue": false,
      "isOverride": false,
      "medications": [
        {
          "id": "77777777-7777-4777-8777-777777777777",
          "name": "가상 A약",
          "doseText": "1회 1정",
          "frequencyText": "하루 1회",
          "startsOn": "2026-10-05",
          "endsOn": "2026-10-10",
          "instructions": null,
          "confirmedBy": {
            "id": "22222222-2222-4222-8222-222222222222",
            "displayName": "자녀 1"
          },
          "confirmedAt": "2026-10-02T18:00:00+09:00",
          "supersedesId": null
        }
      ],
      "completion": null,
      "cancellation": null,
      "sourceEncounterIds": [
        "33333333-3333-4333-8333-333333333333"
      ],
      "version": 1,
      "myUnassignedState": "NOT_APPLICABLE",
      "canAccept": false,
      "canDecline": false,
      "canRelease": false
    }
  }
}
```

**처리·검증**

PENDING이고 기한이 지나지 않아야 한다. 담당자는 같은 공동체의 ACTIVE 멤버이며 전체 가능 시간과 사용자 전체 미취소 일정 충돌을 검사한다. RECIPIENT의 자동 배정 제외를 수동 배정·수행 기록의 금지로 확대하지 않는다. 수동 배정은 동일한 가능 시간·충돌 검증을 적용한다. 모든 ACTIVE 구성원이 담당자를 변경할 수 있다. null 해제는 받지 않고 T10을 사용한다. OPEN 인계가 있다면 ACCEPTED로 닫고 acceptedBy는 새 담당자, assignmentOrigin은 MANUAL로 기록한다. 버튼 처리자는 audit에 남긴다. 미해결 카드 수락은 T11을 사용한다. 이미 거절한 본인의 담당 지정은 수정 화면에서 T09를 사용하며 허용한다. 타 구성원의 거절도 수동 지정을 막지 않는다. 체크는 한 명만 가능하고 교체 시 기존 체크를 해제한다. 현재 episode의 예약·대기 delivery를 종료한다.

**주요 오류**

409 VERSION_CONFLICT/NOT_AVAILABLE/TIME_CONFLICT/TASK_OVERDUE/INVALID_STATE,403 NOT_MEMBER.

### T10. 현재 담당자 인계(담당 내려놓기)

`POST /api/v1/tasks/{occurrenceId}/handoffs`

ACTIVE 공동체+현재 담당자 본인만,201. 요청 `{expectedVersion}`. 다른 사람이 대신 해제하면403 NOT_ASSIGNEE. PENDING·담당자존재·endsAt>now 검사. 담당 해제+USER_REQUEST 새 episode+이전 담당자 기록+즉시 인계 outbox+audit+멱등 성공 원자 처리.
응답 data={occurrence:Task,handoff:Handoff}. 새 episode에는 거절 응답이 없어 이전 담당자를 포함한 모든 현재 ACTIVE 구성원에게 UNRESOLVED. 이미무담당409 INVALID_STATE. 경과409 TASK_OVERDUE. 일정 자체 삭제와 구분한다.

### T11. 미해결 미지정 수락

`POST /api/v1/handoffs/{handoffId}/accept`

ACTIVE 공동체,200. 요청 `{expectedVersion,expectedTaskVersion}`. 앞 version은 Handoff.version이다. occurrenceId는 해당 episode에서 읽는다. 본인 userId를 요청으로 받지 않는다.
잠금 후 현재 OPEN·PENDING·무담당·endsAt>now·본인 거절없음·전체 가능 시간·사용자 전역 충돌을 검사한다. 최초 NO_CANDIDATE도 수락 가능하다. 본인에게 HANDOFF 담당 지정+episode ACCEPTED+모든 해당 미지정 대기알림 종료를 원자 처리한다.
응답 data={occurrence:Task,handoff:Handoff}. 본인이 이미 거절했다면409 RESPONSE_ALREADY_DECLINED, 수정 화면 T09 안내. 경쟁에서 이미 배정이면409 ALREADY_ASSIGNED. 오래된 episode/버전409, 부모의 명시적 수락은 허용한다. canAccept는 힌트이며 저장 검사를 생략하지 않는다.

### T12. 완료·대리 완료

`POST /api/v1/tasks/{occurrenceId}/complete`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "performedByUserId": "22222222-2222-4222-8222-222222222222"
}
```

**응답**

```json
{
  "data": {
    "occurrence": {
      "id": "55555555-5555-4555-8555-555555555555",
      "groupId": "11111111-1111-4111-8111-111111111111",
      "seriesId": "66666666-6666-4666-8666-666666666666",
      "seriesVersion": 0,
      "revisionNo": 1,
      "anchorDate": "2026-10-05",
      "kind": "MEDICATION",
      "title": "아침 약 챙기기",
      "description": null,
      "startsAt": "2026-10-05T08:00:00+09:00",
      "endsAt": "2026-10-05T08:30:00+09:00",
      "dueAt": "2026-10-05T08:30:00+09:00",
      "executionStatus": "COMPLETED",
      "assignee": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "assignmentOrigin": "AUTO",
      "openHandoff": null,
      "isOverdue": false,
      "isOverride": false,
      "medications": [
        {
          "id": "77777777-7777-4777-8777-777777777777",
          "name": "가상 A약",
          "doseText": "1회 1정",
          "frequencyText": "하루 1회",
          "startsOn": "2026-10-05",
          "endsOn": "2026-10-10",
          "instructions": null,
          "confirmedBy": {
            "id": "22222222-2222-4222-8222-222222222222",
            "displayName": "자녀 1"
          },
          "confirmedAt": "2026-10-02T18:00:00+09:00",
          "supersedesId": null
        }
      ],
      "completion": {
        "performedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "completedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "completedAt": "2026-10-02T18:00:00+09:00"
      },
      "cancellation": null,
      "sourceEncounterIds": [
        "33333333-3333-4333-8333-333333333333"
      ],
      "version": 1,
      "myUnassignedState": "NOT_APPLICABLE",
      "canAccept": false,
      "canDecline": false,
      "canRelease": false
    }
  }
}
```

**처리·검증**

PENDING만 완료할 수 있으며 담당자가 없거나 기한이 지나도 허용한다. performedByUserId는 같은 공동체의 ACTIVE 멤버이고 RECIPIENT도 가능하다. completedBy·completedAt은 서버가 기록한다. 실제 약 섭취의 센서 검증을 의미하지 않는다. OPEN 인계와 예약 알림을 종료한다. 담당자는 유지하고 점수는 assignee 기준이며 미취소 예약 구간의 충돌도 유지한다. 같은 멱등 키는 최초 성공을 반환하고 다른 키로 이미 완료한 업무를 처리하면 409다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,403 NOT_MEMBER/FORBIDDEN.

### T13. 완료 재열기

`POST /api/v1/tasks/{occurrenceId}/reopen`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 1
}
```

**응답**

```json
{
  "data": {
    "occurrence": {
      "id": "55555555-5555-4555-8555-555555555555",
      "groupId": "11111111-1111-4111-8111-111111111111",
      "seriesId": "66666666-6666-4666-8666-666666666666",
      "seriesVersion": 0,
      "revisionNo": 1,
      "anchorDate": "2026-10-05",
      "kind": "MEDICATION",
      "title": "아침 약 챙기기",
      "description": null,
      "startsAt": "2026-10-05T08:00:00+09:00",
      "endsAt": "2026-10-05T08:30:00+09:00",
      "dueAt": "2026-10-05T08:30:00+09:00",
      "executionStatus": "PENDING",
      "assignee": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "assignmentOrigin": "AUTO",
      "openHandoff": null,
      "isOverdue": false,
      "isOverride": false,
      "medications": [
        {
          "id": "77777777-7777-4777-8777-777777777777",
          "name": "가상 A약",
          "doseText": "1회 1정",
          "frequencyText": "하루 1회",
          "startsOn": "2026-10-05",
          "endsOn": "2026-10-10",
          "instructions": null,
          "confirmedBy": {
            "id": "22222222-2222-4222-8222-222222222222",
            "displayName": "자녀 1"
          },
          "confirmedAt": "2026-10-02T18:00:00+09:00",
          "supersedesId": null
        }
      ],
      "completion": null,
      "cancellation": null,
      "sourceEncounterIds": [
        "33333333-3333-4333-8333-333333333333"
      ],
      "version": 2,
      "myUnassignedState": "NOT_APPLICABLE",
      "canAccept": false,
      "canDecline": false,
      "canRelease": false
    }
  }
}
```

**처리·검증**

COMPLETED만 재열 수 있다. 완료 snapshot을 audit에 남기고 완료 필드를 지운다. 기존 담당자의 ACTIVE·가능 시간·충돌을 재검사하며 자기 업무는 충돌 검사에서 제외한다. 부적합하면 해제하고 미경과 업무에는 새 인계를 생성한다. 경과 업무는 PENDING·isOverdue=true로 남기며 반복 푸시는 하지 않는다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,403 NOT_MEMBER.

### T14. 미지정 사건 목록(보조 조회)

`GET /api/v1/care-groups/{groupId}/handoffs`

ACTIVE 공동체,200. Query status=OPEN 기본, limit/cursor. createdAt DESC,id DESC. data.items=[{handoff:Handoff,occurrence:Task}], nextCursor/hasMore. 개인 거절 여부는 Task.myUnassignedState로 제공한다. 메인 날짜 목록은 T01이며 별도 상단 전역 인계 영역을 필수로 만들지 않는다. NO_CANDIDATE도 내부 사건 목록에 포함된다.

### T15. 일정 변경 이력

`GET /api/v1/tasks/{occurrenceId}/history`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `limit`, `cursor`.

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        "eventType": "TASK_COMPLETED",
        "actor": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "createdAt": "2026-10-02T18:00:00+09:00",
        "changes": [
          {
            "field": "executionStatus",
            "before": "PENDING",
            "after": "COMPLETED"
          }
        ]
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

**처리·검증**

audit_event의 raw JSON 대신 최소 이력 DTO를 반환한다. createdAt DESC,id DESC로 정렬한다. 민감 의료 문장·다른 공동체 상세는 노출하지 않으며 수행·배정·일정 변경을 중심으로 표시한다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### T16. 본인의 미지정 거절

`POST /api/v1/handoffs/{handoffId}/decline`

ACTIVE 공동체,200. 요청 `{expectedVersion,expectedTaskVersion}`. 현재 OPEN·PENDING·무담당·endsAt>now 확인 후 본인의 DECLINED 응답만 저장한다. 일정 담당/실행 상태·다른 사람 응답·공통 episode version은 변경하지 않는다. 응답 data={occurrence:Task,handoffId,response:"DECLINED",respondedAt}. Task.myUnassignedState=RESOLVED, canAccept/canDecline=false.
동일 키 replay는 공통 계약. 다른 키로 같은 현재 사건을 반복 거절해도 최초 respondedAt 유지하며200. 이미 다른 사람에게 배정/새 episode라면409. 본인의 아직 미발송 인계/digest 전송은 제외하되 다른 사람 알림은 유지한다. 사용자에게 ‘일정 완료’로 표시하지 않는다.

### T17. 메인 월 캘린더 요약

`GET /api/v1/care-groups/{groupId}/calendar`

ACTIVE 공동체,200. Query month=YYYY-MM 필수. 해당 KST 월 모든 날짜를 반환한다(빈 날짜도 포함). 전체 공동체 기준이며 mineOnly를 받지 않는다. startsAt 날짜 기준이고 취소 제외. PENDING 무담당이면 거절·경과와 무관하게 unassignedPendingCount에 포함한다.
```json
{
  "data": {
    "month": "2026-10",
    "days": [
      {
        "date": "2026-10-01",
        "totalCount": 0,
        "completedCount": 0,
        "unassignedPendingCount": 0,
        "indicator": "NONE"
      },
      {
        "date": "2026-10-02",
        "totalCount": 2,
        "completedCount": 1,
        "unassignedPendingCount": 1,
        "indicator": "ATTENTION"
      }
    ],
    "timezone": "Asia/Seoul"
  }
}
```
예시는 앞2일만 발췌했으며 실제 응답은 월 전체다. indicator는NONE(0건)/ATTENTION(미완료무담당>0)/NORMAL이다. 개인 거절로 날짜 경고를 지우지 않는다. 개인 가능 시간 페이지는 이 API를 호출하지 않는다.

### 7.1 배정·반복 구현 공통

신규 생성·후보 적용·매일 발생 생성에서 서버가 결정적 배정을 수행한다. ACTIVE CAREGIVER 중 전체 구간에 가능하고 모든 공동체의 미취소 담당 업무와 충돌하지 않는 후보를 고른다. priority 최소 → 같은 공동체 해당 KST 주 월~일의 미취소 담당 건수 최소(완료 포함) → 4시간 묶음 가능 → member.id 순으로 결정한다. 돌봄 대상은 배정 후보에서 제외하고 미등록 가능 시간은 불가로 처리한다.

묶음은 같은 대상·날짜의 신규 업무에서 첫 시작부터 마지막 시작까지 240분 이내인 경우다. 중간 시간 전체의 가능 여부와 충돌을 검사한다. 각 업무의 최상위 순위·최소 건수 후보 집합에 교집합이 있을 때 한 사람에게 함께 배정한다. 업무 자체는 합치지 않으며 실제 건수만큼 점수에 반영한다.

신규 배정 실패는 NO_CANDIDATE 미지정 episode를 만들되 즉시 인계 알림은 생성하지 않고, 새 업무 때문에 기존 담당자를 임의로 재배정하지 않는다. 가능 시간 축소는 미래의 부적합 담당만 해제한다. 순위 변경은 이후 신규 배정에만 영향을 준다.

모든 일정 관련 명령은 schedule_guard 잠금 → 최신 조회 → 권한·버전·가능 시간·충돌 검사 → 변경·audit·outbox의 동일 커밋 순서를 따른다. 파일 전송·AI·푸시 외부 호출은 잠금 밖에서 수행한다. 범용 담당자 PATCH 또는 프론트의 배정 계산 결과를 그대로 저장하지 않는다.

### 7.2 이번만 수정 예시

T06 요청:

```json
{
  "scope": "OCCURRENCE",
  "expectedVersion": 0,
  "patch": {
    "startsAt": "2026-10-06T09:00:00+09:00",
    "endsAt": "2026-10-06T09:30:00+09:00"
  }
}
```

응답은 `data.occurrence`에 갱신한 Task 전체를 반환한다. 기존 anchorDate가 2026-10-05이면 그대로 유지하고 실제 startsAt만 이동한다. 가능하면 담당자를 유지하고 부적합하면 assignee=null·assignmentOrigin=null·새 openHandoff를 반환한다. 다른 날짜의 규칙은 바뀌지 않는다.

## 8. 개인 가능 시간

### V01. 활동시간·근무 설정 조회

`GET /api/v1/me/availability-config`

- 권한: 본인
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "activeStartMinute": 420,
    "activeEndMinute": 1320,
    "userVersion": 0,
    "workConfigVersion": "<sha256-config-fingerprint>",
    "weeklyWorkPeriods": [
      {
        "isoWeekday": 1,
        "startMinute": 540,
        "endMinute": 1080
      }
    ]
  }
}
```

**처리·검증**

workConfigVersion은 정렬한 weekly_work_period 값으로 계산한 fingerprint이며 DB 새 컬럼이 아니다. userVersion은 app_user.version이다. 근무·가능 구간은 0~1440분으로 표현한다. 개인 근무 내용을 공동체 구성원 목록에 반환하지 않는다.

**주요 오류**

공통401.

### V02. 활동·근무 변경 미리보기

`POST /api/v1/me/availability-config/preview`

- 권한: 본인
- 성공: `200`

**요청**

```json
{
  "expectedUserVersion": 0,
  "expectedWorkConfigVersion": "<sha256-config-fingerprint>",
  "patch": {
    "activeStartMinute": 420,
    "activeEndMinute": 1320,
    "weeklyWorkPeriods": [
      {
        "isoWeekday": 1,
        "startMinute": 540,
        "endMinute": 1080
      }
    ]
  }
}
```

**응답**

```json
{
  "data": {
    "previewToken": "<signed-preview>",
    "expiresAt": "2026-10-02T18:05:00+09:00",
    "recalculatedDateCount": 5,
    "clippedCustomDates": [],
    "releasedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "retainedPastOccurrenceCount": 0
  }
}
```

**처리·검증**

patch는 최소 한 필드를 변경한다. weeklyWorkPeriods를 제공하면 전체 대체하고 []면 모두 삭제한다. 활동 시간은 같은 날 start<end, 근무 구간은 ISO 요일 1~7과 minute 범위를 검사한다. 자정 넘는 근무는 두 요일로 분할하며 겹침·인접 구간은 병합한다. 미래 자동 계산 PARTIAL을 다시 계산하고 활동 범위 축소로 직접 편집 구간이 잘리면 날짜·최종 구간을 미리 보여준다. 미등록 날짜를 자동 등록하지 않는다. 현재 ACTIVE 공동체는 하나다. 남아 있는 과거 공동체 배정까지 충돌 검사하되 응답에는 접근 가능한 본인 담당 일정 ID만 제공한다.

**주요 오류**

400 VALIDATION_ERROR,409 VERSION_CONFLICT.

### V03. 활동·근무 변경 저장

`PATCH /api/v1/me/availability-config`

- 권한: 본인
- 성공: `200`

**요청**

```json
{
  "expectedUserVersion": 0,
  "expectedWorkConfigVersion": "<sha256-config-fingerprint>",
  "previewToken": "<signed-preview>",
  "patch": {
    "activeStartMinute": 420,
    "activeEndMinute": 1320,
    "weeklyWorkPeriods": [
      {
        "isoWeekday": 1,
        "startMinute": 540,
        "endMinute": 1080
      }
    ]
  }
}
```

**응답**

```json
{
  "data": {
    "activeStartMinute": 420,
    "activeEndMinute": 1320,
    "userVersion": 1,
    "workConfigVersion": "<sha256-config-fingerprint>",
    "weeklyWorkPeriods": [
      {
        "isoWeekday": 1,
        "startMinute": 540,
        "endMinute": 1080
      }
    ],
    "releasedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ]
  }
}
```

**처리·검증**

V02와 동일한 patch와 token을 사용한다. guard·사용자 행 잠금과 workConfigVersion 검사를 수행한다. 미래 자동 계산 구간과 미리보기에서 승인한 직접 구간의 잘림을 반영한다. 새 구간에 포함되지 않는 미래 담당 업무만 해제하고 AVAILABILITY 인계를 생성한다. 다른 가족에게 자동 재배정하지 않는다. 집계 설정의 동시성을 위해 app_user.version을 증가시키며 근무 테이블에 새 컬럼을 추가하지 않는다.

**주요 오류**

409 VERSION_CONFLICT/PREVIEW_STALE,400 VALIDATION_ERROR.

### V04. 내 날짜별 가능 시간

`GET /api/v1/me/availability-days`

- 권한: 본인
- 성공: `200`

**요청**

Query: `fromDate`, `toDateExclusive` 필수.

**응답**

```json
{
  "data": {
    "items": [
      {
        "date": "2026-10-05",
        "mode": "PARTIAL",
        "customIntervals": false,
        "intervals": [
          {
            "startMinute": 420,
            "endMinute": 540
          },
          {
            "startMinute": 1080,
            "endMinute": 1320
          }
        ],
        "version": 0
      },
      {
        "date": "2026-10-06",
        "mode": null,
        "customIntervals": false,
        "intervals": [],
        "version": null
      }
    ]
  }
}
```

**처리·검증**

요청 범위의 각 날짜를 반환한다. 행이 없으면 mode·version=null이며 DB에 UNREGISTERED enum을 추가하지 않는다. FULL은 활동 시간 전체로 근무를 무시하고 UNAVAILABLE은 구간 0개다. PARTIAL·customIntervals=false는 활동-근무, true는 직접 입력한 최종 가능 구간이다. 다른 사용자의 전체 개인 캘린더를 조회하는 경로는 제공하지 않는다.

**주요 오류**

400 VALIDATION_ERROR.

### V05. 날짜 범위 가능 시간 미리보기

`POST /api/v1/me/availability-days/preview`

- 권한: 본인
- 성공: `200`

**요청**

```json
{
  "fromDate": "2026-10-05",
  "toDateExclusive": "2026-10-07",
  "mode": "PARTIAL",
  "customIntervals": true,
  "intervals": [
    {
      "startMinute": 1080,
      "endMinute": 1320
    }
  ],
  "expectedDays": [
    {
      "date": "2026-10-05",
      "version": 0
    },
    {
      "date": "2026-10-06",
      "version": null
    }
  ]
}
```

**응답**

```json
{
  "data": {
    "previewToken": "<signed-preview>",
    "expiresAt": "2026-10-02T18:05:00+09:00",
    "recalculatedDateCount": 5,
    "clippedCustomDates": [],
    "releasedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ],
    "retainedPastOccurrenceCount": 0,
    "days": [
      {
        "date": "2026-10-05",
        "mode": "PARTIAL",
        "customIntervals": true,
        "intervals": [
          {
            "startMinute": 1080,
            "endMinute": 1320
          }
        ],
        "version": 0
      },
      {
        "date": "2026-10-06",
        "mode": "PARTIAL",
        "customIntervals": true,
        "intervals": [
          {
            "startMinute": 1080,
            "endMinute": 1320
          }
        ],
        "version": null
      }
    ]
  }
}
```

**처리·검증**

expectedDays는 범위의 모든 날짜를 정확히 한 번 포함하고 미등록 날짜 version은 null이다. FULL·UNAVAILABLE은 customIntervals=false, intervals는 생략 또는 []다. PARTIAL·false는 근무를 제외해 계산한다. PARTIAL·true는 최종 intervals가 필수이며 []도 가능하다. 활동 범위 안의 start<end를 검사하고 정렬·병합한다. 같은 입력 패턴을 범위의 각 날짜에 적용하며 최종 구간과 담당 해제 영향을 보여준다.

**주요 오류**

400 VALIDATION_ERROR,409 VERSION_CONFLICT.

### V06. 날짜 범위 가능 시간 저장

`PUT /api/v1/me/availability-days`

- 권한: 본인
- 성공: `200`

**요청**

```json
{
  "fromDate": "2026-10-05",
  "toDateExclusive": "2026-10-07",
  "mode": "PARTIAL",
  "customIntervals": true,
  "intervals": [
    {
      "startMinute": 1080,
      "endMinute": 1320
    }
  ],
  "expectedDays": [
    {
      "date": "2026-10-05",
      "version": 0
    },
    {
      "date": "2026-10-06",
      "version": null
    }
  ],
  "previewToken": "<signed-preview>"
}
```

**응답**

```json
{
  "data": {
    "items": [
      {
        "date": "2026-10-05",
        "mode": "PARTIAL",
        "customIntervals": true,
        "intervals": [
          {
            "startMinute": 1080,
            "endMinute": 1320
          }
        ],
        "version": 1
      },
      {
        "date": "2026-10-06",
        "mode": "PARTIAL",
        "customIntervals": true,
        "intervals": [
          {
            "startMinute": 1080,
            "endMinute": 1320
          }
        ],
        "version": 0
      }
    ],
    "releasedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ]
  }
}
```

**처리·검증**

V05의 동일 요청과 token을 사용한다. 날짜 행과 최종 구간을 원자적으로 저장한다. null version으로 요청한 날짜가 이미 등록되었으면 409다. 기존 행은 version을 증가시키고 신규 행은 0이다. 미래의 부적합 배정만 해제하고 지난 업무·진행 중 업무는 보존하며 미리보기에서 안내한다. 날짜별 근무 예외는 직접 가능 구간으로 표현한다.

**주요 오류**

409 VERSION_CONFLICT/PREVIEW_STALE,400 VALIDATION_ERROR.

## 9. 알림·Web Push

### N01. 알림함

`GET /api/v1/me/notifications`

- 권한: 본인 + 각 공동체 ACTIVE
- 성공: `200`

**요청**

Query: `groupId` 선택, `unreadOnly` 기본false, `limit`, `cursor`.

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        "groupId": "11111111-1111-4111-8111-111111111111",
        "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "eventType": "TASK_ASSIGNED",
        "title": "가족 돌봄 일정이 있습니다",
        "body": "가족 일정에서 내용을 확인해주세요.",
        "readAt": null,
        "createdAt": "2026-10-02T18:00:00+09:00",
        "target": {
          "type": "TASK",
          "id": "55555555-5555-4555-8555-555555555555",
          "groupId": "11111111-1111-4111-8111-111111111111"
        }
      }
    ],
    "nextCursor": null,
    "hasMore": false,
    "unreadCount": 1
  }
}
```

**처리·검증**

notification.user_id가 본인이고 해당 공동체의 ACTIVE 멤버인 항목만 반환한다. 탈퇴 공동체 알림은 제외한다. groupId를 지정하면 멤버십을 검사한다. unreadCount는 선택한 공동체 필터 안의 전체 미읽음 수다. target이 삭제되면 null로 반환하며 클릭 시 대상 API에서 다시 인가한다.

**주요 오류**

403 NOT_MEMBER,400 VALIDATION_ERROR/INVALID_CURSOR.

### N02. 알림 읽음

`POST /api/v1/me/notifications/{notificationId}/read`

- 권한: 본인 + 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "groupId": "11111111-1111-4111-8111-111111111111",
    "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "eventType": "TASK_ASSIGNED",
    "title": "가족 돌봄 일정이 있습니다",
    "body": "가족 일정에서 내용을 확인해주세요.",
    "readAt": "2026-10-02T18:00:00+09:00",
    "createdAt": "2026-10-02T18:00:00+09:00",
    "target": {
      "type": "TASK",
      "id": "55555555-5555-4555-8555-555555555555",
      "groupId": "11111111-1111-4111-8111-111111111111"
    }
  }
}
```

**처리·검증**

최초 readAt을 서버 시각으로 기록하고 반복 호출은 기존 시각을 유지한다. version 컬럼이 없으므로 expectedVersion은 받지 않는다. 푸시 전달 성공과 사용자의 읽음은 별개다.

**주요 오류**

403 FORBIDDEN/NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### N03. 기존 반복 알림 설정 조회 — 폐기

`GET /api/v1/me/notification-preferences`

전환 후410 ENDPOINT_RETIRED. 반복 알림은 매일09시 고정이고 개인 거절 여부로 대상 결정. notification_preference의 이전 값은 무시한다.

### N04. 기존 반복 알림 설정 변경 — 폐기

`PUT /api/v1/me/notification-preferences`

전환 후410 ENDPOINT_RETIRED. UI에 DAILY/ONCE 선택을 제공하지 않는다. 브라우저 알림 권한·구독 해제는N05~N08로 유지한다.

### N05. Web Push 공개 설정

`GET /api/v1/push/config`

- 권한: 인증
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "enabled": true,
    "applicationServerKey": "<base64url-VAPID-public-key>"
  }
}
```

**처리·검증**

공개 VAPID 키만 반환하고 private key·외부 API key는 노출하지 않는다. Vercel 프론트 Origin의 Service Worker·PushManager에서 사용한다.

**주요 오류**

공통401.

### N06. 기기 구독 등록·갱신

`POST /api/v1/me/push-subscriptions`

- 권한: 본인
- 성공: `201`

**요청**

```json
{
  "endpoint": "https://<push-service>/<opaque-endpoint>",
  "keys": {
    "p256dh": "<base64url-key>",
    "auth": "<base64url-secret>"
  }
}
```

**응답**

```json
{
  "data": {
    "id": "88888888-8888-4888-8888-888888888888",
    "enabled": true
  }
}
```

**처리·검증**

브라우저 PushSubscription.toJSON을 기준으로 한다. 서버는 키 디코딩·길이·HTTPS endpoint를 검사한다. 외부 요청 대상이므로 사설 IP·localhost·metadata 대상으로의 요청을 거부하고 지원 푸시 공급자 host·redirect 정책을 검증한다. 본인의 동일 활성 endpoint는 갱신하고 200이다. 다른 사용자의 활성 행은 비활성화하고 대기 delivery를 취소한 후 새 행을 생성하여 이전 FK 이력을 유지한다. 원문 비밀 키를 로그에 남기지 않는다.

**주요 오류**

400 VALIDATION_ERROR,409 INVALID_STATE.

### N07. 내 활성 기기 구독 목록

`GET /api/v1/me/push-subscriptions`

- 권한: 본인
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "88888888-8888-4888-8888-888888888888",
        "enabled": true,
        "createdAt": "2026-10-02T18:00:00+09:00",
        "lastSeenAt": "2026-10-02T18:00:00+09:00"
      }
    ]
  }
}
```

**처리·검증**

본인의 활성 구독에 대한 최소 메타만 반환한다. endpoint·비밀 키는 반환하지 않는다. 등록 시 받은 id로 현재 브라우저 구독을 해제한다.

**주요 오류**

공통401.

### N08. 기기 구독 해제

`DELETE /api/v1/me/push-subscriptions/{subscriptionId}`

- 권한: 본인
- 성공: `204` / 본문 없음

**요청**

본문 없음

**응답**

없음.

**처리·검증**

물리 삭제 대신 enabled=false로 바꾸고 대기 delivery를 취소한다. 이미 비활성이면 204다. 다른 사용자 구독은 403이다. 로그아웃·계정 전환 시 토큰 제거 전에 현재 기기를 해제하고 브라우저 unsubscribe도 수행한다. 해제에 실패해도 새 계정 등록 시 기존 owner 연결을 끊는다. 다른 기기는 유지한다.

**주요 오류**

403 FORBIDDEN,404 RESOURCE_NOT_FOUND.

### 9.1 푸시 수신·정기 알림 계약

payload는 notificationId/eventId/일반title/body만. 건강정보·토큰 없음. tag=eventId, 클릭 후 인증API 재조회,401이면 로그인 후 재조회한다. SENT/provider 수락과 기기표시·읽음은 별개다. iPhone/Android 표본검증은 별도다.

- USER_REQUEST/AVAILABILITY/MEMBER_LEFT 담당 해제는 즉시 인계 안내.
- 최초 NO_CANDIDATE는 즉시 안내 없이 정기 미지정 대상.
- 매일09:00 KST ACTIVE 부모·자녀 각각의 startsAt>now·PENDING·무담당·OPEN·본인거절없음 항목만 digest.
- DAILY/ONCE와 당일 즉시 안내 항목 제외 규칙 없음. 같은 날짜 수신자별 digest 고유키만 유지.
- 전송 전에 현재 episode와 사용자 응답·상태를 다시 검사.0건이면취소. 거절은 본인에게만 적용.
- 30분전/시작/경과1회/진료준비 알림은 별도이며 과거 예약 몰아보내기 금지.

## 10. 프론트 호출 흐름

| 화면/행동 | 호출 |
|---|---|
| 가입 | A05 → 로그인 화면(A06), 자녀 연결은 로그인 후 G03/G04 |
| 재로그인 | A06 → A03 → NO_GROUP/WAITING_APPROVAL/READY 분기 |
| 첫 부모 연결 | G03 → 부모 정보 입력(필요시) → G04 |
| 승인 대기 | G09 조회, 뒤로가기 G12 성공 후 이동 |
| 승인 관리 | G10 → G11, 역할 변경 G05 → G06 |
| 메인 | T17 월집계 → T01 날짜목록, mineOnly 토글은 목록에만 |
| 미지정 수락/거절 | T11/T16 → T01/T17 갱신 |
| 거절 후 맡기/담당 교체 | T02+G05 → T09 → T01/T17 갱신 |
| 내 담당 내려놓기 | T10 → 목록/캘린더 갱신 |
| 개인 가능 시간 | V01~V06, 건강일정 표시 없음 |
| 진료·처방 | E01~E15 → R01/R02/R03/R04 → 일정 조회 |
| 푸시 | 인증N05 → 사용자버튼 권한 → SW구독 → N06 |
| 로그아웃/계정전환 | N08현재기기 → 브라우저unsubscribe → A04 → 토큰/Blob/메모리해제 |

가입 승인 후·역할 변경 후에는 서버 상태를 재조회한다. 프론트만 화면을 막는 것으로 인가를 대신하지 않는다. 403을 로그아웃으로 처리하지 않으며409는 최신 버전·현재 사건을 조회한다. 네트워크 재시도는 동일 멱등키·동일본문만 재사용한다.

## 11. 이관·버전·수용 기준

DB v1.3의 전환 순서를 따른다. 적용된 V1/V2를 수정하지 않는다. 옛 클라이언트의 A01/A02·N03/N04·G08 호출과 PICKUP·수동반복·수동MEDICATION·기존E05 metadata는 호환되지 않으므로 프론트·백엔드 동시 전환 또는 명시적 단계 배포가 필요하다. 폐기 API의410은 전환 이후 기대값이다.

AI schema는 새 분석부터4종류 kind를 사용한다. 저장된 schemaVersion1 payload의 PICKUP은 명시적으로 OTHER로 읽기매핑하며 수정이력/원문을 보존한다. 신규 출력에 구형 PICKUP을 허용하지 않는다. 모델·schema 버전을 기록한다.

필수 회귀: 부모 nullable가입/자녀이름필수/중복번호/첫가입경쟁/대기권한403/승인취소경쟁/마지막주돌봄자녀/단일공동체/부모정보불변/개인거절독립/재해제새사건/거절후T09/수락경쟁/09시개인별필터/수동약반복거부/문서분류/캘린더정렬/모바일API연동. 기존 파일·AI·처방·lease·rollback 테스트도 유지한다.

## 12. 전체 API 목록

| ID | 기능 | Method | 경로 |
|---|---|---|---|
| A01 | 기존 시연 계정 목록 — 폐기 | GET | /api/v1/auth/demo-accounts |
| A02 | 기존 선택 로그인 — 폐기 | POST | /api/v1/auth/demo-login |
| A03 | 내 정보·가입 진행 상태 | GET | /api/v1/me |
| A04 | 로그아웃 | POST | /api/v1/auth/logout |
| A05 | 전화번호 회원가입 | POST | /api/v1/auth/signup |
| A06 | 전화번호 로그인 | POST | /api/v1/auth/login |
| G01 | 내 ACTIVE 공동체 목록(최대 1건) | GET | /api/v1/me/care-groups |
| G02 | 공동체 상세 | GET | /api/v1/care-groups/{groupId} |
| G03 | 부모 전화번호 조회 | POST | /api/v1/care-groups/recipient-lookup |
| G04 | 부모 연결 신청·첫 자녀 즉시 가입 | POST | /api/v1/care-groups/{groupId}/join |
| G05 | 구성원 목록 | GET | /api/v1/care-groups/{groupId}/members |
| G06 | 자녀 역할 변경 | PUT | /api/v1/care-groups/{groupId}/member-priorities |
| G07 | 공동체 탈퇴 | POST | /api/v1/care-groups/{groupId}/memberships/me/leave |
| G08 | 기존 Today 홈 — 폐기 | GET | /api/v1/care-groups/{groupId}/home |
| G09 | 내 가입 신청 상태 | GET | /api/v1/me/join-requests/current |
| G10 | 승인 대기 목록 | GET | /api/v1/care-groups/{groupId}/join-requests |
| G11 | 가입 승인·거절 | POST | /api/v1/care-groups/{groupId}/join-requests/{requestId}/decision |
| G12 | 내 가입 신청 취소 | POST | /api/v1/me/join-requests/{requestId}/cancel |
| E01 | 기록 생성 | POST | /api/v1/care-groups/{groupId}/encounters |
| E02 | 기록 목록 | GET | /api/v1/care-groups/{groupId}/encounters |
| E03 | 진료/문서 기록 상세 | GET | /api/v1/encounters/{encounterId} |
| E04 | 기록 메타 수정 | PATCH | /api/v1/encounters/{encounterId} |
| E05 | 문서 일괄 업로드 | POST | /api/v1/encounters/{encounterId}/documents |
| E06 | 사용 확인한 음성 업로드 | POST | /api/v1/encounters/{encounterId}/audio |
| E07 | 원문 텍스트 조회 | GET | /api/v1/sources/{sourceId}/text |
| E08 | 전사 원문 수정 | PATCH | /api/v1/sources/{sourceId}/text |
| E09 | 문서 원본 열람 | GET | /api/v1/sources/{sourceId}/content |
| E10 | 소스 제거 | DELETE | /api/v1/encounters/{encounterId}/sources/{sourceId} |
| E11 | 기록 작업 목록 | GET | /api/v1/encounters/{encounterId}/jobs |
| E12 | 작업 상세 조회 | GET | /api/v1/processing-jobs/{jobId} |
| E13 | 실패 작업 재시도 | POST | /api/v1/processing-jobs/{jobId}/retry |
| E14 | 기록 삭제 영향 미리보기 | POST | /api/v1/encounters/{encounterId}/deletion-preview |
| E15 | 기록 삭제·파기 접수 | DELETE | /api/v1/encounters/{encounterId} |
| R01 | 확인 후보 목록 | GET | /api/v1/encounters/{encounterId}/review-items |
| R02 | 후보 일괄 확인·수정·적용 | POST | /api/v1/encounters/{encounterId}/review-items/confirm |
| R03 | 후보 제외 | POST | /api/v1/encounters/{encounterId}/review-items/{itemId}/dismiss |
| R04 | 확정 처방 조회 | GET | /api/v1/care-groups/{groupId}/medications |
| T01 | 선택 날짜 건강 일정 목록 | GET | /api/v1/care-groups/{groupId}/tasks |
| T02 | 일정 상세 | GET | /api/v1/tasks/{occurrenceId} |
| T03 | 건강 일정 직접 단건 추가 | POST | /api/v1/care-groups/{groupId}/task-series |
| T04 | 시리즈 규칙 상세 | GET | /api/v1/task-series/{seriesId} |
| T05 | 반복 일괄 수정 미리보기 | POST | /api/v1/tasks/{occurrenceId}/series-edit-preview |
| T06 | 일정 수정 | PATCH | /api/v1/tasks/{occurrenceId} |
| T07 | 일정 삭제 미리보기 | POST | /api/v1/tasks/{occurrenceId}/deletion-preview |
| T08 | 일정 삭제(취소) | DELETE | /api/v1/tasks/{occurrenceId} |
| T09 | 수동 담당자 지정·변경 | PUT | /api/v1/tasks/{occurrenceId}/assignment |
| T10 | 현재 담당자 인계(담당 내려놓기) | POST | /api/v1/tasks/{occurrenceId}/handoffs |
| T11 | 미해결 미지정 수락 | POST | /api/v1/handoffs/{handoffId}/accept |
| T12 | 완료·대리 완료 | POST | /api/v1/tasks/{occurrenceId}/complete |
| T13 | 완료 재열기 | POST | /api/v1/tasks/{occurrenceId}/reopen |
| T14 | 미지정 사건 목록(보조 조회) | GET | /api/v1/care-groups/{groupId}/handoffs |
| T15 | 일정 변경 이력 | GET | /api/v1/tasks/{occurrenceId}/history |
| T16 | 본인의 미지정 거절 | POST | /api/v1/handoffs/{handoffId}/decline |
| T17 | 메인 월 캘린더 요약 | GET | /api/v1/care-groups/{groupId}/calendar |
| V01 | 활동시간·근무 설정 조회 | GET | /api/v1/me/availability-config |
| V02 | 활동·근무 변경 미리보기 | POST | /api/v1/me/availability-config/preview |
| V03 | 활동·근무 변경 저장 | PATCH | /api/v1/me/availability-config |
| V04 | 내 날짜별 가능 시간 | GET | /api/v1/me/availability-days |
| V05 | 날짜 범위 가능 시간 미리보기 | POST | /api/v1/me/availability-days/preview |
| V06 | 날짜 범위 가능 시간 저장 | PUT | /api/v1/me/availability-days |
| N01 | 알림함 | GET | /api/v1/me/notifications |
| N02 | 알림 읽음 | POST | /api/v1/me/notifications/{notificationId}/read |
| N03 | 기존 반복 알림 설정 조회 — 폐기 | GET | /api/v1/me/notification-preferences |
| N04 | 기존 반복 알림 설정 변경 — 폐기 | PUT | /api/v1/me/notification-preferences |
| N05 | Web Push 공개 설정 | GET | /api/v1/push/config |
| N06 | 기기 구독 등록·갱신 | POST | /api/v1/me/push-subscriptions |
| N07 | 내 활성 기기 구독 목록 | GET | /api/v1/me/push-subscriptions |
| N08 | 기기 구독 해제 | DELETE | /api/v1/me/push-subscriptions/{subscriptionId} |
