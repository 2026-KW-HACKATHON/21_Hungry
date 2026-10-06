# 가족 돌봄 서비스 API 명세서 v1.0

작성일: 2026-10-02 (Asia/Seoul)  
기준: **family-care-PRD-v0.14.md / family-care-DB-design-v1.2.md**  
대상: 가상 의료 데이터를 사용하는 KW 해커톤 MVP. 프론트 2명·백엔드 1명 공동 구현 계약 초안.

이 명세는 기존 PRD·DB의 기능과 28개 테이블을 유지한다. API 경로, JSON DTO, 조회 조합, HTTP 상태, 미리보기 검증 방식은 이번 명세에서 제안하는 **구현 계약**이다. 팀 검토 후 구현 기준으로 고정한다. 기존 기능의 의미를 바꾸는 확정사항으로 간주하지 않는다. 실제 서버 구현·연동 테스트를 완료한 문서가 아니다.

원본 PRD·DB 문서는 수정하지 않았다. 정식 회원가입·OAuth·JWT·refresh token·역할별 화면·실시간 전사·채팅·외부 캘린더·새 인증 테이블은 추가하지 않는다.

## 목차

1. 공통 계약
2. 공통 DTO·상태·검증
3. 인증과 사용자
4. 공동체·구성원·홈
5. 진료 기록·파일·AI 작업
6. 확인 후보·처방
7. 일정·배정·인계·완료
8. 개인 가능 시간
9. 알림·Web Push
10. 프론트 호출 흐름
11. DB 매핑·구현 주의사항
12. 검토 결과·미정 사항·수용 검사
13. 전체 API 목록

## 1. 공통 계약

### 1.1 주소·배포·버전

- 프론트: `https://<frontend>.vercel.app`, 백엔드: `https://<api-domain>`.
- 모든 업무 API 경로는 `/api/v1`로 시작한다. 주소는 예시 자리표시자이며 실제 도메인은 미정이다.
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

인증 실패는 401, 인증 후 다른 공동체·LEFT·개인 소유권 위반은 403. 존재하는 다른 공동체 리소스를 UUID로 요청해도 403이다. 존재하지 않는 UUID는 404. 서버는 데이터 본문을 반환하기 전에 권한을 검사한다. 삭제된 리소스는 권한 검사 가능한 tombstone이 있으면 먼저 인가하고 404 처리한다. 랜덤 UUID는 접근 제어가 아니다.

### 1.3 JSON 규약·시간·페이지네이션

- JSON 필드는 camelCase. ID는 UUID 문자열, enum은 대문자 문자열.
- 필수값은 각 DTO에 정의. 예시의 `null` 필드는 nullable, 배열은 빈 경우 `[]`다. 읽기 전용 필드를 요청으로 보내면 400. 본문에 정의되지 않은 필드는 400으로 거부하는 기본값이다.
- 일반 성공: `{"data": ...}`. 목록: `{"data":{"items":[],"nextCursor":null,"hasMore":false}}`. 204·파일 바이너리는 envelope 없음.
- 실제 순간: ISO-8601 offset 필수. 서버 응답은 `+09:00`으로 통일하는 계약. 날짜는 YYYY-MM-DD, 반복 시각 HH:mm, 업무 시간대 Asia/Seoul.
- 시각 범위 `[from,to)`는 시작 포함·끝 제외. 날짜 범위 입력도 `[fromDate,toDateExclusive)`로 통일한다. 반복 `lastDate`·처방 `endsOn`은 **포함**이다.
- 업무 겹침은 `[startsAt,endsAt)` 기준. 동일 끝/시작 시각은 충돌하지 않는다.
- 목록 `limit` 기본 20, 최대 100은 API 기술 기본값. cursor는 서버가 인코딩한 불투명 문자열로 프론트가 생성하지 않는다. 필터 변경 시 폐기한다.
- 정렬: 업무 startsAt ASC,id ASC; 기록 occurredOn DESC NULLS LAST,createdAt DESC,id DESC; 알림 createdAt DESC,id DESC. 커서는 각 정렬 키·필터를 포함한다. 잘못된 커서는 400 `INVALID_CURSOR`.
- 기록/알림 목록의 cursor는 업무 startsAt cursor와 구별한다. 조회 도중 변경으로 인한 목록 변동은 가능하며 스냅샷 격리를 보장하지 않는다.

### 1.4 오류 envelope

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "다른 가족이 변경한 내용이 있습니다. 새로고침 후 다시 확인해주세요.",
    "requestId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "details": {"currentVersion": 2, "fieldErrors": []}
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

- **모든 POST/PUT/PATCH/DELETE 업무 변경**은 `Idempotency-Key` 필수라는 구현 기본값으로 통일한다. 예외: 로그인/로그아웃, 읽기 목적 preview·전화번호 lookup은 불필요. key는 1~100자 ASCII 식별 문자열, 새 사용자 동작마다 UUID 권장.
- 동일 사용자+operation(메서드·정규 경로/대상 포함)+key, 동일 요청이면 성공 결과의 ID·버전·상태코드를 재반환. 같은 key 다른 내용은 409. multipart hash는 메타 JSON 정규화+파일 순서+각 실제 바이트 해시로 계산하고 boundary는 제외한다.
- 멱등 성공 저장·업무 변경은 한 트랜잭션. 만료 청소 후에도 DB 업무 고유키로 중복 방지. 보관 24시간은 API 기본값 제안. 로그아웃/회원 탈퇴 뒤 기존 멱등 응답으로 권한을 우회하지 못하도록 replay 전에 현재 인증·인가부터 수행한다.
- 같은 요청 동시 실행은 잠금 후 저장 결과 재조회. 대기 한도 초과 시 409 REQUEST_IN_PROGRESS, 같은 키로 재조회/재시도. 임시 업로드는 실패·replay 시 정리한다.
- DB에 version 있는 변경 대상은 `expectedVersion` 필수. 범위 변경은 해당 `seriesVersion`, 인계 수락은 업무와 인계 버전, 확인은 각 후보 버전을 별도로 받는다. 버전 없는 source/job/weekly_work_period는 아래 정의한 inputVersion·textVersion·fingerprint로 검증한다. 파일 저장 상태는 사용자 임의 변경 불가.
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
| UserRef | id UUID, displayName 1~50자 | 없음 |
| Group | id, name 1~80자, recipient UserRef, status, myMembership, version | 없음 |
| Member | id, user, role, status, version | priority: RECIPIENT는 null, CAREGIVER는 1~99 정수 |
| Encounter | id, groupId, recordType VISIT/DOCUMENT, title 1~150자, createdBy, inputVersion, version, createdAt, processingState, hasReviewItems, isSummaryStale | occurredOn 날짜, hospitalName 최대150자 |
| Source | id, encounterId, sourceType AUDIO/DOCUMENT, status PENDING/READY/FAILED, textVersion, file, contentPath | removedAt; contentPath는 원본 unavailable면 null |
| File | id, mediaType, byteSize 양의 정수, state | originalName 최대255자 |
| Job | id, encounterId, jobType TRANSCRIBE/OCR/ANALYZE, inputVersion, status, attemptCount, canRetry | sourceId(ANALYZE=null), error |
| Medication | id, name 1~150자, doseText 1~100자, frequencyText 1~150자, startsOn, endsOn, confirmedBy, confirmedAt | instructions, supersedesId |
| Task | id, groupId, seriesId, seriesVersion, revisionNo, anchorDate, kind, title 1~150자, startsAt, endsAt, dueAt, executionStatus, isOverdue, isOverride, medications, sourceEncounterIds, version | description, assignee, assignmentOrigin, openHandoff, completion, cancellation |
| Handoff | id, occurrenceId, reason, status, version | previousAssignee, requestedBy, acceptedBy, closedAt, closeReason |
| ReviewItem | id, encounterId, revisionId, itemType TASK/MEDICATION, reviewState, reviewReasons, payload, evidence, version | reviewedBy, reviewedAt |

