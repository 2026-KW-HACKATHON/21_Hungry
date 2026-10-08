# 파싱·원문 근거 연결 개선 — 2026-10-09

## 결론

문체 지침 추가 대신 **모델의 인용문/UUID/근거 인덱스 생성을 서버의 발화 ID 해석으로 교체하는 내부 경로**를 구현했다. 기존 v1.1/v1.3의 실패 14건을 실제 파서로 재현했고, 발화 ID 후보의 추가 실제 호출 38건은 모두 파서·원문 위치 검사를 통과했다. 그러나 의미적으로 틀린 근거, 질문/답변 분리, 조건 변경과 후보 추출 회귀가 남았다. **v1.5/v1.6 모두 승격하지 않는다. 운영 기본은 legacy/v1.1이다.**

38회 실제 추가 비용 추정 **$0.38839050 / $1**. 재시도 0회. 운영 의료기록·처방·일정 생성, 전사/번역/채점 API 호출, 모델 변경, 커밋/푸시/배포 없음. 기존 변경 및 삭제된 구버전 문서는 그대로 보존했다. 이 보고서는 AI 원문 대조이며 개발자 검토 대기, 의료진 검증 완료가 아니다.

## 1. 실제 경로와 수정 파일

| 파일/영역 | 변경 또는 확인 |
|---|---|
| `src/main/java/com/kw/knowone/encounter/processing/OpenAiProcessingAdapter.java` | `analyze → analysisRequest → /responses → parseAnalysis`의 실제 경로에 프로토콜 선택 추가. 기본 legacy는 v1.1·기존 schema 순서를 유지. 후보 v1/v2는 각각 프롬프트 1.5/1.6. eval도 같은 요청·HTTP·파싱 메서드 사용 |
| 같은 패키지 `UtteranceEvidence.java` | 명시 화자/줄/PDF 페이지 경계로 안정적인 발화 ID 부여, 내부 schema 생성, 선택 ID를 실제 quote/sourceId/textVersion/page/occurrence로 복원, root evidence와 detail 인덱스 서버 생성 |
| `src/main/resources/application.yaml` | `OPENAI_ANALYSIS_PROTOCOL`, 기본 `legacy`. 실험값 `utterance-id-v1`, `utterance-id-v2`. 이번에는 설정을 활성화하지 않음 |
| `src/main/resources/ai/analysis-prompt-v1.5.txt`, `v1.6.txt` | 기존 사실·조건·처방/일정 정책을 짧게 보존. 인용문 복사와 인덱스 계산 지시를 ID 선택으로 교체. v1.6은 근거 먼저 선택하도록 한 문장 및 schema 순서만 변경; 문체 예시 추가 없음 |
| `src/test/java/.../SavedEvidenceAuditMain.java` | 저장 v1.1/v1.3 실제 파서 재실행, JSON/잘림/거절 코드 구분, 모든 근거 문제의 JSON Pointer 표 |
| `SummaryEvaluationMain.java`, `SummaryEvidenceEvaluationMain.java` | 기존 평가기를 공통화해 고정 $1 추가 캠페인, 잠금/호출 전 비용 예약/캐시/동일 입력 검사/후보 고정, 이전 holdout→개발 분리. Spring/DB/워커 없음 |
| `EvidenceEvaluationReportMain.java` | 추가 응답 로컬 재검사, 토큰·비용 집계, 각 주장과 실제 선택 원문 쌍 출력. **의미 자동 채점기가 아님** |
| `UtteranceEvidenceTests.java`, `build.gradle` | 정확한 원문·emoji code point·CRLF·중복 구절·PDF·화자 미상·버전·ID 오류·실제 analyze 모의 HTTP 경로·저장 H01 재현·예산·자료 정합성 시험과 실행 명령 |
| `scripts/prepare-primock-eval.ps1` | `-EvidenceHoldout`로 별도 폴더에 공개 텍스트 2건만 확보. 기존 10건과 번역을 덮어쓰지 않음 |
| `src/test/resources/ai/eval/primock57-evidence-holdout/` | 원본 README/라이선스/전사/note, 전체 영·한 발화, 사실·금지 추론·원문 ID·번역 상태 |
| `docs/AI-SUMMARY-GUIDELINES.md`, `docs/progress.md` | 버전 0.4.0 및 구조적 처리/평가 상태. 새 의료 규칙 누적 대신 형식/의미 분리 명시 |
| `eval-results/evidence-linking-2026-10-09/` | 요청·원본 응답·파싱 결과·비용 ledger·입력/프롬프트/코드 해시·스냅샷·후보 고정·형식 진단·AI 의미 검토 |

