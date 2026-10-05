# 진료 녹음·문서 AI 처리 계약 v1.0

작성·공식 문서 확인: 2026-10-05, Asia/Seoul.
기준: PRD v0.14, DB v1.2 7절, API v1.0 E/R 및 공통 DTO.
이 문서는 짧은 SRS 역할의 구현 보완 문서다. 기존 업무 요구사항과 DB/API를 변경하지 않는다. 모델 ID·음성 코덱·예산은 decisions.md 미정이며 실호출 시험 전 고정한다. 아래 OpenAI 호출 경로와 내부 출력 규약은 이번 구현 기본값이다.

## AI-01. 입력과 처리 경로

1. 사용자가 녹음 종료 후 사용 여부를 선택한다. 삭제 선택이면 서버 전사 요청을 만들지 않는다.
2. Bearer·실제 공동체 권한·멱등성·실제 음성 형식/길이/크기를 검증한다. AUDIO 접수와 TRANSCRIBE 작업을 저장하고 202를 반환한다.
3. 전사 워커는 파일 기반 `POST /v1/audio/transcriptions`를 사용한다. 한국어 녹음 파일을 입력하고 전사 텍스트를 받는다. 특정 모델의 응답 포맷은 선택 모델 공식 문서로 확인한다. 실시간 전사·화자 분리는 추가하지 않는다.
4. 전사 텍스트를 encounter_source.extracted_text에 저장하고 READY로 바꾼다. 텍스트 저장과 음성 DELETE_PENDING은 같은 트랜잭션이다.
5. 문서 source는 검증된 이미지 또는 PDF로 OCR 작업을 실행한다. 구현 기본값은 Responses의 image/file 입력으로 문서 텍스트를 추출한 뒤 저장하는 것이다. PDF는 페이지별 순서와 페이지 경계를 보존한다. PDF page-to-text 매핑은 기존 JSON 메타 사용 가능성을 확인하고 별도 컬럼을 임의 추가하지 않는다.
6. 현재 입력의 활성 소스가 모두 READY이면 ANALYZE를 접수한다. 전사/OCR 텍스트 snapshot과 sourceId/textVersion, 진료일을 제공한다. 기존 확정 처방은 중복·변경 비교에 필요한 같은 공동체 데이터만 제공한다.
7. `POST /v1/responses`와 지원 모델의 Structured Outputs `text.format` JSON Schema를 사용해 쉬운 요약과 TASK/MEDICATION 후보를 받는다. 스키마를 통과한 출력도 서버 의미 검증 후 반영한다.
8. 저장 시 encounter inputVersion·source textVersion·작업 lease·삭제 여부를 재검사한다. 오래된 결과는 OBSOLETE로 버린다. API 상세·작업 조회는 기존 DTO로 반환한다.

## AI-02. 내부 입력 계약

분석 context: encounterId(서버 내부), inputVersion, occurredOn(nullable), timezone=Asia/Seoul, 분석 기준 시각, sources[{sourceId,textVersion,sourceType,text,pageMapping}], 필요한 기존 확정 처방.

원문은 지시문이 아니라 분석 자료로 구분한다. 녹음·문서 안의 명령문으로 시스템 지침을 변경하지 않는다. API 키·다른 공동체 자료·불필요한 개인정보를 넣지 않는다. 외부 DB 도구나 일정 변경 도구를 모델에 제공하지 않는다.

occurredOn은 상대 날짜 해석의 기준일이다. 진료일이 없거나 표현이 모호하면 날짜를 추정 확정하지 않는다. 등록일을 진료일로 자동 치환하지 않는다. '일주일 뒤'는 기준일이 명시된 경우에만 서버에서 계산하고 근거를 남긴다. '다음에', '필요하면'은 확정 예약이 아니다.

## AI-03. 내부 출력 계약과 API 매핑

실제 구현에서 `analysis-output.schema.json`을 추가한다. 이 문서의 필드 규약을 JSON Schema로 옮기고 테스트 fixture와 함께 버전 고정한다. 현재는 모델 미선정이므로 실행 가능한 공급자 스키마가 완성된 상태로 보고하지 않는다.