`dueAt`는 endsAt의 응답 별칭이며 DB 새 컬럼이 아니다. summary/derived 상태·페이지 수·sourceEncounterIds는 조회 결과다. 세션·lease·outbox 내부 필드는 반환하지 않는다. 텍스트 description/instructions 등 별도 DB 길이 상한 없는 필드는 임의 제품 상한을 추가하지 않으며 서버 요청 크기 제한을 따른다.

### 2.2 반복·업무·근거

- kind: MEDICATION, HOSPITAL, EXAM, PICKUP, OTHER.
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

10MB·10페이지·10장은 서비스 MVP 정책. OpenAI 기술 제한은 PRD v0.14·DB v1.2의 2026-10-02 공식 문서 확인 기록을 참조한다. 이 API 문서는 외부 공급자 최대치를 제품 정책으로 사용하지 않는다. 외부 모델 입력 전에 서비스 검증·선택 모델의 실제 입력/context 제한을 재검사한다.

## 3. 인증과 사용자

### A01. 시연 계정 목록

`GET /api/v1/auth/demo-accounts`

- 권한: 공개, DEMO 프로필만
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "items": [
      {
        "loginKey": "demo-parent",
        "displayName": "돌봄 대상"
      },
      {
        "loginKey": "demo-child-1",
        "displayName": "자녀 1"
      },
      {
        "loginKey": "demo-child-2",
        "displayName": "자녀 2"
      },
      {
        "loginKey": "demo-child-3",
        "displayName": "자녀 3"
      }
    ]
  }
}
```

**처리·검증**

활성 DEMO 계정만 반환. 전화번호·비밀번호·세션 정보 없음. 대상 공동체/멤버는 시드에서 준비한다.

**주요 오류**

403 DEMO_ONLY(시연 경로 비활성).

### A02. 시연 로그인

`POST /api/v1/auth/demo-login`

- 권한: 공개, DEMO 프로필만
- 성공: `200`

**요청**

```json
{
  "loginKey": "demo-child-1"
}
```

**응답**

```json
{
  "data": {
    "accessToken": "<random-opaque-token>",
    "tokenType": "Bearer",
    "expiresAt": "2026-10-03T18:00:00+09:00",
    "user": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    }
  }
}
```

**처리·검증**

loginKey는 A01의 4개 값. ACTIVE DEMO 계정만 허용. 신규 auth_session 생성, Set-Cookie 없음, no-store. 원문 토큰은 응답으로만 전달.

**주요 오류**

400 VALIDATION_ERROR, 401 UNAUTHORIZED(없는/비활성 계정), 403 DEMO_ONLY.

### A03. 내 정보

`GET /api/v1/me`

- 권한: 인증
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "22222222-2222-4222-8222-222222222222",
    "displayName": "자녀 1",
    "accountType": "DEMO",
    "timezone": "Asia/Seoul",
    "activeStartMinute": 420,
    "activeEndMinute": 1320,
    "version": 0
  }
}
```

**처리·검증**

현재 로그인 사용자. 다른 사용자 ID를 요청으로 받지 않는다.

**주요 오류**

공통 401.

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

## 4. 공동체·구성원·홈

### G01. 내 공동체 목록

`GET /api/v1/care-groups`

- 권한: 인증
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "11111111-1111-4111-8111-111111111111",
        "name": "가상 가족 공동체",
        "recipient": {
          "id": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          "displayName": "돌봄 대상"
        },
        "status": "ACTIVE",
        "myMembership": {
          "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          "user": {
            "id": "22222222-2222-4222-8222-222222222222",
            "displayName": "자녀 1"
          },
          "role": "CAREGIVER",
          "priority": 1,
          "status": "ACTIVE",
          "version": 0
        },
        "version": 0
      }
    ]
  }
}
```

**처리·검증**

자신의 ACTIVE 멤버십만. 소수 공동체 전체 반환, cursor 없음. 선택 상태는 프론트에서 관리한다.

**주요 오류**

공통 401.

### G02. 공동체 상세

`GET /api/v1/care-groups/{groupId}`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "가상 가족 공동체",
    "recipient": {
      "id": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
      "displayName": "돌봄 대상"
    },
    "status": "ACTIVE",
    "myMembership": {
      "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "user": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "role": "CAREGIVER",
      "priority": 1,
      "status": "ACTIVE",
      "version": 0
    },
    "version": 0
  }
}
```

**처리·검증**

리소스 groupId 멤버십 검사. ARCHIVED는 조회 가능, 변경은409 INVALID_STATE.

**주요 오류**

403 NOT_MEMBER, 404 RESOURCE_NOT_FOUND.

### G03. 대상 전화번호 조회

`POST /api/v1/care-groups/recipient-lookup`

- 권한: 인증, 시연
- 성공: `200`

**요청**

```json
{
  "phoneNumber": "<관리자가 준비한 시연 식별번호>"
}
```

**응답**

```json
{
  "data": {
    "recipientUserId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    "displayName": "돌봄 대상",
    "groupId": "11111111-1111-4111-8111-111111111111"
  }
}
```

**처리·검증**

시연 번호만 조회하며 문자·전화·PASS 인증을 수행하지 않는다. 요청 횟수 제한을 적용한다. 합류 화면에 필요한 최소 정보만 반환하고 건강정보는 반환하지 않는다. 시연 식별 번호를 실제 전화번호 인증으로 간주하지 않는다.

**주요 오류**

404 RESOURCE_NOT_FOUND(등록 대상 없음), 400 VALIDATION_ERROR, 429 RATE_LIMITED.

### G04. 공동체 참여

`POST /api/v1/care-groups/{groupId}/memberships`

- 권한: 인증, 시연
- 성공: `201`

**요청**

