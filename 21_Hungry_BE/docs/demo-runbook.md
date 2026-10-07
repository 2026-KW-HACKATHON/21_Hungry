# 통합 시연 순서와 실패 복구

갱신: 2026-10-08, Asia/Seoul. 실제 개인정보 대신 완전한 창작 자료만 사용한다.

## 시연 순서

1. A01/A02로 demo 계정 조회·로그인 후 G01/G02로 공동체와 ACTIVE 멤버십을 확인한다.
2. V01→V02→V03, V04로 활동/근무 가능 시간을 preview 후 저장한다. 같은 `Idempotency-Key` replay와 다른 body 409를 함께 보인다.
3. E01 진료 생성 후 E06 WAV, E05 이미지/PDF를 순서대로 업로드한다. 각 변경의 최신 `version`/`inputVersion`을 다음 요청에 전달한다.
4. E11/E12로 job을 관찰하고 E03에서 current revision, source, provider metadata를 확인한다. 원본 자료와 전사/OCR/페이지·quote·codepoint 근거를 사람이 대조한다.
5. R01의 확인 필요 항목을 조회하고 R02로 가상 처방을 확정한다. R04 처방 조회 후 T03/T04로 반복 복약 일정과 snapshot을 확인한다. 명확한 일반 TASK 자동 적용은 한 번만 생성됐는지 확인한다.
6. T09 담당 지정, T10 인계, 다른 ACTIVE 계정의 T11 수락, T12 완료, T13 재열기를 순서대로 수행한다.
7. notification event worker를 실행해 N01 알림함을 확인하고 N02에서만 readAt을 설정한다. 실제 Push 환경이면 provider 2xx·기기 표시·click을 별도 기록한다.
8. E14 preview 후 E15로 진료를 삭제한다. 즉시 E03/E07/E09가 404인지, worker 처리 뒤 object key/민감 text가 제거됐는지, 공유 복약 업무는 남고 단독 연결 업무만 취소됐는지 확인한다.

자동 PostgreSQL 테스트는 타 공동체/LEFT 차단, 개인 알림·구독 소유권, 멱등 replay/body 충돌, stale 결과 차단, lease 회수/fencing, 삭제·완료·탈퇴 후 대기 알림 차단, 음성 만료·삭제 재시도, 공유 복약 업무 보존을 검증한다. 외부 모델 정확도와 실제 Push 표시는 키/기기 없이는 별도 BLOCKED다.

## 실패 복구

- 업로드/명령 응답을 받지 못했으면 새 key로 재전송하지 말고 같은 `Idempotency-Key`와 동일 body로 replay한다. body를 바꿔야 하면 현재 detail/version을 다시 읽고 새 key를 쓴다.
- AI가 `FAILED`면 E12의 safe error code와 `canRetry`를 확인한다. transient만 E13으로 재시도하며 입력한도/refusal/incomplete/schema 오류는 자료·설정을 수정한 새 inputVersion으로 처리한다. 기존 성공 encounter에는 `-EncounterId` smoke 복구 경로를 쓴다.
- stale version/preview는 detail을 다시 조회하고 preview를 재발급한다. 기존 token을 우회하지 않는다.
- Push provider 2xx인데 표시되지 않으면 delivery를 재전송해 해결됐다고 간주하지 않는다. OS 권한, Service Worker, subscription 만료, N07을 확인하고 새 구독은 N08 후 N06으로 교체한다.
- 배포 readiness 실패는 `deployment-runbook.md`의 직전 이미지 복구를 따르고, migration 비호환이면 이미지 롤백 대신 forward-fix/격리 DB restore를 선택한다.
- 시연 데이터 삭제 실패는 `DELETE_PENDING`과 delete attempts를 확인해 worker 재시도를 허용한다. 파일을 수동 공개 경로로 옮기거나 DB key만 지워 성공처럼 만들지 않는다.
