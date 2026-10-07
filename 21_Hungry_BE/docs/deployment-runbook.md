# 운영 배포·롤백 runbook

갱신: 2026-10-08, Asia/Seoul. 이 문서는 준비 절차이며 이번 작업에서 운영 배포·재시작은 수행하지 않았다.

## 사전 조건

- 프론트는 Vercel, API/PostgreSQL/Nginx는 EC2로 분리한다. 외부에는 Nginx 443만 열고 API 8080은 loopback, PostgreSQL은 포트를 publish하지 않는다.
- `.env.prod.example`을 서버의 `.env`로 복사해 mode 600으로 두고 실제 DB password, CORS Origin, preview secret, OpenAI/VAPID 값을 secret manager 또는 서버 파일로 주입한다. demo DB/V2 seed를 prod에 사용하지 않는다.
- `FILE_STORAGE_ROOT`는 컨테이너 `/data/private-files`의 named volume이다. Dockerfile의 non-root `app` 사용자가 소유하며 재시작에도 유지된다.
- Nginx는 `/api/` 경로를 보존하고 120 MiB request/120초 proxy timeout을 사용한다. Spring CORS가 허용 Origin의 OPTIONS와 인증 오류에도 헤더를 붙인다.
- 확정 production frontend Origin은 `https://knowone-eight.vercel.app`, API domain은 `api.gaebalmani.shop`, DNS A 레코드는 `52.35.249.169`다. `CORS_ALLOWED_ORIGINS`에는 scheme/host가 정확히 일치하는 frontend Origin만 넣는다.
- VAPID subject는 `mailto:js48765348@gmail.com`이다. `secrets/vapid.env`의 검증된 키 쌍을 서버 secret 저장소 또는 mode 600 `.env`에 옮기되 private key를 명령 출력·CI 로그에 남기지 않는다.

## 배포

```bash
cd /home/ec2-user/21_Hungry/21_Hungry_BE
chmod 600 .env
RELEASE_ID="$(git rev-parse --verify HEAD)" ENV_FILE="$PWD/.env" bash scripts/deploy.sh "$PWD"
docker compose --env-file .env -f compose.prod.yaml ps
curl -fsS https://api.gaebalmani.shop/actuator/health/readiness
```

배포 스크립트는 commit SHA tag 이미지를 새로 만들고 readiness 실패 시 직전 실행 이미지 ID로 API만 복구한다. DB와 파일 volume은 교체하지 않는다. 여러 API replica는 같은 큐를 실행할 수 있지만 SKIP LOCKED/lease/fencing으로 중복 반영을 막는다. 단일 서버 배포는 `backend-production` concurrency로 직렬화한다.

CI는 PostgreSQL에서 `test bootJar`만 실행하고 AI/notification/push worker를 명시적으로 끈다. 실제 외부 호출은 opt-in smoke에서만 수행한다.

## 롤백 판단

- 애플리케이션/이미지 롤백과 DB 롤백은 별개다. 새 migration이 additive이고 이전 이미지가 새 schema를 허용할 때만 이미지 롤백한다.
- destructive/rename migration 뒤에는 이전 이미지를 기계적으로 실행하지 않는다. forward-fix 또는 검증된 DB restore를 선택한다.
- 자동 readiness 복구 후에도 Nginx access/error log, API safe log, `/actuator/health/readiness`를 확인한다. 2026-10-08 확인 당시 DNS는 지정 IP로 정상 해석됐지만 80/443은 모두 연결되지 않아 인증서와 Nginx 동작은 미확인이다. 실제 배포 전 security group/firewall, Nginx, HTTPS 인증서와 backup 보존 위치를 확인해야 한다.
