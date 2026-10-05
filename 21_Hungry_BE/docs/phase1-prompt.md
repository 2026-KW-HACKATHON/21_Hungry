# Codex CLI 1단계 작업 요청

저장소 루트의 AGENTS.md와 docs/progress.md, decisions.md, implementation-plan.md를 읽고 기준 PRD v0.14·DB v1.2·API v1.0을 확인해라.

이번 요청은 **1단계 로컬 실행·스키마·공통 기반**까지만이다. 기존 레포/코드/팀 프론트 구성을 먼저 확인하고 중복 앱을 생성하지 마라. 구현 전에 변경 범위·파일·검증 계획을 짧게 알려라.

1. Java21·단일 Spring Boot·Gradle Wrapper·Spring Security·JPA·PostgreSQL·Flyway 기반을 마련한다. 정확한 버전은 공식 호환성을 확인하고 기록한다.
2. DB 설계의 초기 DDL을 Flyway V1으로 옮긴다. 기존 적용 migration이 있으면 V1을 덮어쓰지 말고 현재 상태에 맞춘다. ddl-auto=validate, 실제 PostgreSQL로 빈 DB migration과 앱 실행을 검사한다.
3. 4개 시연 계정·공동체·멤버십·schedule_guard 등 필수 시드는 local/demo 전용으로 분리한다. 실제 데이터가 있는 환경에서 migration으로 강제 시연 계정을 주입하지 않는다.
4. local/test/prod 설정·비밀값 없는 .env.example·로컬 PostgreSQL compose·공통 API 오류 envelope·requestId·Clock·CORS 설정 기반을 만든다. 아직 로그인/업무 API는 구현하지 않는다. 기본 보안은 명시 공개 경로 외 보호하며 검증용 상태 endpoint는 업무 API 60개와 구분해 기록한다.
5. 빌드/테스트 CI와 Dockerfile·배포 설정 초안을 마련한다. 기존 CI가 있으면 확장한다. 서버 자격증명 없이 실제 배포/인증서 발급/푸시를 시도하지 않는다.
6. README에 실행·테스트·설정 방법을 쓴다. 외부 HTTPS/CORS·녹음·푸시 기술 시험의 필요한 입력과 BLOCKED 상태를 기록한다.
7. 실제 실행한 검사만 보고하고 docs/progress.md 갱신. 추가 DB 설계 변경·JWT·refresh token·프론트 구현·2단계 인증 API는 하지 않는다. 커밋/푸시는 이 요청에 포함하지 않는다.

완료 보고: 변경 파일, 선택한 버전, 실행 명령/결과, migration 검증, 미확보 설정/미실행 검증, 다음 단계 범위.
