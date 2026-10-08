# 가족 돌봄 서비스 DB 설계 v1.3

갱신: 2026-10-08 KST. 기준: PRD v0.15 / API v1.1. v1.2의 현재 설계를 대체한다.
이 문서의 DDL은 목표 스키마이며 배포된 migration이 아니다. 기존 V1/V2와 적용된 migration은 수정하지 않는다. 실제 저장소의 추가 migration·제약명을 확인하여 다음 버전 migration을 별도로 작성해야 한다.

## 1. 변경 요약과 테이블 구성

기존 28개 테이블을 보존하고 group_join_request, handoff_response를 추가한 30개 목표 테이블이다. notification_preference는 기존 데이터 보존용으로만 남으며 DAILY/ONCE 제품 정책·읽기·수정은 제거한다. task_series.kind는 네 가지로 축소한다. 기존 handoff_request를 모든 미지정 발생 사건으로 재사용하고 사용자 거절만 별도 저장한다.

| 영역 | 주요 변경 |
|---|---|
| app_user | 전화번호 필수·정규화 유일, account_role=PARENT/CHILD, 부모 이름 최초 nullable, login_key legacy nullable |
| care_group | 어머니/아버지·출생연도·최초 완료자/시각, 최초 완료 후 수정 금지 |
| group_member | PENDING 추가, ACTIVE/PENDING 합쳐 user당 하나, 자녀 priority 1/2 |
| group_join_request | 첫 가입/승인·거절·취소 이력과 version, 재신청은 새 요청 ID |
| task_series | EXAM/MEDICATION/HOSPITAL/OTHER, creation_origin으로 수동 ONCE 제약 구분 |
| handoff_request | 최초 배정 실패 및 담당 해제의 episode. 발생 건당 OPEN 하나 |
| handoff_response | episode+user별 DECLINED만 저장. 행 없음=미해결 |
| encounter_source | document_type 세 종류, 기존 자료만 LEGACY_UNCLASSIFIED |
| notification_preference | deprecated 보존. 정기 발송 판단에서 사용 금지 |

## 2. 계정·공동체 모델

### 2.1 가입과 로그인

한국 휴대전화 MVP 입력 기본값은 하이픈/공백을 제거한 010 + 8자리(11자리)다. 다른 국가 번호는 이번 계약에서 받지 않는다. 정규화 후 UNIQUE로 동시 중복 가입을 막는다. 전화번호 소유 인증·password 검증 없이 로그인하는 해커톤 설정이다. account_type은 기존 DEMO/REAL 데이터 분류이며 가입 역할(account_role)과 구분한다. 이번 가입은 DEMO로 생성한다. 기존 login_key는 테스트 도구용이고 전화번호 API에서 식별자로 사용하지 않는다.

부모는 display_name=null로 가입할 수 있다. 자녀는 공백 제거 후 1~50자 이름 필수다. 부모 가입과 care_group·RECIPIENT ACTIVE 멤버 삽입을 한 트랜잭션에서 수행한다. 기본 group.name은 ‘돌봄 공동체’로 두고 부모 최초 정보 완료 때 ‘{성함} 돌봄 공동체’로 설정한다. UI placeholder를 실제 부모 이름으로 저장하지 않는다.

가입 API는 계정만 생성하고 토큰을 반환하지 않는다. 자녀의 연결은 로그인 후 별도 API이며 가입 화면에서 연속 호출할 수 있다. 중복 전화번호는 409, 로그인 없는/비활성 번호는 401이다. auth_session 발급·해시·만료·복수 세션·현재 세션 로그아웃은 기존 계약을 유지한다.

### 2.2 단일 공동체와 가입 신청

uq_member_current_group은 user_id WHERE status IN ('ACTIVE','PENDING')로 만든다. 부모도 포함한다. 여러 과거 LEFT 행은 허용한다. 다른 부모 가입·신청은 현재 관계가 끝난 후 가능하다. 한 공동체·사용자 멤버 행은 UNIQUE이며 재가입 시 재사용하지만 신청 이력은 새 group_join_request로 남긴다.

멤버와 신청 상태 전이:

| 동작 | group_member | group_join_request |
|---|---|---|
| 첫 ACTIVE 자녀 0명에서 연결 | ACTIVE/priority1/joined_at=now | APPROVED/FIRST_JOIN/decided_by=본인 |
| 기존 ACTIVE 자녀 있음 | PENDING/priority2/joined_at=null | PENDING |
| 현재 주돌봄자녀 승인 | ACTIVE/priority2/joined_at=now | APPROVED/REVIEW |
| 주돌봄자녀 거절 | LEFT/left_at=now | REJECTED/REVIEW |
| 신청자 취소 | LEFT/left_at=now | CANCELED/REVIEW/decided_by=본인 |
| ACTIVE 자녀 탈퇴 | LEFT/left_at=now | 과거 승인 이력 유지 |

PENDING→LEFT는 실제 가입 완료 후 탈퇴를 뜻하지 않을 수 있다. group_join_request가 경위를 보존한다. 재신청 시 left_at=null·joined_at=null로 전환하고 새 신청 행을 만든다. FIRST_JOIN 경로에서는 joined_at를 새 시각으로 채운다.

모든 연결/승인/취소/역할 변경/탈퇴는 schedule_guard(id=1) → 관련 계정·그룹·멤버·신청 행의 고정 순서 잠금으로 직렬화한다. 계정당 부분 UNIQUE와 요청당 version은 최종 방어다. 동시 첫 자녀 요청은 정확히 하나만 FIRST_JOIN이고 다음은 PENDING이다. 현재 ACTIVE 자녀 0명일 때만 첫 연결 판단하며 대기자를 자동 승인하지 않는다.

승인 권한은 ACTIVE CAREGIVER priority1, 역할 변경은 모든 ACTIVE 멤버다. 마지막 priority1을 잃는 변경은 최종 ACTIVE 자녀가 남는 한 거부한다. 일괄 승격+강등은 최종 상태 기준으로 허용한다. 부모 role/priority는 RECIPIENT/null로 고정한다. DB 행간 규칙은 CHECK만으로 표현하지 않고 동일 guard 서비스와 회귀 테스트로 보장한다.

### 2.3 부모 정보 최초 저장

부모 성함=app_user.display_name, 공통 관계/출생연도/완료자·시각=care_group이다. 최초 입력은 첫 자녀 연결과 원자 처리한다. 이름 먼저 갱신 후 completed_at를 기록한다. 생년 범위는 1900~서버 KST 현재 연도(서비스 검증), DDL은 고정 범위만 검사한다. completed 이후 트리거와 API 모두 수정 차단한다. 모두 탈퇴해도 최초 정보는 보존한다. 신규 동시 연결에서 늦은 요청이 부모 정보를 덮어쓸 수 없다.

## 3. 일정과 미지정 episode

### 3.1 상태 분리

실행 상태 PENDING/COMPLETED/CANCELED, assignee 존재, isOverdue, 사용자별 응답은 독립이다. Task.myUnassignedState는 미완료 무담당이면 현재/최근 episode에 본인의 DECLINED 존재 여부로 RESOLVED 또는 UNRESOLVED, 나머지는 NOT_APPLICABLE이다. 거절을 occurrence.status나 handoff_request.status=CLOSED로 저장하지 않는다.