```json
{
  "recipientUserId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  "confirmedName": "돌봄 대상"
}
```

**응답**

```json
{
  "data": {
    "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "user": {
      "id": "22222222-2222-4222-8222-222222222222",
      "displayName": "자녀 1"
    },
    "role": "CAREGIVER",
    "priority": 1,
    "status": "ACTIVE",
    "version": 0
  }
}
```

**처리·검증**

G03에서 조회한 대상·공동체·이름을 서버에서 대조한다. 로그인 본인만 CAREGIVER로 합류한다. 기존 ACTIVE 멤버라면 200과 기존 Member를 반환한다. LEFT는 기존 행을 ACTIVE로 전환하고 version을 증가시킨다. 신규 priority=2는 이번 구현 기본값 제안이며 가족이 변경할 수 있다. RECIPIENT 추가·변경은 허용하지 않는다. 실제 의료정보 접근 인증으로 사용하지 않는다.

**주요 오류**

400 VALIDATION_ERROR,403 DEMO_ONLY/FORBIDDEN,404 RESOURCE_NOT_FOUND,409 INVALID_STATE.

### G05. 구성원 목록

`GET /api/v1/care-groups/{groupId}/members`

- 권한: 공동체
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        "user": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "role": "CAREGIVER",
        "priority": 1,
        "status": "ACTIVE",
        "version": 0
      }
    ]
  }
}
```

**처리·검증**

ACTIVE 구성원만, priority ASC NULLS LAST,member.id ASC. 본인 가능 시간/다른 공동체 상세를 노출하지 않는다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### G06. 공동 우선순위 변경

`PUT /api/v1/care-groups/{groupId}/member-priorities`

- 권한: 공동체
- 성공: `200`

**요청**

```json
{
  "members": [
    {
      "memberId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      "priority": 1,
      "expectedVersion": 0
    }
  ]
}
```

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        "user": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "role": "CAREGIVER",
        "priority": 1,
        "status": "ACTIVE",
        "version": 1
      }
    ]
  }
}
```

**처리·검증**

members는 변경 대상의 비어 있지 않은 목록이며 ID 중복을 허용하지 않는다. ACTIVE CAREGIVER의 priority만 1~99로 변경하고 동순위를 허용한다. 모든 ID·버전을 검사한 후 원자적으로 적용한다. RECIPIENT의 priority=null은 유지한다. 기존 담당자를 해제하거나 다시 배정하지 않는다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER,409 VERSION_CONFLICT/INVALID_STATE.

### G07. 공동체 탈퇴