기존 `AnalysisOutputValidator`의 원문 exact match, Unicode code point start/end 계산, 약 확인·조건부 TASK 검토 정책은 수정하지 않았다. 외부 `analysis-output.schema.json`, API DTO/DB/PRD/FE는 변경하지 않았다. 내부 summary는 근거를 갖는 주장 배열이지만 서버에서 기존 문자열로 합치므로 화면은 기존 `summary.text`, details/evidence 표시를 그대로 사용한다. 개별 summary 주장↔ID 연결은 평가의 raw 응답에 남으며 외부 API에 새 필드를 추가하지 않았다.

## 2. 저장 실패 14건의 정확한 분류

원본 39응답을 **수정하지 않고** 실제 `parseAnalysis → AnalysisOutputValidator.validate`로 재실행했다. v1.1 21건 중 8건, v1.3 18건 중 6건이 거절됨. 모든 39건 JSON 문법 유효; 원래 거절 14건 모두 `AI_EVIDENCE_INVALID`. 파서의 schema 거절, 출력 잘림, 통신 실패는 이 저장 집합에서 0건이었다. 이것은 원래 파서가 검사하지 않는 모든 JSON Schema 키워드까지 무결하다는 보장이 아니다.

첫 근거 거절 단계 기준 원문 불일치 8, 잘못된 sourceId 2, 범위 밖 detail 인덱스 4. 한 응답에 여러 오류가 있으므로 아래는 대표 경로, 전체 경로는 [자동 생성 전체 표](../../eval-results/evidence-linking-2026-10-09/saved-response-audit.md)와 JSON에 있다. **14건 모두 summary가 존재했으나 root evidence 또는 details 검증 때문에 응답 전체가 실패**했다. 일부 오류를 삭제해 성공으로 바꾸지 않았다.

표의 경로는 공급자 JSON Pointer, 인덱스는 0-based. `original`은 기존 출력 순서, `first`는 과거 근거 우선 실험 순서다. 같은 사례가 중복된 이유는 설정/번역이 달랐기 때문이다.