- NO_CANDIDATE는 첫 배정 실패 episode. 즉시 인계 알림 없음.
- USER_REQUEST는 현재 담당자가 직접 해제. previous_assignee/requested_by 저장, 즉시 안내.
- AVAILABILITY는 가능 시간 또는 업무 시간 변경·재열기 검증에서 부적합 담당 해제, MEMBER_LEFT는 탈퇴 해제. 즉시 안내.
- 모든 미래 PENDING 무담당 발생에는 OPEN episode 하나를 유지한다. 재처리로 새 episode를 계속 만들지 않는다.
- 거절 PK=(handoff_id,user_id). 같은 거절 반복은 ON CONFLICT DO NOTHING이며 responded_at 유지. 다른 사용자 응답·occurrence.version을 올리지 않는다.
- 수락 또는 수동 지정은 guard 이후 상태/버전/가능시간/충돌 검증, assignee 지정+episode ACCEPTED+pending event/delivery 종료+audit+멱등 성공 원자 처리.
- 수동 지정의 accepted_by는 새 담당자, audit actor는 조작한 구성원이다. 과거 DECLINED도 수동 지정 가능하다.
- 재해제 시 이전 episode/응답 보존하고 새 ID를 생성한다. 새 episode에 응답을 복사하지 않는다.
- 완료/취소는 episode CLOSED. ends_at<=now는 EXPIRED이며 일정 PENDING 자체는 유지한다. 과거 무담당 표시에는 최신 EXPIRED episode 응답을 사용하되 수락/거절과 09시 대상에서는 제외한다.
- 시간 수정으로 미래로 옮겨지면 새 OPEN episode를 만든다. 열려 있는 episode에서 내용/시간만 바뀌고 미지정이 유지되는 경우 기존 응답은 유지한다(새 담당 해제 사건 아님).

### 3.2 수동/AI 생성

kind는 네 가지. PICKUP은 OTHER로 매핑한다. creation_origin=MANUAL은 ONCE·non-MEDICATION만 허용하며 API뿐 아니라 생성/수정 서비스에서 검사한다. REVIEW는 처방 확인 등 확인 후보 적용, AI는 명확한 TASK 자동 적용, 이력을 판별할 수 없으면 LEGACY다. AI_TASK 후보가 MEDICATION을 우회 생성하지 못한다.

task_series/revision/occurrence anchor·14일 horizon·복약 연결·snapshot·취소 tombstone·4시간 묶음과 audit는 유지한다. 반복의 같은 날짜 중복은 UNIQUE(series_id,anchor_date)로 막는다. SINGLE 공동체로 바뀌어도 이전 공동체 미래 배정이 남을 수 있으므로 사용자 전역 시간 충돌 검사는 유지한다. 탈퇴 시 미래 배정 해제 후 새 공동체에 연결한다.

## 4. 조회와 알림

날짜별 목록은 starts_at을 KST 날짜로 바꿔 해당 날짜에 포함한다. 미완료 미지정+거절 없음 rank0, 미완료 미지정+본인 거절 rank1, 나머지 rank2 순으로 starts_at,id를 붙인다. cursor는 rank/startsAt/id 및 사용자·필터·날짜를 포함한다. 목록 변경 시 첫 페이지부터 재조회한다.

월 요약은 취소 제외 total/completed/unassignedPending를 집계한다. 표시 NONE/NORMAL/ATTENTION은 total=0/일반/미완료 무담당>0 순이다. 사용자 거절은 빨간 경고를 없애지 않는다. 월 집계는 전체 공동체, mineOnly는 일별 목록에만 적용한다. 개인 가능 시간 조회는 건강 일정을 섞지 않는다.

09시 eligibility:
ACTIVE 계정+ACTIVE 멤버, occurrence PENDING/assignee NULL/starts_at>now,
현재 handoff OPEN, 해당 사용자 handoff_response 없음.
notification_preference와 당일 최초 알림 생성 여부는 조건에 넣지 않는다.

정기 event_key=digest:{groupId}:{userId}:{KSTdate}. payload에는 occurrenceId와 handoffId 쌍을 저장해 재해제된 새 사건과 구분한다. 발송 직전 유효한 쌍만 재조회하고 0개면 취소한다. 거절 이후 그 사람의 미발송 인계/digest는 필터링하되 다른 사람 알림은 유지한다. 이미 생성된 알림함 이력은 삭제하지 않아도 되며 외부 푸시를 회수할 수 없다.

가입 요청/결과는 G09/G10 서버 상태 조회로 화면을 갱신한다. 가입 승인 전 건강정보 알림을 허용하지 않는다. 가입 전용 푸시는 이번 필수 범위에 추가하지 않는다.

## 5. 파일·AI·권한 유지 계약

문서 유형과 media_type은 별개다. DOCUMENT에는 세 분류 중 하나가 필요하며 AUDIO는 null이다. 기존 문서만 LEGACY_UNCLASSIFIED를 허용하고 신규 API에는 금지한다. 원본 자료 분류를 AI 추측으로 덮어쓰지 않는다.

파일당10,000,000 bytes, PDF10페이지, encounter 활성 이미지10장; WAV25,000,000 bytes/1200초. signature/parser/decoder로 확인한다. 비공개 임시→검증→승격, DB 실패 파일 보상·고아 정리, 음성24시간 만료·전사 성공 후 삭제, record 삭제시 민감 본문·근거 제거와 공유 복약 업무 보존을 유지한다.

processing_job의 SKIP LOCKED·lease/fencing·inputVersion·textVersion·동일 revision 중복 방지를 유지한다. 모든 소스 준비 전 부분 분석 성공으로 표시하지 않는다. 모델이 DB ID·상태·담당자를 결정하지 않는다. quote/textVersion/PDF page/Unicode codepoint를 서버 검증하며 신규·변경 약은 확인 필수다.

outbox, notification(event,user), delivery(notification,subscription) 유일성 유지. HTTP404/410 구독 해제, 429 Retry-After, 제한 backoff, 외부 전송 잠금 밖, 이전 worker fencing. 사용자의 현재 ACTIVE 권한을 replay/전송 전 재검사한다. hash token만 저장한다.

## 6. 배포 DB 전환 절차

