# 가족 돌봄 서비스 개발 지침

이 파일은 저장소 전체에 적용한다. 사용자 지시와 기존 코드의 맥락을 먼저 확인한다. 0단계는 문서 준비이며 애플리케이션을 구현하거나 배포한 상태가 아니다.

## 기준 문서와 읽는 순서

1. `docs/progress.md`: 현재 단계와 다음 작업.
2. `docs/decisions.md`: 원문 차이의 처리 및 이번 개발의 기본값.
3. `docs/implementation-plan.md`: 단계 범위와 완료 기준.
4. `docs/family-care-PRD-v0.15.md`: 제품·업무 요구사항.
5. `docs/family-care-DB-design-v1.3.md`: 30개 목표 테이블·무결성·트랜잭션 설계.
6. `docs/family-care-API-spec-v1.2.md`: HTTP·DTO·오류·검증 계약. v1.1을 대체하며 E06 녹음 계약을 정정한다.
7. `docs/remaining-work-20261008.md`: 신규 계약의 구현 순서와 집중 회귀 시나리오.
8. AI 작업 시 `docs/processing-contract-v1.0.md`.

각 문서는 담당 영역의 기준이다. 단순한 문서 우선순위로 의미 충돌을 숨기지 않는다. decisions.md에 명시된 좁은 차이만 해당 결정을 적용한다. 새로운 업무 정책 충돌은 기록하고 해당 부분만 보류한다. 독립적으로 진행 가능한 일은 계속한다. 기준 원문은 보존하고 변경 필요 시 새 버전과 변경 사유를 남긴다.

## 작업 범위

- 현재 요청받은 단계만 구현한다. 구현 전 범위·변경 파일·검증 계획을 짧게 정리한다.
- 한 기능이 API부터 DB까지 실제로 동작하도록 끝낸다. 없는 기능을 고정 응답으로 성공처럼 보이게 하지 않는다.
- 기존 저장소와 프론트 분업을 존중한다. 팀의 프론트 기술을 임의 변경하지 않는다.
- Java 21, 단일 Spring Boot, Gradle, Spring Security, JPA, PostgreSQL, Flyway를 구현 기본값으로 한다. 정확한 의존성 버전은 1단계에서 공식 호환성을 확인하고 고정한다.
- 외부 AI 호출은 서버에서만 실행한다. Python 서버·Redis·Kafka·JWT·refresh token·정식 가입을 임의 추가하지 않는다.
- 기존 기능별 패키지에 Controller/Service/Repository/Entity/DTO를 배치한다. Entity 직접 응답 금지. 불필요한 추상 계층을 만들지 않는다.

## 인증·데이터 경계

- 32바이트 난수를 base64url 무패딩으로 발급한다. SHA-256은 토큰 문자열의 UTF-8 바이트에 적용하며 DB에는 hash만 저장한다.
- Authorization Bearer로 인증한다. auth_session 만료·revoked·계정 ACTIVE 검사. 현재 세션만 로그아웃한다.
- 실제 리소스 group_id의 ACTIVE 멤버십을 검사한다. 중첩·배열 ID 전부 검사하고 개인 데이터는 소유권도 검사한다.
- 존재하는 타 공동체 리소스는 403, 존재하지 않으면 404. UUID 난수성은 권한이 아니다.
- 의료정보·토큰·원본은 Cache-Control: no-store. 원문·토큰·키·object_key를 일반 로그/URL/분석 도구에 남기지 않는다.
- 프론트는 Vercel, API는 별도 HTTPS EC2 Origin. CORS는 명시적 Origin. OPTIONS 및 허용 Origin의 오류 응답도 처리한다.

## DB·동시성

- Flyway가 스키마 변경을 관리한다. ddl-auto=validate. 이미 적용된 migration을 수정하지 않는다.
- DB 설계의 복합 FK·CHECK·UNIQUE를 JPA 자동 DDL로 대체하지 않는다. version·JSONB 매핑을 실제 PostgreSQL에서 검증한다.
- ScheduleMutationService에서 일정/배정/인계/완료/반복/가능시간/우선순위/멤버십 변경을 묶는다. 트랜잭션 첫 단계에서 schedule_guard id=1을 FOR UPDATE로 잠근 뒤 최신 값으로 검증한다.
- expectedVersion과 잠금은 함께 적용한다. 변경 이력·notification_event는 업무 변경과 같은 트랜잭션에 기록한다.
- 멱등 replay 전 현재 인증·인가. 같은 키 다른 내용은 409. 성공 응답 저장과 업무 변경은 원자적이다.
- preview는 읽기 전용. 저장 시 영향 대상/버전/fingerprint/cutoff/만료를 다시 검사한다.
- 외부 AI·푸시·파일 I/O는 일정 잠금 안에서 실행하지 않는다.
- 반복 anchor_date·취소 tombstone·14일 horizon을 유지한다. 생성기가 기존 예외/완료/취소를 덮어쓰지 않는다.

## 파일·AI·알림

- 실제 파일 형식/크기/파서 검증 후 접수한다. 문서 10,000,000 bytes·PDF 10페이지·기록 이미지 10장. PDF와 음성은 이미지 장수 제외.
- 일괄 업로드는 전체 검증 후 등록한다. encounter 잠금으로 기존+신규 장수를 검사한다. 실패·replay의 임시 파일을 정리한다.
- DB 작업 claim과 결과 반영은 짧은 트랜잭션. 외부 호출은 밖에서 실행한다. lease_token·inputVersion·삭제 여부 검사 후 반영한다.
- AI가 상태·담당자·확인자·DB ID를 결정하지 않는다. 처방은 확인 후 적용. 명확한 일반 업무만 기존 규칙으로 자동 적용한다.
- 공급자 JSON을 API DTO로 직접 노출하지 않는다. 스키마와 근거·날짜·중복·의미를 서버에서 검사한다.
- 전사 저장과 음성 DELETE_PENDING을 함께 기록한다. 삭제 워커·24시간 만료 스위퍼·지연 경고를 구현한다.
- notification_event Outbox → 알림함/기기 전달. 발송 성공은 읽음이 아니다. 푸시에 약명·진단명 금지.

## 검증·완료 보고

- 권한, 멱등성, 잠금 경쟁, 반복 취소, stale AI 결과, 확인 원자성에는 의미 있는 통합 테스트를 둔다.
- PostgreSQL 전용 동작은 실제 PostgreSQL에서 검증한다. 외부 호출은 fake로 자동 테스트하고 실제 연동은 별도로 검증한다.
- 테스트 실패·환경 미확보·실제 기기 미검증을 그대로 보고한다. 미실행 항목을 통과로 표시하지 않는다.
- 완료 보고: 변경 내용, API 범위, 실행 명령/결과, 남은 제한, 다음 단계. progress.md와 API 진행표 갱신.
- 비밀 값은 커밋하지 않는다. 파괴적 DB 초기화·force push·배포는 별도 사용자 지시 범위에서만 수행한다. 요청되지 않은 후속 단계와 커밋/푸시는 자동 실행하지 않는다.