| 출력 | 내부 필드 | DB/API 매핑 |
|---|---|---|
| 요약 | schemaVersion=1, summary.text, details | encounter_revision.summary_text/details → E03 summary |
| details | symptoms/tests/medicationMentions/precautions/followUps; 각 [{text,evidence}] | API details schemaVersion=1 |
| 근거 | sourceId,textVersion,page(nullable),quote,occurrenceIndex | 서버 검증 후 quote 대신 기존 Evidence offset으로 저장 |
| 일반 업무 후보 | itemType=TASK, payload.schemaVersion=1, kind,title,date,time,durationMinutes,durationSource,recurrence,lastDate,weekdays,description | extracted_item payload. 적용 필수값은 API 6.1 |
| 처방 후보 | itemType=MEDICATION, payload.schemaVersion=1, name,doseText,frequencyText,startsOn,endsOn,instructions,schedulePlans | 기존 R02 payload. 불확실 필드 null 보존 |
| 계획 | recurrence,firstDate,lastDate,weekdays,localTime,durationMinutes | 명시 시각마다 별도 plan; 적용 시 기간 검증 |
| 추출 주의 | uncertaintyCodes[], isConditional | 서버 reviewReasons 산출에만 사용. 추가 API 필드 아님 |

모델에 item ID/version/reviewState/assignee/confirmedBy/application_key/DB supersedes ID 생성을 맡기지 않는다. 서버가 의미 비교와 동일 공동체 검사를 거쳐 ID·상태를 부여한다. internal uncertaintyCodes는 공급자 신호일 뿐 최종 정책 판단은 서버가 한다. 모델 스키마에서는 모든 정의된 키를 required로 두되 미상은 nullable, 객체 additionalProperties=false, enum은 기존 DTO와 일치시킨다. TASK와 MEDICATION은 지원되는 스키마 부분집합을 사용해 타입을 구분한다. 임의 중첩 JSON은 받지 않는다.

계획 duration은 의료 지시와 구별한다. 명시 시각이 없는 복약은 schedulePlans=[] 또는 localTime=null을 가진 후보로 보존하고 확인 화면에서 받는다. 하루 두 번을 임의 08:00/20:00으로 바꾸지 않는다. 처방 적용에는 유한 종료일과 필수 복약 정보가 필요하다. OTHER 소요시간은 사용자 입력이며 임의 계획값을 적용하지 않는다.

### 내부 응답 예시 — 적용 가능한 일정이 아닌 확인 후보

```json
{
  "schemaVersion": 1,
  "summary": {
    "text": "가상 자료에 일주일 뒤 재방문 안내가 있습니다.",
    "details": {
      "schemaVersion": 1,
      "symptoms": [],
      "tests": [],
      "medicationMentions": [],
      "precautions": [],
      "followUps": [{"text": "일주일 뒤 재방문", "evidence": [{"sourceId": "44444444-4444-4444-8444-444444444444", "textVersion": 1, "page": null, "quote": "일주일 뒤 다시 오세요", "occurrenceIndex": 0}]}]
    },
    "evidence": [{"sourceId": "44444444-4444-4444-8444-444444444444", "textVersion": 1, "page": null, "quote": "일주일 뒤 다시 오세요", "occurrenceIndex": 0}]
  },
  "items": [{
    "itemType": "TASK",
    "payload": {"schemaVersion": 1, "kind": "HOSPITAL", "title": "병원 재방문", "date": null, "time": null, "durationMinutes": 120, "durationSource": "PLANNING_DEFAULT", "recurrence": "ONCE", "lastDate": null, "weekdays": [], "description": null},
    "evidence": [{"sourceId": "44444444-4444-4444-8444-444444444444", "textVersion": 1, "page": null, "quote": "일주일 뒤 다시 오세요", "occurrenceIndex": 0}],
    "uncertaintyCodes": ["MISSING_DATE", "MISSING_TIME"],
    "isConditional": false
  }]
}
```