1. 백업과 격리 복원을 확보하고 현재 Flyway·제약·행 수를 확인한다. 아래 목표 DDL로 기존 DB를 덮어쓰지 않는다.
2. 사전 검사: 중복 정규화 번호·누락 번호·부모/자녀 역할 모호·복수 ACTIVE·priority 3~99·기존 PICKUP·문서 분류·현재 무담당 사건을 추출한다. 값이 모호하면 migration을 중단하고 매핑 파일로 해결한다.
3. 계정 역할은 기존 RECIPIENT/CAREGIVER 관계로 일관될 때만 채운다. 부모/자녀가 혼재한 계정은 자동 선택 금지. 전화번호 없는 이전 데모 계정은 명시적 가상 번호 매핑이 필요하다.
4. 신규 컬럼은 초기 nullable로 추가하고 backfill 후 NOT NULL/유일성 검증. 기존 ACTIVE joined_at를 유지한다. parent profile의 관계·출생연도는 만들지 않는다. 이미 자녀가 연결된 부모는 별도 승인된 초기 이관 입력이 필요하며 기존 이름만 있다고 완료로 표시하지 않는다.
5. 기존 자녀 priority1은 유지, priority>=2는2로 명시적으로 정규화한다. ACTIVE 자녀가 있으나1이 전혀 없는 그룹은 임의 선택하지 않고 역할 매핑을 요구한다. 다중 공동체는 유지할 한 곳과 다른 곳 탈퇴/미래 배정 해제를 명시하고 정상 서비스 전이 또는 검증된 이관으로 처리한다. 행 삭제로 FK 이력을 훼손하지 않는다.
6. PICKUP→OTHER, task_series.creation_origin은 source/기록 근거로만 채우고 불명확하면 LEGACY. document_type은 확인된 매핑 또는 LEGACY_UNCLASSIFIED. 기존 AI payload의 schemaVersion1도 읽기 호환하며 다음 분석부터 schemaVersion2의 새 4종류 schema로 출력한다.
7. 모든 미래 PENDING 무담당에 기존 OPEN episode를 재사용하거나 NO_CANDIDATE를 보충한다. 기존 OPEN에 사용자 거절은 없으므로 모두 미해결. 이관 자체로 대량 즉시 인계 알림을 발송하지 않는다.
8. 이전 DAILY/ONCE 기반 미발송 digest를 취소/재계산하고 새 정책 적용 시각을 기록한다. 기존 알림함·SENT·notification_preference 이력은 보존한다. source/job/처방 데이터를 초기화하지 않는다.
9. API/UI 계약 전환과 worker 재개를 맞춘다. 신규 가입·신청·응답·조회·09시 검증 후 배포한다. 구형 코드가 PENDING/새 enum을 처리하지 못하므로 단순 이전 이미지 rollback의 스키마 호환성을 먼저 검사한다. 신규 데이터 생성 후 자동 destructive down migration 금지.

## 7. 테이블 사전

아래 사전은 목표 DDL과 같은 컬럼 선언을 사용한다. 행간 ACTIVE 권한·최종 주돌봄자녀·MANUAL 반복 금지·출생연도 현재값 검사는 서비스 트랜잭션이 담당한다.

### app_user

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  login_key varchar(50) UNIQUE,
  display_name varchar(50),
  phone_number varchar(20) NOT NULL UNIQUE CHECK (phone_number ~ '^010[0-9]{8}$'),
  account_role varchar(10) NOT NULL CHECK (account_role IN ('PARENT','CHILD')),
  account_type varchar(10) NOT NULL DEFAULT 'DEMO' CHECK (account_type IN ('DEMO','REAL')),
  status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','DELETED')),
  password_hash text,
  timezone varchar(40) NOT NULL DEFAULT 'Asia/Seoul' CHECK (timezone = 'Asia/Seoul'),
  active_start_minute smallint NOT NULL DEFAULT 420 CHECK (active_start_minute BETWEEN 0 AND 1439),
  active_end_minute smallint NOT NULL DEFAULT 1320 CHECK (active_end_minute BETWEEN 1 AND 1440),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (active_start_minute < active_end_minute),
  CHECK (account_role <> 'CHILD' OR (display_name IS NOT NULL AND char_length(btrim(display_name)) BETWEEN 1 AND 50))
```

### auth_session

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash)=32),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > created_at)
```

### care_group

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_user_id uuid NOT NULL REFERENCES app_user(id),
  parent_relation varchar(10) CHECK (parent_relation IN ('MOTHER','FATHER')),
  parent_birth_year smallint CHECK (parent_birth_year BETWEEN 1900 AND 9999),
  parent_profile_completed_at timestamptz,
  parent_profile_completed_by uuid REFERENCES app_user(id),
  recipient_role varchar(12) NOT NULL DEFAULT 'RECIPIENT' CHECK (recipient_role='RECIPIENT'),
  name varchar(80) NOT NULL,
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK ((parent_profile_completed_at IS NULL AND parent_relation IS NULL AND parent_birth_year IS NULL AND parent_profile_completed_by IS NULL) OR (parent_profile_completed_at IS NOT NULL AND parent_relation IS NOT NULL AND parent_birth_year IS NOT NULL AND parent_profile_completed_by IS NOT NULL)),
  UNIQUE (recipient_user_id)
```

### group_member

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  user_id uuid NOT NULL REFERENCES app_user(id),
  role varchar(12) NOT NULL CHECK (role IN ('RECIPIENT','CAREGIVER')),
  priority smallint CHECK (priority IN (1,2)),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','LEFT')),
  joined_at timestamptz,
  left_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,user_id),
  UNIQUE (group_id,user_id,role),
  CHECK ((role='CAREGIVER' AND priority IS NOT NULL) OR (role='RECIPIENT' AND priority IS NULL)),
  CHECK ((status='LEFT') = (left_at IS NOT NULL)),
  CHECK (status <> 'ACTIVE' OR joined_at IS NOT NULL),
  CHECK (status <> 'PENDING' OR (role='CAREGIVER' AND priority=2 AND joined_at IS NULL))
```

### group_join_request

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL,
  user_id uuid NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELED')),
  decision_kind varchar(12) CHECK (decision_kind IN ('FIRST_JOIN','REVIEW')),
  decided_by uuid REFERENCES app_user(id),
  decided_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id,user_id) REFERENCES group_member(group_id,user_id),
  CHECK ((status='PENDING' AND decided_at IS NULL AND decided_by IS NULL AND decision_kind IS NULL)
    OR (status<>'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decision_kind IS NOT NULL))
```

### weekly_work_period

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
  start_minute smallint NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute smallint NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (start_minute < end_minute),
  UNIQUE (user_id,iso_weekday,start_minute,end_minute)
```

### availability_day

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  local_date date NOT NULL,
  mode varchar(12) NOT NULL CHECK (mode IN ('FULL','PARTIAL','UNAVAILABLE')),
  custom_intervals boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (user_id,local_date),
  UNIQUE (id,user_id),
  CHECK (NOT custom_intervals OR mode <> 'UNAVAILABLE')
```

### availability_interval

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  day_id uuid NOT NULL REFERENCES availability_day(id) ON DELETE CASCADE,
  start_minute smallint NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute smallint NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
  CHECK (start_minute < end_minute),
  UNIQUE (day_id,start_minute,end_minute)
```

### encounter

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  created_by uuid NOT NULL,
  record_type varchar(12) NOT NULL CHECK (record_type IN ('VISIT','DOCUMENT')),
  occurred_on date,
  hospital_name varchar(150),
  title varchar(150) NOT NULL,
  input_version integer NOT NULL DEFAULT 1 CHECK (input_version>0),
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, created_by) REFERENCES group_member(group_id, user_id)
```

### file_asset

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  uploaded_by uuid NOT NULL,
  purpose varchar(12) NOT NULL CHECK (purpose IN ('AUDIO','DOCUMENT')),
  object_key text UNIQUE,
  original_name varchar(255),
  media_type varchar(120) NOT NULL,
  byte_size bigint NOT NULL CHECK (byte_size>0),
  sha256 bytea NOT NULL CHECK (octet_length(sha256)=32),
  state varchar(20) NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','AVAILABLE','DELETE_PENDING','DELETED','FAILED')),
  expires_at timestamptz,
  deleted_at timestamptz,
  delete_attempts integer NOT NULL DEFAULT 0 CHECK (delete_attempts>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, uploaded_by) REFERENCES group_member(group_id, user_id),
  CHECK (purpose<>'AUDIO' OR expires_at IS NOT NULL),
  CHECK ((state='DELETED' AND object_key IS NULL AND deleted_at IS NOT NULL) OR (state<>'DELETED' AND object_key IS NOT NULL AND deleted_at IS NULL))
```

### encounter_source

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  source_type varchar(10) NOT NULL CHECK (source_type IN ('AUDIO','DOCUMENT')),
  document_type varchar(24) CHECK (document_type IN ('PRESCRIPTION','DIAGNOSIS','MEDICINE_BAG','LEGACY_UNCLASSIFIED')),
  CHECK ((source_type='AUDIO' AND document_type IS NULL) OR (source_type='DOCUMENT' AND document_type IS NOT NULL)),
  extracted_text text,
  text_version integer NOT NULL DEFAULT 0 CHECK (text_version>=0),
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','READY','FAILED')),
  removed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  UNIQUE (asset_id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id, asset_id) REFERENCES file_asset(group_id, id)
```

### processing_job

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  source_id uuid,
  job_type varchar(12) NOT NULL CHECK (job_type IN ('TRANSCRIBE','OCR','ANALYZE')),
  input_version integer NOT NULL CHECK (input_version>0),
  dedup_key varchar(160) NOT NULL UNIQUE,
  status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','OBSOLETE')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  provider varchar(60),
  model varchar(100),
  prompt_version varchar(40),
  error_code varchar(60),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,source_id) REFERENCES encounter_source(group_id,encounter_id,id),
  CHECK ((job_type='ANALYZE' AND source_id IS NULL) OR (job_type<>'ANALYZE' AND source_id IS NOT NULL)),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
```

### encounter_revision

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  input_version integer NOT NULL CHECK (input_version>0),
  job_id uuid NOT NULL UNIQUE,
  summary text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details)='object'),
  evidence jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence)='array'),
  is_current boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  UNIQUE (encounter_id,input_version),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,job_id) REFERENCES processing_job(group_id,encounter_id,id)
