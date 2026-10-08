-- API v1.1 / DB v1.3 transition. V1 and demo V2 are immutable.
-- This migration deliberately fails when legacy rows cannot be migrated without guessing.

ALTER TABLE app_user ALTER COLUMN login_key DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN display_name DROP NOT NULL;
UPDATE app_user SET phone_number = regexp_replace(phone_number, '[^0-9]', '', 'g')
WHERE phone_number IS NOT NULL;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM app_user WHERE phone_number IS NULL OR phone_number !~ '^010[0-9]{8}$') THEN
    RAISE EXCEPTION 'V3 requires every user to have a normalized 010 phone number';
  END IF;
  IF EXISTS (SELECT phone_number FROM app_user GROUP BY phone_number HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'V3 found duplicate normalized phone numbers';
  END IF;
END $$;
ALTER TABLE app_user ALTER COLUMN phone_number SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_user_phone_normalized CHECK (phone_number ~ '^010[0-9]{8}$');
ALTER TABLE app_user ADD COLUMN account_role varchar(10);
UPDATE app_user u SET account_role = CASE WHEN EXISTS (
  SELECT 1 FROM care_group g WHERE g.recipient_user_id = u.id
) THEN 'PARENT' ELSE 'CHILD' END;
ALTER TABLE app_user ALTER COLUMN account_role SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_user_account_role CHECK (account_role IN ('PARENT','CHILD'));
ALTER TABLE app_user ADD CONSTRAINT ck_child_display_name CHECK (
  account_role <> 'CHILD' OR (display_name IS NOT NULL AND char_length(btrim(display_name)) BETWEEN 1 AND 50)
);

ALTER TABLE care_group ADD COLUMN parent_relation varchar(10)
  CHECK (parent_relation IN ('MOTHER','FATHER'));
ALTER TABLE care_group ADD COLUMN parent_birth_year smallint
  CHECK (parent_birth_year BETWEEN 1900 AND 9999);
ALTER TABLE care_group ADD COLUMN parent_profile_completed_at timestamptz;
ALTER TABLE care_group ADD COLUMN parent_profile_completed_by uuid REFERENCES app_user(id);
ALTER TABLE care_group ADD CONSTRAINT ck_parent_profile_complete CHECK (
  (parent_profile_completed_at IS NULL AND parent_relation IS NULL AND parent_birth_year IS NULL AND parent_profile_completed_by IS NULL)
  OR
  (parent_profile_completed_at IS NOT NULL AND parent_relation IS NOT NULL AND parent_birth_year IS NOT NULL AND parent_profile_completed_by IS NOT NULL)
);

ALTER TABLE group_member DROP CONSTRAINT group_member_priority_check;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM group_member WHERE priority IS NOT NULL AND priority NOT IN (1,2)) THEN
    RAISE EXCEPTION 'V3 found caregiver priority outside 1/2';
  END IF;
  IF EXISTS (
    SELECT user_id FROM group_member WHERE status='ACTIVE' GROUP BY user_id HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'V3 found a user connected to multiple active groups';
  END IF;
END $$;
ALTER TABLE group_member ADD CONSTRAINT group_member_priority_check CHECK (priority IN (1,2));
ALTER TABLE group_member DROP CONSTRAINT group_member_status_check;
ALTER TABLE group_member ADD CONSTRAINT group_member_status_check CHECK (status IN ('PENDING','ACTIVE','LEFT'));
ALTER TABLE group_member ALTER COLUMN joined_at DROP NOT NULL;
ALTER TABLE group_member ADD CONSTRAINT ck_member_active_joined CHECK (status <> 'ACTIVE' OR joined_at IS NOT NULL);
ALTER TABLE group_member ADD CONSTRAINT ck_member_pending CHECK (
  status <> 'PENDING' OR (role='CAREGIVER' AND priority=2 AND joined_at IS NULL)
);
CREATE UNIQUE INDEX uq_member_current_group ON group_member(user_id) WHERE status IN ('ACTIVE','PENDING');

CREATE TABLE group_join_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL,
  user_id uuid NOT NULL,
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELED')),
  decision_kind varchar(12) CHECK (decision_kind IN ('FIRST_JOIN','REVIEW')),
  decided_by uuid REFERENCES app_user(id),
  decided_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (group_id,id),
  FOREIGN KEY (group_id,user_id) REFERENCES group_member(group_id,user_id),
  CHECK ((status='PENDING' AND decided_at IS NULL AND decided_by IS NULL AND decision_kind IS NULL)
    OR (status<>'PENDING' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decision_kind IS NOT NULL))
);
CREATE UNIQUE INDEX uq_pending_join_user ON group_join_request(user_id) WHERE status='PENDING';
CREATE INDEX idx_join_group_status ON group_join_request(group_id,status,created_at,id);

ALTER TABLE encounter_source ADD COLUMN document_type varchar(24)
  CHECK (document_type IN ('PRESCRIPTION','DIAGNOSIS','MEDICINE_BAG','LEGACY_UNCLASSIFIED'));
UPDATE encounter_source SET document_type='LEGACY_UNCLASSIFIED' WHERE source_type='DOCUMENT';
ALTER TABLE encounter_source ADD CONSTRAINT ck_source_document_type CHECK (
  (source_type='AUDIO' AND document_type IS NULL) OR (source_type='DOCUMENT' AND document_type IS NOT NULL)
);

UPDATE task_series SET kind='OTHER' WHERE kind='PICKUP';
ALTER TABLE task_series DROP CONSTRAINT task_series_kind_check;
ALTER TABLE task_series ADD CONSTRAINT task_series_kind_check CHECK (kind IN ('MEDICATION','HOSPITAL','EXAM','OTHER'));
ALTER TABLE task_series ADD COLUMN creation_origin varchar(10) NOT NULL DEFAULT 'LEGACY'
  CHECK (creation_origin IN ('MANUAL','AI','REVIEW','LEGACY'));

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

COMMENT ON TABLE notification_preference IS 'Deprecated legacy data; not used for v1.1 reminder eligibility';

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