`POST /api/v1/care-groups/{groupId}/memberships/me/leave`

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
    "membershipStatus": "LEFT",
    "releasedOccurrenceIds": [
      "55555555-5555-4555-8555-555555555555"
    ]
  }
}
```

**처리·검증**

본인의 Member version을 검사한다. CAREGIVER만 탈퇴할 수 있다. 미래 담당 일정 해제, MEMBER_LEFT 인계 생성, 미발송 알림 차단을 원자적으로 처리한다. 세션·다른 공동체·전역 푸시 구독은 유지한다. 대상 멤버의 단독 탈퇴는 금지한다.

**주요 오류**

403 FORBIDDEN/NOT_MEMBER,409 VERSION_CONFLICT.

### G08. 홈 조회

`GET /api/v1/care-groups/{groupId}/home`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `date=2026-10-05`(선택, 기본 KST오늘), `limit=20`(각 분류 최대100).

**응답**

```json
{
  "data": {
    "groupId": "11111111-1111-4111-8111-111111111111",
    "date": "2026-10-05",
    "todayMyTasks": {
      "items": [
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
          "version": 0
        }
      ],
      "hasMore": false
    },
    "unassignedFutureTasks": {
      "items": [],
      "hasMore": false
    },
    "overdueTasks": {
      "items": [],
      "hasMore": false
    },
    "reviewEncounterCount": 1
  }
}
```

**처리·검증**

오늘 내 업무는 startsAt이 선택한 날짜의 KST 구간에 포함되고, PENDING이며 assignee가 본인인 업무다. 미배정 미래 업무는 PENDING·assignee=null·startsAt>=now, 경과 업무는 PENDING·endsAt<=now다. now는 서버 기준이다. 더보기는 T01에서 해당 필터와 조회 범위를 사용한다. reviewEncounterCount는 현재 버전의 확인 후보가 있는 공동체 내 미삭제 기록 수다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER.

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
        "schemaVersion": 1,
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
        "contentPath": "/api/v1/sources/44444444-4444-4444-8444-444444444444/content"
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
          "schemaVersion": 1,
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
        "version": 0
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
| metadata | application/json | 예 | `{ "expectedVersion":0, "expectedInputVersion":1 }` |
| files | binary 반복 part | 예 | 하나 이상의 원본 문서 |

브라우저가 multipart boundary를 생성하도록 Content-Type을 수동 설정하지 않는다. 파일 순서는 응답 sources 순서와 같다.

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
        "contentPath": "/api/v1/sources/44444444-4444-4444-8444-444444444444/content"
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
        "originalName": "가상녹음.<선택형식>",
        "mediaType": "<출시 전 확정한 실제 음성 MIME>",
        "byteSize": 82000,
        "state": "AVAILABLE"
      },
      "contentPath": null
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

실제 음성 형식·크기·길이를 서버에서 검사한다. 기존 추천값을 구현 기본값으로 사용하면 duration<=1200초, byteSize<=25,000,000이다. 지원 MIME·코덱은 12.2절의 출시 전 확정 항목이며 설정과 다른 형식은 415다. 전사 후 모든 소스가 준비되면 ANALYZE를 실행한다. 전사 저장 후 원본 삭제를 예약하고, 업로드 시 최대 24시간 expiresAt을 설정한다. 문서의 10MB 제한을 음성에 적용하지 않는다.

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
    "text": "가상 문서 원문"
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
          "schemaVersion": 1,
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
        "schemaVersion": 1,
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
        "version": 0
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

TASK payload 필수값은 schemaVersion=1, kind, title, date(YYYY-MM-DD), time(HH:mm), durationMinutes, recurrence다. ONCE가 아니면 lastDate(nullable)·weekdays도 제공한다. description은 선택이다. 조회 후보에는 미상 날짜·시각을 null로 보존하지만 적용에는 명시값이 필요하다. durationSource는 PLANNING_DEFAULT 또는 USER_INPUT으로 계획 시간을 의료 사실과 구분한다. 복약·수령 30분, 동행 120분은 기존 추천값이며 OTHER는 사용자가 입력한다.

MEDICATION payload는 R02 예시 구조다. schedulePlans는 명시적인 시각을 가진 반복 규칙 목록이며 중복 시각은 허용하지 않는다. 각 계획 기간은 처방의 유효 기간 안에 있어야 한다. instructions·supersedesMedicationId는 nullable이다. 변경 대상과 source 참조는 같은 공동체여야 한다. 중단 지시를 일정 삭제로 조용히 실행하지 않는다.

충돌이 있으면 conflictResolution을 제공한다.

```json
{"mode":"SOURCE","selectedSourceId":"44444444-4444-4444-8444-444444444444"}
```

또는 최종 payload를 직접 수정한 뒤 다음 정보를 제공한다.

```json
{"mode":"MANUAL","note":"가족이 원문을 대조해 수정함"}
```

SOURCE는 해당 후보 evidence에 속한 활성 소스만 선택할 수 있고 최종 payload가 선택한 자료의 해소값과 일치해야 한다. MANUAL은 최종 payload 명시가 필수다. 충돌이 없으면 null 또는 생략한다. 서버는 선택 기록을 후보 payload의 schemaVersion=1 확장 reviewResolution에 저장할 수 있으며 새 컬럼은 필요 없다. CONFLICT 사유만 지워 검증을 우회할 수 없다. 사용자 확인은 의료진 검증을 의미하지 않는다.

## 7. 일정·배정·인계·완료

### T01. 가족 일정 목록

`GET /api/v1/care-groups/{groupId}/tasks`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `from`, `to`(시각, 둘 다 필수); `executionStatus`, `assigneeUserId`, `unassigned=true`, `overdue=true`, `encounterId`, `limit`, `cursor` 선택.

**응답**

```json
{
  "data": {
    "items": [
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
        "version": 0
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

**처리·검증**

startsAt이 [from,to)에 포함되는 업무를 조회한다. from<to가 필수이며 assigneeUserId와 unassigned=true는 동시에 사용하지 않는다. 상태 필터를 생략하면 PENDING·COMPLETED만 반환하고 CANCELED를 지정하면 취소 건을 조회한다. overdue=true는 서버 now 기준의 PENDING·endsAt<=now다. assigneeUserId는 해당 공동체의 구성원이어야 한다. Task DTO로 표를 구성하며 encounterId 연결은 후보·약의 실제 관계로 판단한다. 시작이 범위 밖인 장시간 업무는 포함되지 않으므로 필요한 화면은 더 넓은 범위를 조회한다.

**주요 오류**

400 VALIDATION_ERROR/INVALID_CURSOR,403 NOT_MEMBER.

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
    "version": 0
  }
}
```

**처리·검증**

원래 반복 위치인 anchorDate와 실제 startsAt을 분리한다. dueAt은 endsAt의 별칭이며 isOverdue는 계산한다. 취소 건도 권한 검사 후 상세 조회할 수 있다. medications는 발생 건에 연결된 약 snapshot이다.

**주요 오류**

403 NOT_MEMBER,404 RESOURCE_NOT_FOUND.

### T03. 수동 일정 생성

`POST /api/v1/care-groups/{groupId}/task-series`

- 권한: 공동체
- 성공: `201`

**요청**

```json
{
  "kind": "OTHER",
  "title": "돌봄 물품 가져오기",
  "description": null,
  "rule": {
    "recurrence": "ONCE",
    "firstDate": "2026-10-05",
    "lastDate": "2026-10-05",
    "weekdays": [],
    "localTime": "10:00",
    "durationMinutes": 30
  },
  "medicationIds": []
}
```

**응답**

```json
{
  "data": {
    "seriesId": "66666666-6666-4666-8666-666666666666",
    "seriesVersion": 0,
    "rule": {
      "recurrence": "ONCE",
      "firstDate": "2026-10-05",
      "lastDate": "2026-10-05",
      "weekdays": [],
      "localTime": "10:00",
      "durationMinutes": 30
    },
    "occurrences": [
      {
        "id": "55555555-5555-4555-8555-555555555555",
        "groupId": "11111111-1111-4111-8111-111111111111",
        "seriesId": "66666666-6666-4666-8666-666666666666",
        "seriesVersion": 0,
        "revisionNo": 1,
        "anchorDate": "2026-10-05",
        "kind": "OTHER",
        "title": "돌봄 물품 가져오기",
        "description": null,
        "startsAt": "2026-10-05T10:00:00+09:00",
        "endsAt": "2026-10-05T10:30:00+09:00",
        "dueAt": "2026-10-05T10:30:00+09:00",
        "executionStatus": "PENDING",
        "assignee": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "assignmentOrigin": "AUTO",
        "openHandoff": null,
        "isOverdue": false,
        "isOverride": false,
        "medications": [],
        "completion": null,
        "cancellation": null,
        "sourceEncounterIds": [],
        "version": 0
      }
    ],
    "generationWindow": {
      "fromDate": "2026-10-02",
      "toDateExclusive": "2026-10-16"
    }
  }
}
```

**처리·검증**

kind·title·rule이 필수다. description은 선택, medicationIds는 기본 []다. MEDICATION은 같은 공동체의 확인된 약 1개 이상과 유한 종료일이 필수이며 미확정 약을 연결할 수 없다. 다른 kind에서는 medicationIds=[]만 허용한다. 담당자 입력 필드는 받지 않고 자동 배정 후 T09로 변경한다. 발생 생성 범위는 오늘부터 14일이며 첫날이 그 이후이면 occurrences=[]도 정상이다. 기존 동일 패턴 복약 시리즈에 병합되면 200과 기존 seriesId를 반환한다. 계획 전체가 과거이면 422 VALIDATION_ERROR다.

**주요 오류**

400/422 VALIDATION_ERROR,409 DUPLICATE_MEDICATION_CONFLICT,403 NOT_MEMBER.

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
      "version": 1
    }
  }
}
```

**처리·검증**

PENDING이고 기한이 지나지 않아야 한다. 담당자는 같은 공동체의 ACTIVE 멤버이며 전체 가능 시간과 전 공동체 충돌을 검사한다. RECIPIENT의 자동 배정 제외를 수동 배정·수행 기록의 금지로 확대하지 않는다. 수동 배정은 동일한 가능 시간·충돌 검증을 적용한다. 모든 ACTIVE 구성원이 담당자를 변경할 수 있다. null 해제는 받지 않고 T10을 사용한다. OPEN 인계가 있다면 ACCEPTED로 닫고 acceptedBy는 새 담당자, assignmentOrigin은 MANUAL로 기록한다. 버튼 처리자는 audit에 남긴다. 본인이 인계를 수락하는 동작은 T11을 사용한다.

**주요 오류**

409 VERSION_CONFLICT/NOT_AVAILABLE/TIME_CONFLICT/TASK_OVERDUE/INVALID_STATE,403 NOT_MEMBER.

### T10. 다른 가족에게 인계 요청

`POST /api/v1/tasks/{occurrenceId}/handoffs`

- 권한: 공동체
- 성공: `201`

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
      "assignee": null,
      "assignmentOrigin": null,
      "openHandoff": {
        "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "occurrenceId": "55555555-5555-4555-8555-555555555555",
        "reason": "USER_REQUEST",
        "previousAssignee": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "requestedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "status": "OPEN",
        "acceptedBy": null,
        "closedAt": null,
        "closeReason": null,
        "version": 0
      },
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
      "version": 1
    },
    "handoff": {
      "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "occurrenceId": "55555555-5555-4555-8555-555555555555",
      "reason": "USER_REQUEST",
      "previousAssignee": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "requestedBy": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "status": "OPEN",
      "acceptedBy": null,
      "closedAt": null,
      "closeReason": null,
      "version": 0
    }
  }
}
```

**처리·검증**

PENDING이고 담당자가 존재해야 한다. 가족 모두 일정 담당을 변경할 수 있다는 PRD에 따라 모든 ACTIVE 구성원이 요청할 수 있다. 서버가 USER_REQUEST, previousAssignee, requestedBy를 기록한다. 담당자 해제·OPEN 인계·알림을 원자적으로 처리한다. 경과 건은 409 TASK_OVERDUE로 거부하는 API 기본값이며 새 반복 알림을 만들지 않는다. 이미 열린 인계가 있으면 409다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE/TASK_OVERDUE,403 NOT_MEMBER.

### T11. 내가 맡기(인계 수락)

`POST /api/v1/handoffs/{handoffId}/accept`

- 권한: 공동체, 본인 ACTIVE 멤버
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "expectedOccurrenceVersion": 1
}
```