```

### extracted_item

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  item_key varchar(100) NOT NULL,
  item_type varchar(12) NOT NULL CHECK (item_type IN ('TASK','MEDICATION')),
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
  evidence jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence)='array'),
  review_state varchar(16) NOT NULL CHECK (review_state IN ('NEEDS_REVIEW','READY','APPLIED','DISMISSED','SUPERSEDED')),
  review_reasons text[] NOT NULL DEFAULT '{}',
  reviewed_by uuid,
  reviewed_at timestamptz,
  application_key varchar(160) UNIQUE,
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (revision_id,item_key),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,revision_id) REFERENCES encounter_revision(group_id,encounter_id,id),
  FOREIGN KEY (group_id, reviewed_by) REFERENCES group_member(group_id, user_id),
  CHECK ((reviewed_by IS NULL)=(reviewed_at IS NULL)),
  CHECK (review_state<>'APPLIED' OR (application_key IS NOT NULL AND applied_at IS NOT NULL))
```

### medication_order

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  source_item_id uuid NOT NULL UNIQUE,
  supersedes_id uuid,
  name varchar(150) NOT NULL,
  dose_text varchar(100) NOT NULL,
  frequency_text varchar(150) NOT NULL,
  starts_on date NOT NULL,
  ends_on date NOT NULL,
  instructions text,
  confirmed_by uuid NOT NULL,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, source_item_id) REFERENCES extracted_item(group_id, id),
  FOREIGN KEY (group_id, supersedes_id) REFERENCES medication_order(group_id, id),
  FOREIGN KEY (group_id, confirmed_by) REFERENCES group_member(group_id, user_id),
  CHECK (ends_on>=starts_on),
  CHECK (supersedes_id IS NULL OR supersedes_id<>id)
```

### task_series

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  kind varchar(16) NOT NULL CHECK (kind IN ('MEDICATION','HOSPITAL','EXAM','OTHER')),
  created_by uuid NOT NULL,
  source_item_id uuid UNIQUE,
  generation_key varchar(180) NOT NULL,
  creation_origin varchar(10) NOT NULL CHECK (creation_origin IN ('MANUAL','AI','REVIEW','LEGACY')),
  current_revision_no integer NOT NULL DEFAULT 1 CHECK (current_revision_no>0),
  stop_from_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (group_id,generation_key),
  FOREIGN KEY (group_id, created_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, source_item_id) REFERENCES extracted_item(group_id, id)
```

### task_series_revision

```sql
  series_id uuid NOT NULL,
  revision_no integer NOT NULL CHECK (revision_no>0),
  group_id uuid NOT NULL REFERENCES care_group(id),
  title varchar(150) NOT NULL,
  description text,
  recurrence varchar(10) NOT NULL CHECK (recurrence IN ('ONCE','DAILY','WEEKLY')),
  first_date date NOT NULL,
  last_date date,
  weekdays smallint[] NOT NULL DEFAULT '{}',
  local_time time NOT NULL CHECK (local_time < time '24:00'),
  duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 1440),
  effective_at timestamptz NOT NULL,
  changed_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (series_id,revision_no),
  UNIQUE (group_id,series_id,revision_no),
  FOREIGN KEY (group_id, series_id) REFERENCES task_series(group_id, id),
  FOREIGN KEY (group_id, changed_by) REFERENCES group_member(group_id, user_id),
  CHECK (last_date IS NULL OR last_date>=first_date),
  CHECK ((recurrence='ONCE' AND last_date IS NOT NULL AND last_date=first_date AND cardinality(weekdays)=0) OR (recurrence='DAILY' AND cardinality(weekdays)=0) OR (recurrence='WEEKLY' AND cardinality(weekdays) BETWEEN 1 AND 7 AND weekdays <@ ARRAY[1,2,3,4,5,6,7]::smallint[] AND array_position(weekdays,NULL) IS NULL))
```

### series_medication

```sql
  group_id uuid NOT NULL REFERENCES care_group(id),
  series_id uuid NOT NULL,
  revision_no integer NOT NULL,
  medication_id uuid NOT NULL,
  PRIMARY KEY (series_id,revision_no,medication_id),
  FOREIGN KEY (group_id,series_id,revision_no) REFERENCES task_series_revision(group_id,series_id,revision_no),
  FOREIGN KEY (group_id, medication_id) REFERENCES medication_order(group_id, id)
```

