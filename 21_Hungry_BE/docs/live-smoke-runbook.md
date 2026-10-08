# 실제 AI·Web Push opt-in 검증

갱신: 2026-10-08, Asia/Seoul. 일반 테스트와 CI에서는 외부 호출을 하지 않는다.

## 설정 전달

Spring Boot는 `.env`를 자동으로 읽지 않는다.

- IntelliJ: Run Configuration의 Active profiles와 Environment variables에 값을 넣는다. `.env` 파일을 만들기만 해서는 전달되지 않는다.
- PowerShell: 현재 프로세스에 `$env:OPENAI_API_KEY='<local secret>'`처럼 설정한 뒤 같은 창에서 `gradlew.bat bootRun`을 실행한다. 출력·히스토리 공유를 피하고 종료 후 `Remove-Item Env:OPENAI_API_KEY`로 지운다.
- Compose: `docker compose --env-file .env -f compose.prod.yaml ...`은 치환값을 읽으며, 컨테이너 전달 항목은 `compose.prod.yaml`의 `environment`에 명시돼 있다. `.env`는 Git 제외다.

AI에는 `OPENAI_API_KEY`, `AI_WORKER_ENABLED=true`, 모델 3종, `OPENAI_TIMEOUT`, lease/concurrency/max attempts/max output tokens가 필요하다. 알림에는 `NOTIFICATION_EVENT_WORKER_ENABLED`; 실제 전송에는 추가로 `PUSH_WORKER_ENABLED=true`와 VAPID 3종이 필요하다. `VAPID_SUBJECT`는 운영자가 제공한 실제 `mailto:` 또는 HTTPS URI만 쓴다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\vapid-keys.ps1 `
  -Subject 'mailto:REAL-OPERATOR@example.com'
```

위 명령은 `secrets/vapid.env`를 만들고 P-256 공개/비공개 키의 형식과 짝을 검증한다. 기존 파일은 검증만 하며 덮어쓰지 않고 private key를 출력하지 않는다.

## OpenAI 1회 smoke

가상 인물·가상 약만 포함한 한국어 WAV, 이미지, PDF를 준비한다. 이미지/PDF에는 예를 들어 `가상약A 5mg, 하루 2회, 2026-10-10~2026-10-12, 08:00/20:00`와 명확한 일반 업무를 넣고 PDF는 페이지별 고유 문구를 둔다. 실제 환자 자료를 사용하지 않는다.

저장소의 재현 가능한 합성 이미지/PDF는 다음처럼 만든다. Windows 한국어 TTS가 없는 환경에서는 `-SkipAudio`를 쓰고, WAV는 실제 PCM WAV 또는 opt-in OpenAI TTS 결과를 사용한다. 스트리밍 WAV의 RIFF/data 길이가 `0xffffffff`이면 실제 byte 길이로 header를 마감한 뒤 서버 parser로 검증해야 하며 확장자/MIME 변경만으로 대체하지 않는다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-openai-live-fixtures.ps1 -SkipAudio
```

```powershell
.\scripts\openai-live-api-smoke.ps1 -BaseUrl https://api.example.com `
  -BearerToken $env:SMOKE_BEARER_TOKEN -GroupId '<group UUID>' `
  -WavPath .\fixtures\synthetic-ko.wav -ImagePath .\fixtures\synthetic-rx.png `
  -PdfPath .\fixtures\synthetic-pages.pdf -ConfirmBodyPath .\fixtures\confirm-r02.json
```

실행 전 같은 encounter가 있으면 `-EncounterId`로 E03/jobs를 먼저 조회해 불필요한 재호출을 피한다. 결과에서 provider/model, input/output token, elapsedMs 로그를 기록하되 원문·약명·키는 기록하지 않는다. E03/R01에서 약명·용량·횟수·날짜·시간, evidence의 sourceId/textVersion/page/quote/codepoint offset을 원본과 사람이 대조한다. refusal/incomplete/입력 한도는 명시 오류이고 동일 입력 자동 재시도 대상이 아니다. R02 뒤 처방/series/occurrence가 1회만 생기고 명확한 일반 TASK만 자동 적용됐는지 멱등 replay와 함께 확인한다.

2026-10-08 로컬 실행에서는 WAV 전사, JPEG/PDF OCR, page 1·2와 quote/codepoint 근거, E03, R02, 처방 1개·복약 series 2개·occurrence 6개, 별도 명확 TASK의 자동 `APPLIED`와 occurrence 1개까지 확인했다. 키는 `secrets/openai.env`에서 프로세스에만 주입했으며 파일은 Git 제외 상태다. 성공 호출의 세부 token/지연은 `progress.md`에 기록했다.

## Web Push 1회 smoke

`scripts/web-push-smoke`를 localhost 또는 HTTPS에서 열어 사용자 버튼으로 N05/N06을 수행한다. 알림 사건을 만든 뒤 다음을 서로 구분해 기록한다.

로컬 실제 provider smoke 준비:

```powershell
# 최초 1회: 기존 hungry DB를 건드리지 않는 격리 smoke DB
docker compose exec -T postgres createdb -U hungry -O hungry hungry_push_smoke

# 터미널 1: 검증된 secrets/vapid.env를 읽어 worker를 켠 로컬 API
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-web-push-smoke.ps1

# 터미널 2: localhost 보안 컨텍스트로 검증 페이지 제공
python -m http.server 4173 --directory .\scripts\web-push-smoke
```

브라우저에서 `http://localhost:4173`을 연다. API base는 `http://localhost:18082`를 유지하고 `전화번호 로그인(A06)`을 누른 뒤 `권한 요청 및 현재 기기 구독(N05/N06)`을 누른다. 브라우저 알림 권한은 사용자가 직접 허용해야 한다. token·endpoint·p256dh·auth는 화면 로그, URL, localStorage에 기록하지 않는다. N05는 Bearer 인증이 필수다.

production 값은 frontend `https://knowone-eight.vercel.app`, API `https://api.gaebalmani.shop`, CORS Origin `https://knowone-eight.vercel.app`, VAPID subject `mailto:js48765348@gmail.com`이다. 로컬 smoke의 localhost Origin은 운영 CORS에 추가하지 않는다.

1. provider HTTP 2xx 및 delivery `SENT` 여부
2. 실제 브라우저 알림 표시 여부
3. 클릭 시 Service Worker의 `eventId` tag와 인증 N01→target 재조회 성공 여부
4. provider 수락/표시/클릭만으로 `readAt`이 바뀌지 않고 N02에서만 바뀌는지
5. 계정 전환 시 현재 기기 N08, N07에서 다른 기기 유지, 탈퇴·삭제·완료 후 대기 delivery 취소

provider 2xx는 기기 표시나 읽음의 증거가 아니다. 실제 subscription/기기가 없으면 1~5를 BLOCKED로 남긴다.
