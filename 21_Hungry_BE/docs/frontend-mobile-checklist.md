# 프론트·모바일 연결 체크리스트

갱신: 2026-10-08, Asia/Seoul.

- Vercel production의 `VITE_API_BASE_URL`은 `/api/v1`을 붙이지 않은 `https://api.gaebalmani.shop`으로 둔다. API의 `CORS_ALLOWED_ORIGINS`에는 production Origin `https://knowone-eight.vercel.app`을 정확히 넣으며 `*`를 쓰지 않는다.
- 보호 API는 `Authorization: Bearer <opaque token>`을 사용한다. 명령 API는 새 `Idempotency-Key` UUID를 보내고 replay에는 같은 key와 같은 body를 쓴다. 변경 요청의 `expectedVersion`, `expectedInputVersion`, `expectedSeriesVersion`과 preview 응답의 `previewToken`을 API 명세대로 보낸다.
- E09 파일은 `<img src="API URL">`처럼 직접 노출하지 않는다. 인증 `fetch`, `response.blob()`, `URL.createObjectURL()`로 표시하고 화면 이탈·로그아웃·계정 전환 시 `URL.revokeObjectURL()`과 메모리 상태를 정리한다.
- 녹음 업로드는 현재 실제 WAV parser를 통과한 RIFF/WAVE PCM 표본만 `audio/wav` 또는 `audio/x-wav`로 보낸다. 확장자나 MIME만 바꾸는 것은 변환이 아니다.
- iPhone Safari/PWA와 Android Chrome에서 각각 실제 녹음 2개(짧은 10초, 1분 이상)를 수집해 `ffprobe -hide_banner <file>`로 컨테이너·코덱·sample rate·channel을 기록한다. 원본을 보존하고 서버 로그나 저장소에 실제 건강정보를 넣지 않는다. 표본이 WAV가 아니면 제한 시간·크기·임시 파일 `finally` 삭제를 갖춘 검증된 transcoder를 별도 도입하기 전까지 BLOCKED다.
- Service Worker/Workbox는 `/api/v1/encounters`, `/api/v1/sources`, 인증 파일 응답, `Cache-Control: no-store` 응답을 runtime/precache에서 제외한다. 로그아웃 시 Cache Storage·IndexedDB의 사용자별 API 데이터도 정리한다.
- Push는 사용자 버튼에서 권한을 요청하고 N05→PushManager→N06 순서로 등록한다. 계정 전환/로그아웃은 현재 subscription의 N08 성공 후 브라우저 `unsubscribe()`를 수행한다. 다른 기기의 N07 항목은 유지한다.
- 최소 페이지는 `scripts/web-push-smoke`에 있다. `npx --yes http-server scripts/web-push-smoke -p 4173 -c-1`로 localhost에서 열고 CORS에 `http://localhost:4173`을 추가한다. 토큰·subscription secret은 저장/URL/로그에 넣지 않는다.

현재 제품 프론트는 화면 골격 단계이며 API client, 인증 저장 정책, Push Service Worker 연결은 확인되지 않았다. 담당자 변경을 덮어쓰지 않았고 이 저장소에는 검증 체크리스트만 제공한다.