### task_occurrence

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  series_id uuid NOT NULL,
  revision_no integer NOT NULL,
  anchor_date date NOT NULL,
  title varchar(150) NOT NULL,
  description text,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','CANCELED')),
  assignee_user_id uuid,
  assignment_origin varchar(10) CHECK (assignment_origin IN ('AUTO','MANUAL','HANDOFF')),
  is_override boolean NOT NULL DEFAULT false,
  completed_by uuid,
  performed_by uuid,
  completed_at timestamptz,
  cancel_reason varchar(20) CHECK (cancel_reason IN ('USER_ONE','USER_FUTURE','RULE_CHANGED','RECORD_DELETED')),
  canceled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (series_id,anchor_date),
  FOREIGN KEY (group_id,series_id,revision_no) REFERENCES task_series_revision(group_id,series_id,revision_no),
  FOREIGN KEY (group_id, assignee_user_id) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, completed_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, performed_by) REFERENCES group_member(group_id, user_id),
  CHECK (ends_at>starts_at),
  CHECK ((assignee_user_id IS NULL)=(assignment_origin IS NULL)),
  CHECK ((status='COMPLETED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL AND performed_by IS NOT NULL) OR (status<>'COMPLETED' AND completed_at IS NULL AND completed_by IS NULL AND performed_by IS NULL)),
  CHECK ((status='CANCELED' AND canceled_at IS NOT NULL AND cancel_reason IS NOT NULL) OR (status<>'CANCELED' AND canceled_at IS NULL AND cancel_reason IS NULL))
```

### occurrence_medication

```sql
  group_id uuid NOT NULL REFERENCES care_group(id),
  occurrence_id uuid NOT NULL,
  medication_id uuid NOT NULL,
  PRIMARY KEY (occurrence_id,medication_id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, medication_id) REFERENCES medication_order(group_id, id)
```

### handoff_request

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  occurrence_id uuid NOT NULL,
  reason varchar(20) NOT NULL CHECK (reason IN ('NO_CANDIDATE','USER_REQUEST','AVAILABILITY','MEMBER_LEFT')),
  previous_assignee_id uuid,
  requested_by uuid,
  status varchar(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','CLOSED','EXPIRED')),
  accepted_by uuid,
  closed_at timestamptz,
  close_reason varchar(50),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, previous_assignee_id) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, requested_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, accepted_by) REFERENCES group_member(group_id, user_id),
  CHECK ((status='OPEN')=(closed_at IS NULL)),
  CHECK ((status='ACCEPTED')=(accepted_by IS NOT NULL))
```

### handoff_response

```sql
  group_id uuid NOT NULL,
  handoff_id uuid NOT NULL,
  user_id uuid NOT NULL,
  response varchar(10) NOT NULL DEFAULT 'DECLINED' CHECK (response='DECLINED'),
  responded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (handoff_id,user_id),
  FOREIGN KEY (group_id,handoff_id) REFERENCES handoff_request(group_id,id),
  FOREIGN KEY (group_id,user_id) REFERENCES group_member(group_id,user_id)
```

### audit_event

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  actor_user_id uuid,
  event_type varchar(60) NOT NULL,
  entity_type varchar(40) NOT NULL,
  entity_id uuid NOT NULL,
  before_data jsonb,
  after_data jsonb,
  request_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (group_id, actor_user_id) REFERENCES group_member(group_id, user_id)
```

### notification_preference

```sql
  user_id uuid PRIMARY KEY REFERENCES app_user(id),
  handoff_repeat varchar(10) NOT NULL DEFAULT 'DAILY' CHECK (handoff_repeat IN ('DAILY','ONCE')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
```

### push_subscription

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  endpoint text NOT NULL,
  endpoint_hash bytea NOT NULL CHECK (octet_length(endpoint_hash)=32),
  p256dh text NOT NULL,
  auth_secret text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id,user_id)
```

### notification_event

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  event_type varchar(30) NOT NULL,
  event_key varchar(200) NOT NULL UNIQUE,
  occurrence_id uuid,
  handoff_id uuid,
  encounter_id uuid,
  target_user_id uuid,
  expected_task_version bigint,
  due_at timestamptz NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','EXPANDED','CANCELED','FAILED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  lease_token uuid,
  lease_until timestamptz,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload)='object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, handoff_id) REFERENCES handoff_request(group_id, id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id, target_user_id) REFERENCES group_member(group_id, user_id),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
```

### notification

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  event_id uuid NOT NULL,
  user_id uuid NOT NULL,
  title varchar(120) NOT NULL,
  body varchar(300) NOT NULL,
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (id,user_id),
  UNIQUE (event_id,user_id),
  FOREIGN KEY (group_id, event_id) REFERENCES notification_event(group_id, id),
  FOREIGN KEY (group_id, user_id) REFERENCES group_member(group_id, user_id)
```

### notification_delivery

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES app_user(id),
  subscription_id uuid NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SENT','FAILED','CANCELED')),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  lease_token uuid,
  lease_until timestamptz,
  sent_at timestamptz,
  last_http_status integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (notification_id,subscription_id),
  FOREIGN KEY (notification_id,user_id) REFERENCES notification(id,user_id),
  FOREIGN KEY (subscription_id,user_id) REFERENCES push_subscription(id,user_id),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
  CHECK ((status='SENT')=(sent_at IS NOT NULL))
```

### idempotency_record

```sql
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  operation varchar(60) NOT NULL,
  client_key varchar(100) NOT NULL,
  request_hash bytea NOT NULL CHECK (octet_length(request_hash)=32),
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id,operation,client_key),
  CHECK (expires_at>created_at)
```

### schedule_guard

```sql
  id smallint PRIMARY KEY CHECK (id=1)
```

## 8. 목표 PostgreSQL DDL

아래는 빈 검증 DB용 전체 기준이다. 함께 제공한 family-care-target-schema-v1.3.sql과 동일하다. 기존 migration을 교체하지 않는다.

```sql
-- DB v1.3 목표 스키마: 빈 검증 DB 전용. 기존 V1 교체 금지.

-- Flyway migration에는 BEGIN/COMMIT을 별도 삽입하지 않는다.

CREATE TABLE app_user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  login_key varchar(50) UNIQUE,
  display_name varchar(50),
  phone_number varchar(20) NOT NULL UNIQUE CHECK (phone_number ~ '^010[0-9]{8}$'),
  account_role varchar(10) NOT NULL CHECK (account_role IN ('PARENT','CHILD')),
  account_type varchar(10) NOT NULL DEFAULT 'DEMO' CHECK (account_type IN ('DEMO','REAL')),
  status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','DELETED')),
  password_hash text,
  timezone varchar(40) NOT NULL DEFAULT 'Asia/Seoul' CHECK (timezone = 'Asia/Seoul'),
  active_start_minute smallint NOT NULL DEFAULT 420 CHECK (active_start_minute BETWEEN 0 AND 1439),
  active_end_minute smallint NOT NULL DEFAULT 1320 CHECK (active_end_minute BETWEEN 1 AND 1440),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (active_start_minute < active_end_minute),
  CHECK (account_role <> 'CHILD' OR (display_name IS NOT NULL AND char_length(btrim(display_name)) BETWEEN 1 AND 50))
);

CREATE TABLE auth_session (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash)=32),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > created_at)
);

CREATE INDEX idx_session_user ON auth_session(user_id, expires_at);

CREATE TABLE care_group (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_user_id uuid NOT NULL REFERENCES app_user(id),
  parent_relation varchar(10) CHECK (parent_relation IN ('MOTHER','FATHER')),
  parent_birth_year smallint CHECK (parent_birth_year BETWEEN 1900 AND 9999),
  parent_profile_completed_at timestamptz,
  parent_profile_completed_by uuid REFERENCES app_user(id),
  recipient_role varchar(12) NOT NULL DEFAULT 'RECIPIENT' CHECK (recipient_role='RECIPIENT'),
  name varchar(80) NOT NULL,
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK ((parent_profile_completed_at IS NULL AND parent_relation IS NULL AND parent_birth_year IS NULL AND parent_profile_completed_by IS NULL) OR (parent_profile_completed_at IS NOT NULL AND parent_relation IS NOT NULL AND parent_birth_year IS NOT NULL AND parent_profile_completed_by IS NOT NULL)),
  UNIQUE (recipient_user_id)
);