**응답**

```json
{
  "data": {
    "handoff": {
      "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "occurrenceId": "55555555-5555-4555-8555-555555555555",
      "reason": "USER_REQUEST",
      "previousAssignee": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "requestedBy": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "status": "ACCEPTED",
      "acceptedBy": {
        "id": "22222222-2222-4222-8222-222222222222",
        "displayName": "자녀 1"
      },
      "closedAt": "2026-10-02T18:00:00+09:00",
      "closeReason": null,
      "version": 1
    },
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
      "assignmentOrigin": "HANDOFF",
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
      "version": 2
    }
  }
}
```

**처리·검증**

신청자 ID를 받지 않고 로그인 본인을 사용한다. 자동 후보 제외와 본인의 명시적인 수락을 구분하는 구현 계약으로, RECIPIENT도 가능 시간 검증 후 수락할 수 있다. guard 이후 OPEN·PENDING·담당자 없음·미경과·전체 가능 구간·전 공동체 충돌을 재검사한다. 동시 수락은 한 명만 성공하며 다른 요청은 409다. 경과한 OPEN을 EXPIRED로 정리하는 작업은 오류 응답 트랜잭션의 롤백 때문에 사라지지 않도록 별도 커밋 또는 스위퍼로 보장한다.

**주요 오류**