| 사례 | 버전/순서 | 요청 해시 앞 12자 | 대표 실패 지점 | 정확한 원인 |
|---|---|---|---|---|
| day3_consultation06 (이전 번역) | 1.1/original | d35663e1f3ee | `/evidence/6/quote` | `<UNIN/>` 제거 인용. `/evidence/7`, item 근거도 실패. 현재 번역과의 짝 비교에서는 제외 |
| day3_consultation06 | 1.1/original | 647815f407b5 | `/evidence/11/quote` | 전사 태그 제거로 연속 원문 부분 문자열이 아님 |
| day2_consultation09 | 1.1/original | 19379aecdbe6 | `/details/followUps/2/evidenceIndexes/0` | index=13, evidenceCount=13; 유효 0..12 |
| day3_consultation04 | 1.1/original | 3cd4ead3f581 | `/evidence/16/quote` | 축약/변형 인용으로 exact substring lookup 실패. followUps 인덱스와 item 인용에도 오류 |
| day3_consultation08 | 1.1/first | 65f67e1623de | `/evidence/0/quote` | 원문에 없는 연속 인용. root 5곳, item 인용, followUps 인덱스도 오류 |
| day3_consultation02 | 1.1/first | e57aad9d663a | `/evidence/3/quote` | 원문 불일치; tests/precautions의 인덱스 3곳도 범위 밖 |
| day3_consultation04 | 1.1/first | 9b59040c4e2f | `/evidence/4/quote` | 태그 제거; root 다른 3곳 및 item 2곳도 원문 불일치 |
| day3_consultation05 | 1.1/first | 88f1387989f0 | `/details/symptoms/0/evidenceIndexes/7` | index=8, evidenceCount=8; 이어지는 4개 인덱스도 범위 밖 |
| day3_consultation08 | 1.3/first | fa1e5036bd55 | `/details/medicationMentions/4/evidenceIndexes/0` | index=23, evidenceCount=22; precautions/followUps도 총 4곳 범위 밖 |
| day3_consultation05 | 1.3/first | 185895fe3b09 | `/details/medicationMentions/1/evidenceIndexes/0` | index=28, evidenceCount=28 |
| H01_NOVEL_MIXED_CORRECTION | 1.3/first | 1a7440a8ae40 | `/evidence/2/quote` | 원문 중간의 “우선…” 앞에 `의사:`를 새로 붙임. 원문은 `의사: 위염이라고 … 우선 …`. item에서도 같은 오류 |
| day2_consultation01 (옛 holdout) | 1.3/first | adf7ac9b2da7 | `/evidence/8/quote` | 전사 태그 제거 인용 |
| day2_consultation03 (옛 holdout) | 1.3/first | 036bba533987 | `/evidence/0/sourceId` | 요청 sources에 없는 ID. root 30개, item 2개 참조에 같은 오류 |
| day1_consultation12 (옛 holdout) | 1.3/first | b54e9e44c4eb | `/evidence/13/sourceId` | root 후반 5개 참조가 요청에 없는 ID 사용 |

기존 validator에는 **item에만** 잘못된 근거가 있으면 `INVALID_EVIDENCE/MISSING_EVIDENCE`로 확인 필요 처리하는 레거시 분기가 있다. 이번 39건에서 그 분기만으로 성공한 응답은 없었다. 새 ID 경로는 item ID도 먼저 엄격히 검사해서 잘못된 ID를 이 분기로 보내거나 삭제하지 않는다. root 근거 실패 시 일부 summary만 저장하는 동작도 새로 넣지 않았다.

## 3. 구조 개선과 경계

`원문 → 발화 카탈로그 → 모델의 ID 선택 → 서버 exact 원문 복원 → 기존 validator → 기존 AnalysisResult`

- ID는 sourceId 해시·textVersion·원문 내 발화 순번에서 결정된다. 같은 버전의 원문은 불변이라는 기존 계약을 따른다. 소스 배열 순서 변경에는 안정적이고 버전이 바뀌면 ID가 달라진다.
- 줄, 명시적 `의사:/환자:`와 `doctor:/patient:` 표지, PDF form feed 경계를 사용한다. 화자 미상의 STT를 의사/환자로 추정하지 않는다. 화자 없는 긴 단락은 큰 구간으로 남을 수 있으며 별도 diarization을 구현하지 않았다.
- 선택 가능한 ID를 provider enum으로 제한하고 서버도 존재·빈 목록·중복을 검사한다. 누락/잘못된 근거는 실패한다. 900개/enum 문자합 14,000 상한을 넘으면 호출 전에 `AI_INPUT_LIMIT_EXCEEDED`; 몰래 자르거나 일부 자료만 요약하지 않는다.
- quote/sourceId/version/page/occurrence와 detail 인덱스는 서버에서 생성하고 기존 validator가 원문 검색 및 code point 위치 검사를 다시 한다. `<UNIN/>`·명시적 정정·공백·원문 문장은 모델이 재작성할 필요가 없다.
- v1.6은 claim/item의 ID 필드를 본문보다 먼저 생성하게 했다. v1.5 H01의 duration=0 발견으로 숫자 범위도 provider에 보존하고 서버에서 추가 검사했다. v1.6의 1440분은 범위 안이지만 **근거 없는 일정**이므로 의미 평가 실패다.
- **자동 entailment 판정기는 없다.** 질문·답변·정정·약의 대상·조건이 선택된 발화에 모두 있는지 AI가 별도 대조했다. 테스트에서도 존재하는 질문 ID로 “폐렴 확진”을 만들면 구조 검사는 통과할 수 있음을 negative control로 명시했다. 이 테스트 성공은 잘못된 주장을 승인한 것이 아니다.