CREATE TABLE group_member (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  user_id uuid NOT NULL REFERENCES app_user(id),
  role varchar(12) NOT NULL CHECK (role IN ('RECIPIENT','CAREGIVER')),
  priority smallint CHECK (priority IN (1,2)),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','LEFT')),
  joined_at timestamptz,
  left_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,user_id),
  UNIQUE (group_id,user_id,role),
  CHECK ((role='CAREGIVER' AND priority IS NOT NULL) OR (role='RECIPIENT' AND priority IS NULL)),
  CHECK ((status='LEFT') = (left_at IS NOT NULL)),
  CHECK (status <> 'ACTIVE' OR joined_at IS NOT NULL),
  CHECK (status <> 'PENDING' OR (role='CAREGIVER' AND priority=2 AND joined_at IS NULL))
);

CREATE UNIQUE INDEX uq_group_one_recipient ON group_member(group_id) WHERE role='RECIPIENT';

CREATE INDEX idx_member_user ON group_member(user_id,status,group_id);


CREATE UNIQUE INDEX uq_member_current_group ON group_member(user_id)
 WHERE status IN ('ACTIVE','PENDING');

CREATE TABLE group_join_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL,
  user_id uuid NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELED')),
  decision_kind varchar(12) CHECK (decision_kind IN ('FIRST_JOIN','REVIEW')),
  decided_by uuid REFERENCES app_user(id),
  decided_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id,user_id) REFERENCES group_member(group_id,user_id),
  CHECK ((status='PENDING' AND decided_at IS NULL AND decided_by IS NULL AND decision_kind IS NULL)
    OR (status<>'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decision_kind IS NOT NULL))
);
CREATE UNIQUE INDEX uq_pending_join_user ON group_join_request(user_id) WHERE status='PENDING';
CREATE INDEX idx_join_group_status ON group_join_request(group_id,status,created_at,id);

CREATE TABLE weekly_work_period (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
  start_minute smallint NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute smallint NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (start_minute < end_minute),
  UNIQUE (user_id,iso_weekday,start_minute,end_minute)
);

CREATE TABLE availability_day (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  local_date date NOT NULL,
  mode varchar(12) NOT NULL CHECK (mode IN ('FULL','PARTIAL','UNAVAILABLE')),
  custom_intervals boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (user_id,local_date),
  UNIQUE (id,user_id),
  CHECK (NOT custom_intervals OR mode <> 'UNAVAILABLE')
);

CREATE INDEX idx_availability_date ON availability_day(local_date,user_id);

CREATE TABLE availability_interval (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  day_id uuid NOT NULL REFERENCES availability_day(id) ON DELETE CASCADE,
  start_minute smallint NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute smallint NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
  CHECK (start_minute < end_minute),
  UNIQUE (day_id,start_minute,end_minute)
);

CREATE TABLE encounter (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  created_by uuid NOT NULL,
  record_type varchar(12) NOT NULL CHECK (record_type IN ('VISIT','DOCUMENT')),
  occurred_on date,
  hospital_name varchar(150),
  title varchar(150) NOT NULL,
  input_version integer NOT NULL DEFAULT 1 CHECK (input_version>0),
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, created_by) REFERENCES group_member(group_id, user_id)
);

CREATE INDEX idx_encounter_timeline ON encounter(group_id,occurred_on DESC,created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE file_asset (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  uploaded_by uuid NOT NULL,
  purpose varchar(12) NOT NULL CHECK (purpose IN ('AUDIO','DOCUMENT')),
  object_key text UNIQUE,
  original_name varchar(255),
  media_type varchar(120) NOT NULL,
  byte_size bigint NOT NULL CHECK (byte_size>0),
  sha256 bytea NOT NULL CHECK (octet_length(sha256)=32),
  state varchar(20) NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','AVAILABLE','DELETE_PENDING','DELETED','FAILED')),
  expires_at timestamptz,
  deleted_at timestamptz,
  delete_attempts integer NOT NULL DEFAULT 0 CHECK (delete_attempts>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, uploaded_by) REFERENCES group_member(group_id, user_id),
  CHECK (purpose<>'AUDIO' OR expires_at IS NOT NULL),
  CHECK ((state='DELETED' AND object_key IS NULL AND deleted_at IS NOT NULL) OR (state<>'DELETED' AND object_key IS NOT NULL AND deleted_at IS NULL))
);

CREATE INDEX idx_asset_expiry ON file_asset(expires_at) WHERE state <> 'DELETED';

CREATE INDEX idx_asset_hash ON file_asset(group_id,sha256);

CREATE TABLE encounter_source (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  source_type varchar(10) NOT NULL CHECK (source_type IN ('AUDIO','DOCUMENT')),
  document_type varchar(24) CHECK (document_type IN ('PRESCRIPTION','DIAGNOSIS','MEDICINE_BAG','LEGACY_UNCLASSIFIED')),
  CHECK ((source_type='AUDIO' AND document_type IS NULL) OR (source_type='DOCUMENT' AND document_type IS NOT NULL)),
  extracted_text text,
  text_version integer NOT NULL DEFAULT 0 CHECK (text_version>=0),
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','READY','FAILED')),
  removed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  UNIQUE (asset_id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id, asset_id) REFERENCES file_asset(group_id, id)
);

CREATE INDEX idx_source_encounter ON encounter_source(encounter_id);

CREATE TABLE processing_job (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  source_id uuid,
  job_type varchar(12) NOT NULL CHECK (job_type IN ('TRANSCRIBE','OCR','ANALYZE')),
  input_version integer NOT NULL CHECK (input_version>0),
  dedup_key varchar(160) NOT NULL UNIQUE,
  status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','OBSOLETE')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  provider varchar(60),
  model varchar(100),
  prompt_version varchar(40),
  error_code varchar(60),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,source_id) REFERENCES encounter_source(group_id,encounter_id,id),
  CHECK ((job_type='ANALYZE' AND source_id IS NULL) OR (job_type<>'ANALYZE' AND source_id IS NOT NULL)),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);

CREATE INDEX idx_job_queue ON processing_job(status,available_at,lease_until);

CREATE TABLE encounter_revision (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  input_version integer NOT NULL CHECK (input_version>0),
  job_id uuid NOT NULL UNIQUE,
  summary text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details)='object'),
  evidence jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence)='array'),
  is_current boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (group_id,encounter_id,id),
  UNIQUE (encounter_id,input_version),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,job_id) REFERENCES processing_job(group_id,encounter_id,id)
);

CREATE UNIQUE INDEX uq_current_summary ON encounter_revision(encounter_id) WHERE is_current;

CREATE TABLE extracted_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  encounter_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  item_key varchar(100) NOT NULL,
  item_type varchar(12) NOT NULL CHECK (item_type IN ('TASK','MEDICATION')),
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
  evidence jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(evidence)='array'),
  review_state varchar(16) NOT NULL CHECK (review_state IN ('NEEDS_REVIEW','READY','APPLIED','DISMISSED','SUPERSEDED')),
  review_reasons text[] NOT NULL DEFAULT '{}',
  reviewed_by uuid,
  reviewed_at timestamptz,
  application_key varchar(160) UNIQUE,
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (revision_id,item_key),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id,encounter_id,revision_id) REFERENCES encounter_revision(group_id,encounter_id,id),
  FOREIGN KEY (group_id, reviewed_by) REFERENCES group_member(group_id, user_id),
  CHECK ((reviewed_by IS NULL)=(reviewed_at IS NULL)),
  CHECK (review_state<>'APPLIED' OR (application_key IS NOT NULL AND applied_at IS NOT NULL))
);

CREATE INDEX idx_review_pending ON extracted_item(group_id,review_state,created_at);

