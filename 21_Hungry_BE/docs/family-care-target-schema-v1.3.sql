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