이 예시는 occurredOn 미상인 입력이다. date/time을 null로 유지하고 NEEDS_REVIEW로 보존한다. occurrenceIndex는 동일 source/page의 exact quote 중 0-based 순서이며 서버가 검증한다. 공급자 원문 응답 전체를 DB 일반 로그에 저장하지 않는다.

## AI-04. 근거 검증

모델에게 offset 숫자를 정확히 세도록 의존하지 않는다. 모델은 원문 그대로의 quote와 source 참조를 반환하고 서버가 저장된 text에서 exact match를 찾는다. quote가 여러 번 나오면 occurrenceIndex와 page 범위로 위치를 검증한다. 찾지 못하거나 참조가 잘못되면 자동 적용하지 않는다. 기존 nullable offset 계약에 맞게 검증되지 않은 offset은 null로 두고 해당 후보는 확인 필요로 분류한다. 미검증 근거로 원문 강조를 표시하지 않는다.

offset은 Unicode 코드포인트 [start,end), PDF page는 1부터 시작. Java/JS UTF-16 인덱스와 변환한다. emoji·반복 구절·문서 페이지·textVersion 변경 테스트를 둔다. summary와 details도 같은 source 검증을 받는다.

## AI-05. 확인·자동 적용 경계

| 결과 | 처리 |
|---|---|
| 날짜·시각·유형·계획시간·근거가 검증된 명확한 일반 TASK | 기존 정책에 따라 READY → 일정 서비스로 원자 적용·배정 |
| 신규/변경 MEDICATION | 항상 NEEDS_REVIEW. 요약만으로 medication_order 생성 금지 |
| 날짜/시각/기간/OTHER 계획시간 누락 | null 보존, 확인 시 입력. 미완성 task_occurrence 생성 금지 |
| 녹음·문서 내용 충돌 | CONFLICT 유지. 원문 선택/수동 해소 전 적용 금지 |
| 조건부 계획 | 확인/보류/제외. 확정 예약으로 자동 변환 금지 |
| 이미 적용한 동일 의미 | 서버 비교 후 기적용 처리; 파일 hash/약명만으로 병합하지 않음 |
| 이미 지난 명확한 날짜 | 후보 유지·사용자 조정, 과거 업무 자동 생성 금지 |

reviewReasons는 기존 코드(MISSING_TIME, MED_CHANGE, CONFLICT 등)와 구현 시 고정할 추가 내부 이유를 구분한다. 내부 불확실성은 필드 검증을 우회하지 않는다. AI 신뢰도 퍼센트나 근거 없는 의료적 확신을 새 UI 기능으로 만들지 않는다.

일괄 확인은 revision/inputVersion/각 후보 version과 필수값을 검증한 뒤 처방·시리즈·발생·배정·audit·outbox를 한 트랜잭션에서 반영한다. 하나 실패하면 전체 롤백. 모델의 후보 저장과 일정 자동 적용도 서버의 권한·guard·중복 규칙을 따른다.

## AI-06. 실패·재시도·운영

- source/job 상태와 encounter 파생 상태는 기존 API 2.3을 사용한다. 소스 일부 실패를 누락한 성공으로 표시하지 않는다.
- OpenAI 제한 거부는 AI_INPUT_LIMIT_EXCEEDED, FAILED, 동일 입력 자동 재시도 금지. 입력 조정 안내.
- timeout/429/일시 공급자 장애는 기존 AI_TIMEOUT/AI_RATE_LIMITED/AI_PROVIDER_UNAVAILABLE로 분류하고 제한된 재시도. Retry-After와 지수 backoff를 고려한다. 정확한 횟수·시간은 설정 시험 후 고정.
- refusal·incomplete·JSON/schema 실패는 성공 요약으로 저장하지 않는다. 기존 FAILED에 매핑한다. AI_OUTPUT_INVALID/AI_REFUSED 같은 상세 추가 코드는 제안이며 E12 오류 계약과 테스트를 함께 고정하기 전 공개하지 않는다.
- 빈 전사나 읽을 수 없는 문서는 조작된 정상 결과로 대체하지 않는다. 원문 확인/재업로드 또는 처리 실패로 안내한다.
- 요약 실패는 저장된 전사/OCR로 재시도하며 음성을 다시 전사하지 않는다. 전사 성공 음성 삭제, 실패 음성도 업로드 후 최대24시간 삭제 대상.
- 재시도는 같은 논리 작업 dedup과 새 lease fencing. 결과 반영 시 최신 inputVersion만 허용한다. 외부 중복 호출 비용을 완전히 막는다고 보장하지 않는다.
- 모델·프롬프트·schema 버전은 코드/설정과 fixture로 추적한다. 기존 스키마에 컬럼을 자동 추가하지 않는다. 메트릭에는 job ID·단계·시간·토큰/오류 등 최소 정보만 남긴다.
- 공급자 데이터 보관분과 로컬 삭제를 구분한다. `store=false` 지원 여부와 업체 데이터 정책은 선정 모델/API 공식 문서로 확인하며 이 옵션을 무보관 보장으로 표현하지 않는다.