CREATE TABLE medication_order (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  source_item_id uuid NOT NULL UNIQUE,
  supersedes_id uuid,
  name varchar(150) NOT NULL,
  dose_text varchar(100) NOT NULL,
  frequency_text varchar(150) NOT NULL,
  starts_on date NOT NULL,
  ends_on date NOT NULL,
  instructions text,
  confirmed_by uuid NOT NULL,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, source_item_id) REFERENCES extracted_item(group_id, id),
  FOREIGN KEY (group_id, supersedes_id) REFERENCES medication_order(group_id, id),
  FOREIGN KEY (group_id, confirmed_by) REFERENCES group_member(group_id, user_id),
  CHECK (ends_on>=starts_on),
  CHECK (supersedes_id IS NULL OR supersedes_id<>id)
);

CREATE TABLE task_series (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  kind varchar(16) NOT NULL CHECK (kind IN ('MEDICATION','HOSPITAL','EXAM','OTHER')),
  created_by uuid NOT NULL,
  source_item_id uuid UNIQUE,
  generation_key varchar(180) NOT NULL,
  creation_origin varchar(10) NOT NULL CHECK (creation_origin IN ('MANUAL','AI','REVIEW','LEGACY')),
  current_revision_no integer NOT NULL DEFAULT 1 CHECK (current_revision_no>0),
  stop_from_date date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (group_id,generation_key),
  FOREIGN KEY (group_id, created_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, source_item_id) REFERENCES extracted_item(group_id, id)
);

CREATE TABLE task_series_revision (
  series_id uuid NOT NULL,
  revision_no integer NOT NULL CHECK (revision_no>0),
  group_id uuid NOT NULL REFERENCES care_group(id),
  title varchar(150) NOT NULL,
  description text,
  recurrence varchar(10) NOT NULL CHECK (recurrence IN ('ONCE','DAILY','WEEKLY')),
  first_date date NOT NULL,
  last_date date,
  weekdays smallint[] NOT NULL DEFAULT '{}',
  local_time time NOT NULL CHECK (local_time < time '24:00'),
  duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 1440),
  effective_at timestamptz NOT NULL,
  changed_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (series_id,revision_no),
  UNIQUE (group_id,series_id,revision_no),
  FOREIGN KEY (group_id, series_id) REFERENCES task_series(group_id, id),
  FOREIGN KEY (group_id, changed_by) REFERENCES group_member(group_id, user_id),
  CHECK (last_date IS NULL OR last_date>=first_date),
  CHECK ((recurrence='ONCE' AND last_date IS NOT NULL AND last_date=first_date AND cardinality(weekdays)=0) OR (recurrence='DAILY' AND cardinality(weekdays)=0) OR (recurrence='WEEKLY' AND cardinality(weekdays) BETWEEN 1 AND 7 AND weekdays <@ ARRAY[1,2,3,4,5,6,7]::smallint[] AND array_position(weekdays,NULL) IS NULL))
);

CREATE TABLE series_medication (
  group_id uuid NOT NULL REFERENCES care_group(id),
  series_id uuid NOT NULL,
  revision_no integer NOT NULL,
  medication_id uuid NOT NULL,
  PRIMARY KEY (series_id,revision_no,medication_id),
  FOREIGN KEY (group_id,series_id,revision_no) REFERENCES task_series_revision(group_id,series_id,revision_no),
  FOREIGN KEY (group_id, medication_id) REFERENCES medication_order(group_id, id)
);

CREATE TABLE task_occurrence (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  series_id uuid NOT NULL,
  revision_no integer NOT NULL,
  anchor_date date NOT NULL,
  title varchar(150) NOT NULL,
  description text,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','CANCELED')),
  assignee_user_id uuid,
  assignment_origin varchar(10) CHECK (assignment_origin IN ('AUTO','MANUAL','HANDOFF')),
  is_override boolean NOT NULL DEFAULT false,
  completed_by uuid,
  performed_by uuid,
  completed_at timestamptz,
  cancel_reason varchar(20) CHECK (cancel_reason IN ('USER_ONE','USER_FUTURE','RULE_CHANGED','RECORD_DELETED')),
  canceled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  UNIQUE (series_id,anchor_date),
  FOREIGN KEY (group_id,series_id,revision_no) REFERENCES task_series_revision(group_id,series_id,revision_no),
  FOREIGN KEY (group_id, assignee_user_id) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, completed_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, performed_by) REFERENCES group_member(group_id, user_id),
  CHECK (ends_at>starts_at),
  CHECK ((assignee_user_id IS NULL)=(assignment_origin IS NULL)),
  CHECK ((status='COMPLETED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL AND performed_by IS NOT NULL) OR (status<>'COMPLETED' AND completed_at IS NULL AND completed_by IS NULL AND performed_by IS NULL)),
  CHECK ((status='CANCELED' AND canceled_at IS NOT NULL AND cancel_reason IS NOT NULL) OR (status<>'CANCELED' AND canceled_at IS NULL AND cancel_reason IS NULL))
);

CREATE INDEX idx_occurrence_group_time ON task_occurrence(group_id,starts_at,id);

CREATE INDEX idx_occurrence_assignee ON task_occurrence(assignee_user_id,starts_at,ends_at) WHERE status <> 'CANCELED';

CREATE INDEX idx_occurrence_unassigned ON task_occurrence(group_id,starts_at) WHERE status='PENDING' AND assignee_user_id IS NULL;

CREATE TABLE occurrence_medication (
  group_id uuid NOT NULL REFERENCES care_group(id),
  occurrence_id uuid NOT NULL,
  medication_id uuid NOT NULL,
  PRIMARY KEY (occurrence_id,medication_id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, medication_id) REFERENCES medication_order(group_id, id)
);

CREATE TABLE handoff_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  occurrence_id uuid NOT NULL,
  reason varchar(20) NOT NULL CHECK (reason IN ('NO_CANDIDATE','USER_REQUEST','AVAILABILITY','MEMBER_LEFT')),
  previous_assignee_id uuid,
  requested_by uuid,
  status varchar(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACCEPTED','CLOSED','EXPIRED')),
  accepted_by uuid,
  closed_at timestamptz,
  close_reason varchar(50),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, previous_assignee_id) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, requested_by) REFERENCES group_member(group_id, user_id),
  FOREIGN KEY (group_id, accepted_by) REFERENCES group_member(group_id, user_id),
  CHECK ((status='OPEN')=(closed_at IS NULL)),
  CHECK ((status='ACCEPTED')=(accepted_by IS NOT NULL))
);

CREATE UNIQUE INDEX uq_open_handoff ON handoff_request(occurrence_id) WHERE status='OPEN';

CREATE INDEX idx_handoff_group ON handoff_request(group_id,status,created_at);


CREATE TABLE handoff_response (
  group_id uuid NOT NULL,
  handoff_id uuid NOT NULL,
  user_id uuid NOT NULL,
  response varchar(10) NOT NULL DEFAULT 'DECLINED' CHECK (response='DECLINED'),
  responded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (handoff_id,user_id),
  FOREIGN KEY (group_id,handoff_id) REFERENCES handoff_request(group_id,id),
  FOREIGN KEY (group_id,user_id) REFERENCES group_member(group_id,user_id)
);
CREATE INDEX idx_handoff_response_user ON handoff_response(user_id,handoff_id);

