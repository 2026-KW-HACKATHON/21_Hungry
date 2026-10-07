# PostgreSQL·비공개 파일 백업/복구 runbook

갱신: 2026-10-08, Asia/Seoul.

## 일관된 백업

가장 단순하고 검증 가능한 방식은 API를 먼저 중지해 쓰기와 모든 worker를 멈춘 뒤 DB와 파일을 백업하는 것이다. Nginx는 maintenance 응답으로 전환하고 다음 순서를 지킨다.

```bash
umask 077
mkdir -p backups/2026-10-08T010000Z
docker compose --env-file .env -f compose.prod.yaml stop api
docker compose --env-file .env -f compose.prod.yaml exec -T postgres \
  pg_dump -U "$DB_USERNAME" -d hungry -Fc > backups/2026-10-08T010000Z/database.dump
docker run --rm -v 21_hungry_be_hungry-private-files:/source:ro \
  -v "$PWD/backups/2026-10-08T010000Z:/backup" alpine:3.22 \
  tar -C /source -czf /backup/private-files.tar.gz .
sha256sum backups/2026-10-08T010000Z/* > backups/2026-10-08T010000Z/SHA256SUMS
docker compose --env-file .env -f compose.prod.yaml start api
```

DB dump와 파일 archive는 같은 write-stop 구간에서 만들어야 한다. 백업은 건강정보와 인증 hash를 포함할 수 있으므로 mode 600/700, 암호화 저장, 최소 접근권한, 별도 보존·파기 정책을 적용하고 Git에 넣지 않는다.

## 격리 복원 리허설

운영 project/volume을 덮어쓰지 않고 별도 project name과 loopback 포트를 사용한다.

```powershell
$env:RESTORE_DB_PASSWORD='<temporary secret>'
$env:RESTORE_PREVIEW_SIGNING_SECRET='<32+ byte temporary secret>'
$env:API_IMAGE='21-hungry-api:<tested commit sha>'
docker compose -p hungry-restore -f compose.restore.yaml up -d --wait postgres
docker compose -p hungry-restore -f compose.restore.yaml cp .\backups\<stamp>\database.dump postgres:/tmp/database.dump
docker compose -p hungry-restore -f compose.restore.yaml exec -T postgres pg_restore -U restore_user -d hungry_restore --clean --if-exists /tmp/database.dump
docker run --rm -v hungry-restore_restore-private-files:/target -v "${PWD}\backups\<stamp>:/backup:ro" alpine:3.22 sh -c "tar -C /target -xzf /backup/private-files.tar.gz"
docker compose -p hungry-restore -f compose.restore.yaml up -d --wait api
Invoke-RestMethod http://localhost:18080/actuator/health/readiness
```

복원 API는 `SCHEDULING_ENABLED=false`, AI/event/push worker false다. 복원 후 demo login 또는 보존된 test 계정으로 인증하고 공동체/진료를 조회한 뒤, DB `file_asset.object_key`가 `/data/private-files/<key>`에 존재하는지 확인하고 E09 인증 fetch의 hash를 원본과 비교한다. 원본 DB row 수와 핵심 FK orphan 검사를 함께 기록한다.

검증 후 `docker compose -p hungry-restore -f compose.restore.yaml down -v`로 **hungry-restore project의 임시 volume만** 제거한다. 운영 project나 volume에는 `down -v`를 실행하지 않는다.