## AI-07. 최소 평가 세트와 합격 조건

가상 샘플만 사용하며 아직 아래 시험은 실행하지 않았다.

| 샘플 | 기대 동작 |
|---|---|
| 병원명/시간 없는 대화 | 병원·시각 추정 금지, null·확인 필요 |
| 일주일 뒤 + 진료일 있음/없음 | 기준일 있을 때만 계산, 없으면 date null |
| 0.5정·1.5mg·비슷한 약명 | 숫자·단위 보존, 약은 확인 후 적용 |
| 하루 두 번, 시각 없음 | 임의 시각 생성 금지 |
| 종료일 없는 약 | 확정 처방 생성 금지 |
| 녹음·처방 문서 용량 불일치 | 충돌 표시·일괄 확인 차단 |
| 증상 계속되면 검사 | 확정 예약 자동 생성 금지 |
| emoji·동일 구절 반복 | 서버 근거 offset 정확성·범위 검사 |
| 11페이지/11장·손상·확장자 위장 | AI 호출 전 validation 거부 |
| 분석 중 입력 추가/삭제 | 이전 결과 OBSOLETE·최신만 반영 |
| 전사 성공 후 요약 실패 | 텍스트 재사용·음성 삭제 |
| 모델 refusal/incomplete·재시도 | 성공 은폐/중복 일정 없음 |

정책·상태·중복·권한 검사는 deterministic 테스트에서 전부 통과해야 한다. 실제 샘플 평가에서는 추정 확정/처방 무확인 적용이 하나라도 나오면 자동 적용 경로 배포를 보류하고 원인을 수정한다. 숫자 오인식을 schema 통과만으로 정확하다고 판단하지 않는다. latency·비용 목표값은 측정 전에 꾸며 확정하지 않는다.

## AI-08. 공식 근거와 서비스 결정 구분

공식 확인일: 2026-10-05 KST.

- File transcription: https://developers.openai.com/api/docs/guides/speech-to-text — 파일 전사, 가이드의 최대25MB와 mp3/mp4/mpeg/mpga/m4a/wav/webm 입력. 서비스 20분은 자체 추천이며 코덱/MIME 최종 지원 목록은 실기기·선택 endpoint 검증 필요.
- Structured Outputs: https://developers.openai.com/api/docs/guides/structured-outputs — 지원 모델의 JSON Schema 출력, 스키마 부분집합, refusal·incomplete 처리. 구조 준수가 의료 사실 정확성을 보장하지 않음.
- File inputs: https://developers.openai.com/api/docs/guides/file-inputs — 각 파일50MB 미만·요청 파일합계50MB, PDF 텍스트+페이지 이미지 입력. 우리 문서10MB/10페이지/이미지10장은 자체 정책.

내부 quote 검증·직렬화·server review 결정·처리 인터페이스는 서비스 설계 결정이며 OpenAI의 공식 기능 보장으로 설명하지 않는다. 모델·예산 선정 문서는 별도 평가 후 decisions.md에 기록한다.