CREATE TABLE audit_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  actor_user_id uuid,
  event_type varchar(60) NOT NULL,
  entity_type varchar(40) NOT NULL,
  entity_id uuid NOT NULL,
  before_data jsonb,
  after_data jsonb,
  request_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (group_id, actor_user_id) REFERENCES group_member(group_id, user_id)
);

CREATE INDEX idx_audit_entity ON audit_event(group_id,entity_type,entity_id,created_at);

-- Deprecated: legacy data only, no runtime reads for reminder eligibility.
CREATE TABLE notification_preference (
  user_id uuid PRIMARY KEY REFERENCES app_user(id),
  handoff_repeat varchar(10) NOT NULL DEFAULT 'DAILY' CHECK (handoff_repeat IN ('DAILY','ONCE')),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE push_subscription (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  endpoint text NOT NULL,
  endpoint_hash bytea NOT NULL CHECK (octet_length(endpoint_hash)=32),
  p256dh text NOT NULL,
  auth_secret text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id,user_id)
);

CREATE INDEX idx_push_user ON push_subscription(user_id) WHERE enabled;

CREATE UNIQUE INDEX uq_enabled_endpoint ON push_subscription(endpoint_hash) WHERE enabled;

CREATE TABLE notification_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  event_type varchar(30) NOT NULL,
  event_key varchar(200) NOT NULL UNIQUE,
  occurrence_id uuid,
  handoff_id uuid,
  encounter_id uuid,
  target_user_id uuid,
  expected_task_version bigint,
  due_at timestamptz NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','EXPANDED','CANCELED','FAILED')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  lease_token uuid,
  lease_until timestamptz,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload)='object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id, occurrence_id) REFERENCES task_occurrence(group_id, id),
  FOREIGN KEY (group_id, handoff_id) REFERENCES handoff_request(group_id, id),
  FOREIGN KEY (group_id, encounter_id) REFERENCES encounter(group_id, id),
  FOREIGN KEY (group_id, target_user_id) REFERENCES group_member(group_id, user_id),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);

CREATE INDEX idx_notification_event_due ON notification_event(status,due_at,lease_until);

CREATE TABLE notification (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES care_group(id),
  event_id uuid NOT NULL,
  user_id uuid NOT NULL,
  title varchar(120) NOT NULL,
  body varchar(300) NOT NULL,
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id,id),
  UNIQUE (id,user_id),
  UNIQUE (event_id,user_id),
  FOREIGN KEY (group_id, event_id) REFERENCES notification_event(group_id, id),
  FOREIGN KEY (group_id, user_id) REFERENCES group_member(group_id, user_id)
);

CREATE INDEX idx_notification_inbox ON notification(user_id,created_at DESC,id);

CREATE INDEX idx_notification_unread ON notification(user_id) WHERE read_at IS NULL;

CREATE TABLE notification_delivery (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES app_user(id),
  subscription_id uuid NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SENT','FAILED','CANCELED')),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  lease_token uuid,
  lease_until timestamptz,
  sent_at timestamptz,
  last_http_status integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (notification_id,subscription_id),
  FOREIGN KEY (notification_id,user_id) REFERENCES notification(id,user_id),
  FOREIGN KEY (subscription_id,user_id) REFERENCES push_subscription(id,user_id),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
  CHECK ((status='SENT')=(sent_at IS NOT NULL))
);

CREATE INDEX idx_delivery_due ON notification_delivery(status,next_attempt_at,lease_until);

CREATE TABLE idempotency_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id),
  operation varchar(60) NOT NULL,
  client_key varchar(100) NOT NULL,
  request_hash bytea NOT NULL CHECK (octet_length(request_hash)=32),
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id,operation,client_key),
  CHECK (expires_at>created_at)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record(expires_at);

CREATE TABLE schedule_guard (
  id smallint PRIMARY KEY CHECK (id=1)
);

INSERT INTO schedule_guard(id) VALUES (1);

ALTER TABLE care_group ADD CONSTRAINT fk_recipient_member
 FOREIGN KEY (id,recipient_user_id,recipient_role)
 REFERENCES group_member(group_id,user_id,role) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE task_series ADD CONSTRAINT fk_current_series_revision
 FOREIGN KEY (group_id,id,current_revision_no)
 REFERENCES task_series_revision(group_id,series_id,revision_no) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at := clock_timestamp(); RETURN NEW; END $$;

CREATE TRIGGER trg_app_user_updated BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_care_group_updated BEFORE UPDATE ON care_group FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_group_member_updated BEFORE UPDATE ON group_member FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_weekly_work_period_updated BEFORE UPDATE ON weekly_work_period FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_availability_day_updated BEFORE UPDATE ON availability_day FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_encounter_updated BEFORE UPDATE ON encounter FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_file_asset_updated BEFORE UPDATE ON file_asset FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_encounter_source_updated BEFORE UPDATE ON encounter_source FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_processing_job_updated BEFORE UPDATE ON processing_job FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_extracted_item_updated BEFORE UPDATE ON extracted_item FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_task_series_updated BEFORE UPDATE ON task_series FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_task_occurrence_updated BEFORE UPDATE ON task_occurrence FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_handoff_request_updated BEFORE UPDATE ON handoff_request FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_notification_preference_updated BEFORE UPDATE ON notification_preference FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_push_subscription_updated BEFORE UPDATE ON push_subscription FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_notification_event_updated BEFORE UPDATE ON notification_event FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_notification_delivery_updated BEFORE UPDATE ON notification_delivery FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_medication_group_dates ON medication_order(group_id,starts_on,ends_on);

CREATE INDEX idx_series_source ON task_series(source_item_id);

CREATE INDEX idx_series_medication_reverse ON series_medication(medication_id);

CREATE INDEX idx_occurrence_medication_reverse ON occurrence_medication(medication_id);

-- Completed parent profile is immutable; service also blocks update endpoints.
CREATE FUNCTION protect_parent_profile() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.parent_profile_completed_at IS NOT NULL AND
   (NEW.parent_relation,NEW.parent_birth_year,NEW.parent_profile_completed_at,NEW.parent_profile_completed_by,NEW.recipient_user_id)
   IS DISTINCT FROM
   (OLD.parent_relation,OLD.parent_birth_year,OLD.parent_profile_completed_at,OLD.parent_profile_completed_by,OLD.recipient_user_id)
 THEN RAISE EXCEPTION 'PARENT_PROFILE_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_parent_profile_immutable BEFORE UPDATE ON care_group
 FOR EACH ROW EXECUTE FUNCTION protect_parent_profile();

CREATE FUNCTION protect_parent_name() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.account_role='PARENT' AND NEW.display_name IS DISTINCT FROM OLD.display_name
 AND EXISTS (SELECT 1 FROM care_group WHERE recipient_user_id=OLD.id AND parent_profile_completed_at IS NOT NULL)
 THEN RAISE EXCEPTION 'PARENT_PROFILE_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_parent_name_immutable BEFORE UPDATE ON app_user
 FOR EACH ROW EXECUTE FUNCTION protect_parent_name();

```

## 9. 검증 구분

이번 산출물의 정적 교차 검사는 파일 목록/enum/참조/JSON 예시 및 SQL 문법·제약 시험 보고를 따른다. 실제 Spring 구현·운영 migration·프론트·실기기 검증을 완료했다는 뜻이 아니다. 기존 102개 테스트 보고는 변경 전 구현에 대한 근거이며 신규 정책의 통과 근거로 재사용하지 않는다.