## 4. 데이터 분할·출처

기존 공개 10건(옛 개발 6 + 옛 별도 평가 4)을 모두 개발로 취급한다. 이전 manifest는 역사 보존을 위해 수정하지 않고 새 runner의 `public-development`가 합친다. 합성 8건도 기존 H01/H02를 포함해 모두 회귀 개발 자료다.

새 별도 평가: PriMock57 `day1_consultation01` 109발화(설사·약·조건부 대변검사), `day1_consultation02` 130발화(피부 증상·정정·여러 약·재진). [제작자 README](https://github.com/babylonhealth/primock57)와 [라이선스](https://github.com/babylonhealth/primock57/blob/main/LICENSE.md)를 확인했다. CC BY 4.0, 고정 commit `cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5`. 실제 환자 자료가 아닌 직원 역할극 진료다. 음성 및 ACI-BENCH는 사용하지 않았다.

전체 영어/한국어 대화를 1:1 보관했다. 번역·근거 사실·금지 추론은 AI 작성/원문 대조, 개발자·의료진 승인 아님. note의 Betnovate/cetraben·용량·10~14일 재진 등 **전사에 없는 정보는 정답에 넣지 않았다**. 화자 태그 임의 변경 없음. 새 사례의 대사/답을 프롬프트에 넣지 않았고 v1.6 해시·입력 해시를 고정한 뒤 실행했다. 새 결과를 본 뒤 후보를 수정하지 않았다. 후속 개선에 이 결과를 쓰면 이 두 건도 개발로 편입해야 한다.

## 5. 수정 전후 실제 결과

동일 모델 `gpt-5.4-mini-2026-03-17`, Responses API, max_output_tokens=12,000, store=false, temperature/reasoning 설정 미지정(동일 기본값). 입력 원문과 가상 날짜 문맥 동일. 내부 입력 표현과 출력 schema가 바뀌었으므로 **프롬프트 단독 A/B가 아닌 프로토콜 비교**다. 과거 baseline은 저장 응답을 재사용했고 운 좋은 결과를 얻기 위한 재호출은 하지 않았다.

| 세트 | 저장 v1.3 파서 통과 | v1.5 파서/위치 통과 | v1.6 파서/위치 통과 | v1.6 사실·근거 제한 검토 | v1.6 전체 사례 기준 |
|---|---:|---:|---:|---:|---:|
| 합성 8 | 7/8 | 8/8* | 8/8 | 5/8 오류 미발견 | 3/8 오류 미발견 |
| 공개 개발 10(옛 holdout 포함) | 5/10 | 10/10 | 10/10 | 0/10 전체 충족(9 오류, 1 불완전) | 0/10 |
| 새 별도 평가 2 | 미실행 | 미실행 | 2/2 | 0/2 | 0/2 |

`*` v1.5 H01의 durationMinutes=0은 레거시 파서가 허용한 **별도 숫자 schema 위반 1건**이다. 따라서 8/8을 완전한 schema 성공으로 표현하지 않는다. v1.6은 해당 범위를 엄격히 검사하지만 허구 1440분 일정은 여전히 의미 실패다.

사실·근거 평가는 summary/details뿐 아니라 items도 포함한다. 전체 사례 기준은 여기에 누락 후보·빈 summary 등의 보고서/추출 정책을 추가한다. 위 비율은 엄격한 **사례 단위 AI 검토**이지 임상 정확도나 사실 단위 정량 점수가 아니다. ID 정확성만으로 통과시키지 않으므로 문장은 대부분 맞아도 한 중요 주장에 불충분한 근거가 있으면 그 사례는 실패다. 낮은 중요도의 누락은 별도 기재했다. 전수 항목별 결과는 [semantic-review.json](../../eval-results/evidence-linking-2026-10-09/semantic-review.json), 원문 쌍은 `format-audit.json`에 있다.

대표 변화와 남은 오류:

| 사례 | 변경 전/첫 후보 | 최종 실험 후보 및 판정 |
|---|---|---|
| H01 | v1.3은 `의사: 우선…`이라는 없는 인용 때문에 전체 실패 | v1.6은 실제 의사 발화 전체를 연결, “가상 C약 저녁 1정, 4일”과 검사 양성 조건 보존. **인용 회귀는 해결**. 다만 시각 없는 DAILY/1440분 약 알림 후보는 여전히 실패 |
| day3_consultation08 | v1.5에 가래 있다고 기록하고 inaudible 답으로 혈담 없음 추론 | v1.6은 마른기침·가래 없음 정정 반영, 허위 혈담 부정 제거. 쌕쌕거림 부정은 답만 인용하고 질문 누락, 조건부 TASK 누락은 남음 |
| day5_consultation12 | v1.5 교체약 이름을 ibuprofen으로 오인; “검사 결과 언급 없음”이 인사말을 근거로 사용 | v1.6은 “이부프로펜 대신 처방약”으로 구분하고 인사말 인용 제거. 알 수 없는 약명은 여전히 null 대신 설명 문자열, 질문 없는 부정 근거와 재진 TASK 누락 잔존 |
| H02 | v1.5는 증상 재발 조건부 TASK만 생성 | v1.6은 “다음 날짜 미정”에서 새 일정 정하기 TASK를 추가. **회귀이므로 v1.6 채택하지 않음** |
| day3_consultation05 | v1.5는 paracetamol 비유를 현재 처방으로 만들지 않음 | v1.6은 발열 시 복용 권고로 바꿈. 진찰 TASK도 진단 설명만 인용. **새 중요 회귀** |
| day1_consultation12 | v1.5 Paracetamol 조건 “열이 나고 기운 없음” | v1.6 summary는 “열이나 쇠약감”. AND→OR 변경으로 **회귀** |
| 새 day1_consultation01 | 새로운 대화, baseline 호출 없음 | Dioralyte 조건·초기 며칠을 본문에는 쓰면서 선택 근거는 제품 소개만 포함(084; 필요한 086 누락). 조건부 검사도 조건 발화 누락 |
| 새 day1_consultation02 | 새로운 대화, baseline 호출 없음 | 나흘이라는 정정 대신 사흘 질문을 근거로 선택, 모호한 소변 답을 정상으로 확정. 항히스타민 약명들을 doseText에 넣고 진단을 TASK로 생성 |

남은 실패는 단순 인용 위치 오류가 아니라 **의미 선택/범위·정정·조건·후보 추출** 문제다. 새 두 사례에서는 해당 핵심 내용이 영문과 한국어에 모두 있었으며 관찰된 오류를 번역 탓으로 분류하지 않았다. 번역 자체의 임상 품질 검증은 별도 미완료다. 이번 범위에서 문체를 더 조정하거나 모델을 교체하지 않았다.

## 6. 비용·검증 기록

[공식 모델 요금](https://developers.openai.com/api/docs/models/gpt-5.4-mini), 2026-10-09 확인: 1M tokens당 input $0.75, cached input $0.075, output $4.50. [Structured Outputs 공식 문서](https://developers.openai.com/api/docs/guides/structured-outputs)의 enum 총량·문자 제한, 출력 키 순서, 수치 제약을 확인해 적용했다(OpenAI Docs 스킬 사용). 구조 준수가 의료 의미 정확성을 보장한다는 주장은 하지 않는다.

| 호출 묶음 | 실제 호출 | 예상 USD |
|---|---:|---:|
| v1.5 합성 | 8 | 0.02370450 |
| v1.5 공개 개발 | 10 | 0.15661725 |
| v1.6 합성 | 8 | 0.02510400 |
| v1.6 공개 개발 | 10 | 0.14602875 |
| v1.6 새 별도 평가 | 2 | 0.03693600 |
| 추가 합계 | 38 | **0.38839050** |

총 input 273,582 / output 40,712 tokens. 확인된 cached input 0, reasoning 0. 모두 completed/default tier. 요청 처리시간 평균 6,164ms, 최소 1,655ms, 최대 12,527ms. 호출별 내역·상한 예약·실측 usage·elapsedMillis는 각 record.json에 보존했다. 앞선 평가 $0.45257580와 분리한 추가 ledger이며, 이번 $1 상한을 넘지 않았다.

호출 전 UTF-8 요청 byte 수+4,096 여유를 입력 토큰 상한으로 보고, 캐시 할인을 가정하지 않은 입력 비용+12,000 최대 출력 비용의 2배를 예약한다. `누적 예약/비용 + 다음 예약 <= $1`일 때만 보낸다. 전송 전 PENDING 예약을 영속화하고 미확인 실패/중단은 예약 비용 유지. 한 캠페인 잠금과 기존 성공/실패/PENDING 재사용으로 무한/암묵 재시도를 막는다. 동일 원문 해시가 다르면 캐시를 거부한다. output limit 도달/incomplete는 성공 처리하지 않는다. 예산이나 크레딧 소진이 아니라 **의미 개선 정체·회귀** 때문에 종료했다.

로컬 검증: 기존 저장 39건 재실행, 새 저장 38건 재실행/claim-source 내보내기, 48개 비DB 테스트 및 bootJar. PostgreSQL 통합·운영 배포·실제 음성 전사·화면 E2E는 이번에 실행하지 않았다. HTTP 오류/timeout/refusal/잘림은 로컬 모의 서버 시험이며 실제 provider 오류를 새로 유도한 시험이 아니다.

## 7. 재실행과 서버 반영

PowerShell에서는 점이 있는 Gradle 속성을 따옴표로 감싼다. 다음 명령은 API 호출 없이 실행된다:

```powershell
.\gradlew.bat summaryEvidenceAudit summaryEvidenceReport
.\gradlew.bat summaryEvidenceEval '-PevalSet=public-development' '-PevalPrompt=1.6'
.\gradlew.bat test --tests '*UtteranceEvidenceTests' --tests '*OpenAiProcessingAdapterTests' --tests '*AnalysisOutputValidatorTests' --tests '*SummaryEvaluationTests' --tests '*AiContractResourceTests' --tests '*FileValidationServiceTests' --tests '*PreviewTokenServiceTests' --tests '*WebPushAdapterTests' bootJar
```

실호출 명령(현재 같은 요청은 **모두 캐시 재사용**, 추가 과금 없음):

```powershell
.\gradlew.bat summaryEvidenceEval '-PevalSet=synthetic' '-PevalPrompt=1.6' '-PevalLive=true'
.\gradlew.bat summaryEvidenceEval '-PevalSet=public-development' '-PevalPrompt=1.6' '-PevalLive=true'
.\gradlew.bat summaryEvidenceEval '-PevalSet=new-holdout' '-PevalPrompt=1.6' '-PevalLive=true'
```

결과 폴더나 record.json을 지워 비용/실패를 초기화하지 않는다. 예산을 리셋하는 옵션은 없다. 후보 변경 시 새 버전과 새 고정 파일을 사용하고 새 holdout을 준비해야 한다. `freeze`는 기존 고정 파일을 덮어쓰지 않는다. 추가 호출 중 가이드라인은 0.3.1 snapshot을 사용했고, **0.4.0은 결과/구조 설명 갱신으로 호출 후 작성**됐다. 실제 전송 프롬프트 및 입력을 바꾼 것으로 주장하지 않는다.

현재 기본 `OPENAI_ANALYSIS_PROTOCOL=legacy` 유지. 이후 중요한 의미 오류·새 회귀 해결, 개발/새 별도 평가, 사람의 원문 검토, 통합 시험을 통과한 뒤에만 후보 승격을 판단한다. 승인 이후에는 해당 프롬프트/코드가 포함된 서버 이미지 빌드·배포와 명시적인 프로토콜 설정이 필요하며 DB migration/FE 계약 변경은 필요 없다. **이번에는 설정 활성화·서버 반영을 하지 않았다.**
