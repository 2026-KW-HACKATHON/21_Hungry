-- local/demo 프로필 전용 가상 데이터. 운영 Flyway location에는 포함하지 않는다.
-- 실제 인물의 이름·전화번호·의료정보를 사용하지 않는다.

INSERT INTO app_user (id, login_key, display_name, phone_number, account_type)
VALUES
  ('00000000-0000-4000-8000-000000000001', 'demo-recipient', '가상 돌봄대상', '010-0000-0001', 'DEMO'),
  ('00000000-0000-4000-8000-000000000002', 'demo-caregiver-1', '가상 첫째', '010-0000-0002', 'DEMO'),
  ('00000000-0000-4000-8000-000000000003', 'demo-caregiver-2', '가상 둘째', '010-0000-0003', 'DEMO'),
  ('00000000-0000-4000-8000-000000000004', 'demo-caregiver-3', '가상 셋째', '010-0000-0004', 'DEMO');

INSERT INTO care_group (id, recipient_user_id, name)
VALUES ('10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', '가상 가족 돌봄 공동체');

INSERT INTO group_member (id, group_id, user_id, role, priority)
VALUES
  ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'RECIPIENT', NULL),
  ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', 'CAREGIVER', 1),
  ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000003', 'CAREGIVER', 1),
  ('20000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000004', 'CAREGIVER', 2);

INSERT INTO notification_preference (user_id)
SELECT id FROM app_user WHERE account_type = 'DEMO';

INSERT INTO weekly_work_period (user_id, iso_weekday, start_minute, end_minute)
SELECT caregiver.id, weekday, 540, 1080
FROM app_user caregiver
CROSS JOIN generate_series(1, 5) AS weekday
WHERE caregiver.login_key IN ('demo-caregiver-1', 'demo-caregiver-2', 'demo-caregiver-3');

INSERT INTO availability_day (user_id, local_date, mode, custom_intervals)
SELECT
  caregiver.id,
  current_date + day_offset,
  CASE
    WHEN day_offset % 7 = 6 THEN 'UNAVAILABLE'
    WHEN (day_offset + caregiver_index) % 3 = 0 THEN 'PARTIAL'
    ELSE 'FULL'
  END,
  day_offset % 7 <> 6 AND (day_offset + caregiver_index) % 3 = 0
FROM (
  VALUES
    ('00000000-0000-4000-8000-000000000002'::uuid, 0),
    ('00000000-0000-4000-8000-000000000003'::uuid, 1),
    ('00000000-0000-4000-8000-000000000004'::uuid, 2)
) AS caregiver(id, caregiver_index)
CROSS JOIN generate_series(0, 13) AS day_offset;

INSERT INTO availability_interval (day_id, start_minute, end_minute)
SELECT
  day.id,
  CASE user_account.login_key
    WHEN 'demo-caregiver-1' THEN 540
    WHEN 'demo-caregiver-2' THEN 780
    ELSE 600
  END,
  CASE user_account.login_key
    WHEN 'demo-caregiver-1' THEN 780
    WHEN 'demo-caregiver-2' THEN 1080
    ELSE 960
  END
FROM availability_day day
JOIN app_user user_account ON user_account.id = day.user_id
WHERE day.mode = 'PARTIAL';