409 ALREADY_ASSIGNED/VERSION_CONFLICT/NOT_AVAILABLE/TIME_CONFLICT/TASK_OVERDUE/INVALID_STATE,403 FORBIDDEN/NOT_MEMBER.

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
      "version": 1
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
      "version": 2
    }
  }
}
```

**처리·검증**

COMPLETED만 재열 수 있다. 완료 snapshot을 audit에 남기고 완료 필드를 지운다. 기존 담당자의 ACTIVE·가능 시간·충돌을 재검사하며 자기 업무는 충돌 검사에서 제외한다. 부적합하면 해제하고 미경과 업무에는 새 인계를 생성한다. 경과 업무는 PENDING·isOverdue=true로 남기며 반복 푸시는 하지 않는다.

**주요 오류**

409 VERSION_CONFLICT/INVALID_STATE,403 NOT_MEMBER.

### T14. 인계 목록

`GET /api/v1/care-groups/{groupId}/handoffs`

- 권한: 공동체
- 성공: `200`

**요청**

Query: `status` 기본OPEN, `limit`, `cursor`.

**응답**

```json
{
  "data": {
    "items": [
      {
        "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "occurrenceId": "55555555-5555-4555-8555-555555555555",
        "reason": "USER_REQUEST",
        "previousAssignee": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "requestedBy": {
          "id": "22222222-2222-4222-8222-222222222222",
          "displayName": "자녀 1"
        },
        "status": "OPEN",
        "acceptedBy": null,
        "closedAt": null,
        "closeReason": null,
        "version": 0,
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
          "version": 0
        }
      }
    ],
    "nextCursor": null,
    "hasMore": false
  }
}
```

**처리·검증**

createdAt DESC,id DESC로 정렬한다. 각 인계에 현재 업무 snapshot을 포함한다. 과거 인계는 이력이며 OPEN과 담당자가 동시에 존재하지 않아야 한다. 만료 스위퍼가 아직 실행되지 않았어도 수락 명령은 시각을 재검사한다.

**주요 오류**

400 VALIDATION_ERROR,403 NOT_MEMBER.

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

### 7.1 배정·반복 구현 공통

신규 생성·후보 적용·매일 발생 생성에서 서버가 결정적 배정을 수행한다. ACTIVE CAREGIVER 중 전체 구간에 가능하고 모든 공동체의 미취소 담당 업무와 충돌하지 않는 후보를 고른다. priority 최소 → 같은 공동체 해당 KST 주 월~일의 미취소 담당 건수 최소(완료 포함) → 4시간 묶음 가능 → member.id 순으로 결정한다. 돌봄 대상은 배정 후보에서 제외하고 미등록 가능 시간은 불가로 처리한다.

묶음은 같은 대상·날짜의 신규 업무에서 첫 시작부터 마지막 시작까지 240분 이내인 경우다. 중간 시간 전체의 가능 여부와 충돌을 검사한다. 각 업무의 최상위 순위·최소 건수 후보 집합에 교집합이 있을 때 한 사람에게 함께 배정한다. 업무 자체는 합치지 않으며 실제 건수만큼 점수에 반영한다.

신규 배정 실패는 NO_CANDIDATE 인계를 만들고, 새 업무 때문에 기존 담당자를 임의로 재배정하지 않는다. 가능 시간 축소는 미래의 부적합 담당만 해제한다. 순위 변경은 이후 신규 배정에만 영향을 준다.

모든 일정 관련 명령은 schedule_guard 잠금 → 최신 조회 → 권한·버전·가능 시간·충돌 검사 → 변경·audit·outbox의 동일 커밋 순서를 따른다. 파일 전송·AI·푸시 외부 호출은 잠금 밖에서 수행한다. 범용 담당자 PATCH 또는 프론트의 배정 계산 결과를 그대로 저장하지 않는다.

### 7.2 이번만 수정 예시

T06 요청:

```json
{
  "scope":"OCCURRENCE",
  "expectedVersion":0,
  "patch":{
    "startsAt":"2026-10-06T09:00:00+09:00",
    "endsAt":"2026-10-06T09:30:00+09:00"
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

patch는 최소 한 필드를 변경한다. weeklyWorkPeriods를 제공하면 전체 대체하고 []면 모두 삭제한다. 활동 시간은 같은 날 start<end, 근무 구간은 ISO 요일 1~7과 minute 범위를 검사한다. 자정 넘는 근무는 두 요일로 분할하며 겹침·인접 구간은 병합한다. 미래 자동 계산 PARTIAL을 다시 계산하고 활동 범위 축소로 직접 편집 구간이 잘리면 날짜·최종 구간을 미리 보여준다. 미등록 날짜를 자동 등록하지 않는다. 모든 참여 공동체를 계산하되 응답에는 접근 가능한 본인 담당 일정 ID만 제공한다.

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

### N03. 알림 설정 조회

`GET /api/v1/me/notification-preferences`

- 권한: 본인
- 성공: `200`

**요청**

본문 없음

**응답**

```json
{
  "data": {
    "handoffRepeat": "DAILY",
    "version": 0
  }
}
```

**처리·검증**

DAILY·ONCE를 사용하며 시드 기본값은 DAILY다. 설정 행은 시드에서 준비하고 없는 경우 서버가 같은 기본값으로 초기화한다.

**주요 오류**

공통401.

### N04. 알림 설정 변경

`PUT /api/v1/me/notification-preferences`

- 권한: 본인
- 성공: `200`

**요청**

```json
{
  "expectedVersion": 0,
  "handoffRepeat": "ONCE"
}
```

**응답**

```json
{
  "data": {
    "handoffRepeat": "ONCE",
    "version": 1
  }
}
```

**처리·검증**

DAILY→ONCE는 다음 digest에서 제외하고 ONCE→DAILY는 다음 발송부터 포함한다. 과거 최초 알림은 다시 만들지 않는다. 확인 필요·정상 담당 일정 알림은 인계 반복 설정과 별개다.

**주요 오류**

400 VALIDATION_ERROR,409 VERSION_CONFLICT.

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

### 9.1 푸시 수신 계약

잠금화면 payload 예시:

```json
{"notificationId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","title":"가족 돌봄 일정이 있습니다","body":"앱에서 내용을 확인해주세요."}
```

약명·진단명·요약·토큰을payload에넣지않는다. ServiceWorker notification tag는eventId. 클릭은프론트알림화면으로이동후인증API로target해결,401은로그인후재조회. 푸시외부서버수락SENT는기기표시/읽음보장아님. 권한거부/미지원시알림함유지. iOS홈화면추가/실기기시험필수. 최초OPEN사건별1회,DAILY는09:00 KST미래미배정업무digest,당일최초안내중복제외. 수락·취소·완료·탈퇴·경과발송전재검사. 시작30분전/시작시각,지난예약시각소급몰아발송없음.

## 10. 프론트 호출 흐름

| 화면/사용자 동작 | 호출 순서 |
|---|---|
| 시연 시작 | A01→A02→A03/G01→공동체선택→G08 |
| 가족 참여 | G03→이름확인→G04→G01/G05 |
| 녹음 | HTTPS마이크허가→녹음종료→사용확인→E01(VISIT)→E06→E11/E12polling→E03 |
| 독립 문서 | E01(DOCUMENT)→E05→E11/E12→E03 |
| 기존 진료에 문서 추가 | E03의version/inputVersion→E05→polling→E03(이전summary stale표시) |
| 처방 확인 | R01→원문E07/E09대조→누락시각/기간/충돌해소→R02→E03/T01 |
| 가족 일정 표 | G05+T01→행별담당·완료·인계축표시 |
| 이번만 수정 | T02→T06(OCCURRENCE)→T02/T01 |
| 동일 내용 모두 수정 | T02/T04→T05→범위·예외안내→T06(SERIES_ALL_PENDING) |
| 이번/앞으로 삭제 | T07→영향안내→T08 |
| 맡지 못함/내가 맡기 | T10→N01/T14→T11→T02 |
| 완료/대리완료/재열기 | T12/T13→T02 |
| 가능 시간 | V01/V04→V02또는V05→영향확인→V03또는V06 |
| 푸시 활성화 | N05→사용자버튼으로브라우저권한→ServiceWorker구독→N06 |
| 계정전환/로그아웃 | N08(현재기기)→A04→토큰/메모리/Blob정리→새로그인→필요시새N06 |

401은재로그인,403은권한안내(토큰삭제안함),409는최신조회후미리보기/사용자동작재확인. 네트워크/응답유실재시도는같은Idempotency-Key·동일본문,내용변경은새키. 413/415/422는파일조정안내. AI FAILED는단계/code/canRetry에따라재시도또는원본조정. 무조건새업로드/새기록생성으로오류회피하지않는다.

multipart 호출 예시(프레임워크 독립):

```javascript
const body = new FormData();
body.append('metadata', new Blob([JSON.stringify({
  expectedVersion: encounter.version,
  expectedInputVersion: encounter.inputVersion
})], { type: 'application/json' }));
for (const file of files) body.append('files', file);
const response = await fetch(`${apiOrigin}/api/v1/encounters/${encounter.id}/documents`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${accessToken}`,
    'Idempotency-Key': operationKey // 동일 동작 재시도에서는 같은 값 유지
  },
  body
});
```

파일 열람 예시:

```javascript
const response = await fetch(`${apiOrigin}/api/v1/sources/${source.id}/content`, {
  headers: { Authorization: `Bearer ${accessToken}` }
});
if (!response.ok) throw new Error(`파일 조회 실패: ${response.status}`);
const url = URL.createObjectURL(await response.blob());
// img/iframe 등에 url 연결. 화면 종료·계정 전환 시 URL.revokeObjectURL(url).
```

브라우저토큰저장은최종미정,localStorage는가상데이터MVP추천안만. JS접근토큰은XSS위험있으며실제건강정보운영전재검토. UI문자열은텍스트렌더링하고API응답·파일·토큰을오프라인캐시/분석로그에저장하지않는다.

## 11. DB 매핑·구현 주의사항

| API 영역 | 기존 DB | 구현 책임 |
|---|---|---|
| 로그인/사용자 | app_user,auth_session | hash·만료·revoked·DEMO검사 |
| 공동체/우선순위 | care_group,group_member | ACTIVE·역할·멤버version·재가입 |
| 가능시간 | app_user,weekly_work_period,availability_day,availability_interval | 최종구간·configfingerprint·날짜version |
| 기록/원본 | encounter,file_asset,encounter_source | 실제MIME/크기·장수락·inputVersion·삭제워커 |
| 작업/요약 | processing_job,encounter_revision | lease·dedup·inputVersion맞는결과만반영 |
| 확인/약 | extracted_item,medication_order | 필수값·충돌해소·원자승인·supersedes |
| 반복/발생 | task_series,task_series_revision,series_medication,task_occurrence,occurrence_medication | anchorDate·규칙snapshot·취소tombstone·14일생성 |
| 인계/이력 | handoff_request,audit_event | 해제+OPEN·수락경쟁·수행자/처리자 |
| 알림/기기 | notification_preference,push_subscription,notification_event,notification,notification_delivery | 본인/ACTIVE·outbox·endpoint이력·최소payload |
| 재시도/락 | idempotency_record,schedule_guard | 성공응답원자저장·업무전역락 |

DTO의dueAt/pageCount/activeImageCount/processingState/isOverdue/workConfigVersion/previewToken은새DB컬럼을요구하지않는다. version은수정대상실제테이블의version이며서로다른리소스버전을혼용하지않는다. inputVersion은분석입력변화,revisionNo는반복규칙의불변버전,seriesVersion은시리즈동시성버전.

JPA @Version·벌크version증가·guard일관성,DB복합FK와서비스ACTIVE검사를함께구현한다. ddl-auto=validate/Flyway. 이미적용한V1수정금지. 원본바이트는비공개파일저장소,DB에는메타/키만. 페이지수는서비스파서검사·워커재검사,영속컬럼추가없음. 음성전사성공시삭제예약·실패음성24시간상한운영검사.

명세의preview서명/JSON확장/계산fingerprint는애플리케이션구현이며DDL변경없음. JSONdetails/payload는schemaVersion=1검증을서버에서고정하고임의공급자JSON을그대로API로노출하지않는다.

## 12. 검토 결과·미정 사항·수용 검사

### 12.1 유지한 요구사항과 새 구현 계약

유지한 사항: 4개 시연 계정, care_group·ACTIVE 권한, Bearer opaque 세션, Vercel·EC2 분리 배포, 녹음·전사·요약·OCR, 최소 확인 흐름, 복약 묶기, 가능 시간·자동 배정, 반복 수정·삭제, 인계·대리 완료·재열기, 실제 Web Push·알림함, 가상 데이터 시연, 기존 28개 테이블.

이번에 구체화한 구현 계약: `/api/v1` 경로, camelCase DTO·envelope, 목록 페이지 크기, 변경 요청의 멱등 헤더, source 원본 경로, 서명한 preview token·fingerprint, OCR_PROCESSING·EMPTY 화면 라벨, 전사 정정, 기기 메타, 추가 오류 코드. 기존 문서의 `GET /api/documents/{id}`는 예시였으며 이번 제안에서는 `GET /api/v1/sources/{id}/content`로 구체화했다.

### 12.2 구현·배포 전 확정할 사항

- 실제 Vercel production·preview Origin, 개발 포트, API 도메인과 CORS 환경값.
- 프론트 프레임워크·빌드 도구·환경변수 이름·토큰 저장 위치. API 작성 자체를 막는 항목은 아니다.
- 음성 **지원 MIME·코덱**, 추천 20분·25MB의 최종값. iOS·Android의 MediaRecorder 출력과 선택한 전사 API를 시험한 후 확정한다. 문서 MIME 목록을 음성에 적용하지 않는다.
- AI 공급자·모델·비용 상한·보관 조건. 약명·숫자·한국어 샘플을 시험하고 API key는 서버에만 둔다.
- 기술 설정: multipart 전체 요청 제한, 범위 변경 최대 처리량, 요청 횟수 제한, preview·멱등 TTL, polling 간격. 제품 업로드 제한과 구분한다.
- DB의 주간 건수·추천 소요시간·일괄 수정 기본값과 이번 추가 구현 계약은 팀 검토 후 고정한다.

### 12.3 명세 대조와 구현 후 수용 검사

작성 과정에서 PRD v0.14와 DB v1.2의 인증·배포·업로드·반복 범위·가능 시간·인계·푸시 계약을 대조했다. 아래 항목은 **실행 완료 결과가 아닌 구현 후 검증 목록**이다.

1. 네 계정 로그인, 복수 세션, 현재 토큰만 로그아웃, 401·403 구분.
2. 타 공동체 source·task·job·후보·알림 UUID 및 중첩 ID 요청 403, 개인 데이터 소유권 검사.
3. Vercel→API preflight, 인증 파일 fetch, 오류 응답의 CORS 처리.
4. 10MB·10페이지·10장 경계와 9장 상태의 동시 업로드, 여러 파일 검증 실패 시 부분 등록 금지.
5. 전사·OCR 실패 및 일부 소스 실패 표시, 최신 inputVersion 결과만 반영, 음성 삭제와 요약 재시도.
6. 일괄 확인 중 한 후보가 충돌하면 전체 롤백, 필수 시각·기간 누락 및 미해결 충돌 적용 금지.
7. 같은 시각의 약 묶기와 기간별 목록, 하루 두 번은 별도 시리즈, 일정 삭제와 약 중단 구별.
8. 이번만 이동 시 anchorDate 유지, 일괄 수정 예외 덮어쓰기 안내, 완료·취소·지난 업무 보존.
9. 앞으로 삭제의 anchorDate 경계, 이동 예외 포함, 사용자 취소 재생성 금지, RULE_CHANGED 복구 구별.
10. 전 공동체 동일 사용자 충돌, 첫 시작 기준 4시간 묶기, 중간 구간 및 실제 건수 검사.
11. 가능 시간 축소 시 미래 부적합 담당만 해제, 진행 중 이력 보존, 자동 재배정 없음.
12. 인계 동시 수락 한 명 성공, 경과 수락 거부, 대리 완료·돌봄 대상 체크·재열기.
13. 동일 멱등 키 재시도 중복 방지, 다른 본문 409, 탈퇴 후 replay로 권한 우회 금지.
14. preview 이후 버전·가능 시간·영향 범위·cutoff·만료 변경 시 409와 재확인.
15. 최초 인계 사건·digest 중복 제외, 계정 전환의 구독 이력 유지, 404·410 비활성화, 알림 읽음 구분.
16. 기록 삭제 시 공유 약 업무 유지, 즉시 접근 차단, 원문·민감 snapshot 파기, 최소 완료 이력 안내.

### 12.4 기준 문서·출처 범위

- 프로젝트 소스 `family-care-PRD-v0.14.md`, `family-care-DB-design-v1.2.md`(2026-10-02).
- 외부OpenAI제한은기준문서에서검증한기록을참조: https://developers.openai.com/api/docs/guides/file-inputs / https://developers.openai.com/api/docs/guides/images-vision (기준문서확인일2026-10-02). 이명세작성에서외부가이드를재조회한검증결과로주장하지않는다.
- 본 API 경로/DTO/preview 방식은 외부 공식 API가 아니라 프로젝트 구현 계약 초안이다.

## 13. 전체 API 목록

| ID | 기능 | 메서드 | 경로 | 성공 |
|---|---|---|---|---|

| A01 | 시연 계정 목록 | GET | `/api/v1/auth/demo-accounts` | 200 |

| A02 | 시연 로그인 | POST | `/api/v1/auth/demo-login` | 200 |

| A03 | 내 정보 | GET | `/api/v1/me` | 200 |

| A04 | 로그아웃 | POST | `/api/v1/auth/logout` | 204 |

| G01 | 내 공동체 목록 | GET | `/api/v1/care-groups` | 200 |

| G02 | 공동체 상세 | GET | `/api/v1/care-groups/{groupId}` | 200 |

| G03 | 대상 전화번호 조회 | POST | `/api/v1/care-groups/recipient-lookup` | 200 |

| G04 | 공동체 참여 | POST | `/api/v1/care-groups/{groupId}/memberships` | 201 |

| G05 | 구성원 목록 | GET | `/api/v1/care-groups/{groupId}/members` | 200 |

| G06 | 공동 우선순위 변경 | PUT | `/api/v1/care-groups/{groupId}/member-priorities` | 200 |

| G07 | 공동체 탈퇴 | POST | `/api/v1/care-groups/{groupId}/memberships/me/leave` | 200 |

| G08 | 홈 조회 | GET | `/api/v1/care-groups/{groupId}/home` | 200 |

| E01 | 기록 생성 | POST | `/api/v1/care-groups/{groupId}/encounters` | 201 |

| E02 | 기록 목록 | GET | `/api/v1/care-groups/{groupId}/encounters` | 200 |

| E03 | 진료/문서 기록 상세 | GET | `/api/v1/encounters/{encounterId}` | 200 |

| E04 | 기록 메타 수정 | PATCH | `/api/v1/encounters/{encounterId}` | 200 |

| E05 | 문서 일괄 업로드 | POST | `/api/v1/encounters/{encounterId}/documents` | 202 |

| E06 | 사용 확인한 음성 업로드 | POST | `/api/v1/encounters/{encounterId}/audio` | 202 |

| E07 | 원문 텍스트 조회 | GET | `/api/v1/sources/{sourceId}/text` | 200 |

| E08 | 전사 원문 수정 | PATCH | `/api/v1/sources/{sourceId}/text` | 202 |

| E09 | 문서 원본 열람 | GET | `/api/v1/sources/{sourceId}/content` | 200 |

| E10 | 소스 제거 | DELETE | `/api/v1/encounters/{encounterId}/sources/{sourceId}` | 202 |

| E11 | 기록 작업 목록 | GET | `/api/v1/encounters/{encounterId}/jobs` | 200 |

| E12 | 작업 상세 조회 | GET | `/api/v1/processing-jobs/{jobId}` | 200 |

| E13 | 실패 작업 재시도 | POST | `/api/v1/processing-jobs/{jobId}/retry` | 202 |

| E14 | 기록 삭제 영향 미리보기 | POST | `/api/v1/encounters/{encounterId}/deletion-preview` | 200 |

| E15 | 기록 삭제·파기 접수 | DELETE | `/api/v1/encounters/{encounterId}` | 202 |

| R01 | 확인 후보 목록 | GET | `/api/v1/encounters/{encounterId}/review-items` | 200 |

| R02 | 후보 일괄 확인·수정·적용 | POST | `/api/v1/encounters/{encounterId}/review-items/confirm` | 200 |

| R03 | 후보 제외 | POST | `/api/v1/encounters/{encounterId}/review-items/{itemId}/dismiss` | 200 |

| R04 | 확정 처방 조회 | GET | `/api/v1/care-groups/{groupId}/medications` | 200 |

| T01 | 가족 일정 목록 | GET | `/api/v1/care-groups/{groupId}/tasks` | 200 |

| T02 | 일정 상세 | GET | `/api/v1/tasks/{occurrenceId}` | 200 |

| T03 | 수동 일정 생성 | POST | `/api/v1/care-groups/{groupId}/task-series` | 201 |

| T04 | 시리즈 규칙 상세 | GET | `/api/v1/task-series/{seriesId}` | 200 |

| T05 | 반복 일괄 수정 미리보기 | POST | `/api/v1/tasks/{occurrenceId}/series-edit-preview` | 200 |

| T06 | 일정 수정 | PATCH | `/api/v1/tasks/{occurrenceId}` | 200 |

| T07 | 일정 삭제 미리보기 | POST | `/api/v1/tasks/{occurrenceId}/deletion-preview` | 200 |

| T08 | 일정 삭제(취소) | DELETE | `/api/v1/tasks/{occurrenceId}` | 200 |

| T09 | 수동 담당자 지정·변경 | PUT | `/api/v1/tasks/{occurrenceId}/assignment` | 200 |

| T10 | 다른 가족에게 인계 요청 | POST | `/api/v1/tasks/{occurrenceId}/handoffs` | 201 |

| T11 | 내가 맡기(인계 수락) | POST | `/api/v1/handoffs/{handoffId}/accept` | 200 |

| T12 | 완료·대리 완료 | POST | `/api/v1/tasks/{occurrenceId}/complete` | 200 |

| T13 | 완료 재열기 | POST | `/api/v1/tasks/{occurrenceId}/reopen` | 200 |

| T14 | 인계 목록 | GET | `/api/v1/care-groups/{groupId}/handoffs` | 200 |

| T15 | 일정 변경 이력 | GET | `/api/v1/tasks/{occurrenceId}/history` | 200 |

| V01 | 활동시간·근무 설정 조회 | GET | `/api/v1/me/availability-config` | 200 |

| V02 | 활동·근무 변경 미리보기 | POST | `/api/v1/me/availability-config/preview` | 200 |

| V03 | 활동·근무 변경 저장 | PATCH | `/api/v1/me/availability-config` | 200 |

| V04 | 내 날짜별 가능 시간 | GET | `/api/v1/me/availability-days` | 200 |

| V05 | 날짜 범위 가능 시간 미리보기 | POST | `/api/v1/me/availability-days/preview` | 200 |

| V06 | 날짜 범위 가능 시간 저장 | PUT | `/api/v1/me/availability-days` | 200 |

| N01 | 알림함 | GET | `/api/v1/me/notifications` | 200 |

| N02 | 알림 읽음 | POST | `/api/v1/me/notifications/{notificationId}/read` | 200 |

| N03 | 알림 설정 조회 | GET | `/api/v1/me/notification-preferences` | 200 |

| N04 | 알림 설정 변경 | PUT | `/api/v1/me/notification-preferences` | 200 |

| N05 | Web Push 공개 설정 | GET | `/api/v1/push/config` | 200 |

| N06 | 기기 구독 등록·갱신 | POST | `/api/v1/me/push-subscriptions` | 201 |

| N07 | 내 활성 기기 구독 목록 | GET | `/api/v1/me/push-subscriptions` | 200 |

| N08 | 기기 구독 해제 | DELETE | `/api/v1/me/push-subscriptions/{subscriptionId}` | 204 |
