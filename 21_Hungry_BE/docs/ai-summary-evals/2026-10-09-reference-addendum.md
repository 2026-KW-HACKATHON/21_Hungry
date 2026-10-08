# 공개 연구 보완안 병합 — 가이드라인 0.5.0 / 실행 후보 1.7

후속 상태: 이 문서는 병합 시점의 기록이다. 이후 v1.7 실제 20건 평가를 수행했으며, 결과·비용·승격 보류 판단은 [실제 품질 비교 보고서](2026-10-09-v1.7-quality.md)에 있다.

2026-10-09. 기존 변경사항을 보존했다. 서버 반영·운영 승격·모델 변경·파인튜닝 없음. 번역 및 예시 검토는 Codex AI의 원문 대조 단계이며 개발자 승인·의료진 검증이 아니다.

## 병합 범위

- 입력: 사용자가 제공한 Downloads의 `AI-SUMMARY-REFERENCE-ADDENDUM.md`(문서 날짜 2026-10-08).
- 입력 파일 SHA-256: `cb88efaa0232f3d199734c311e4a4aa57fae7993bfbaa6f26032c60cad851636`. 원본은 수정하지 않았다.
- 기준: 기존 가이드라인 0.4.0, 실행 기본 legacy/v1.1, 평가 후보 v1.6 및 저장 실패 검토. v1.1~v1.6 파일과 이전 실제 응답은 수정하지 않았다.
- 의사 설명 중심·쉬운 표현·불확실성 보존은 기존 규칙과 통합했다. 새 장은 자료의 실제 확보 범위·예시 채택 기준에 집중했다. 중복 문체 규칙이나 연구 전문은 실행 요청에 추가하지 않았다.
- 실제 관찰 실패에 대응해 G03에 AND/OR 구분, G05에 대체되는 약과 새 약의 식별 구분만 명시적으로 보강했다.
- 보완안의 ‘확정된 계획만 후보’는 조건부 행동 후보를 없애는 의미로 채택하지 않았다. 실제 조건부 재방문은 확인 필요 TASK, 약은 처방 확인, 자동 일정은 기존 기준 유지다.

## 연구 원문 확보와 제외

| 출처 | 실제 확인 | 예시 채택 여부 |
| --- | --- | --- |
| [JKMS 논문](https://jkms.org/DOIx.php?id=10.3346/jkms.2024.39.e148) | 본문, CC BY-NC 4.0 표시, 보충 표 링크. Tables 1/2/5 열기 시도는 브라우징 도구 Internal Error | 미채택. 보충 원문/요약 쌍 미확보, 제품 재사용 권한도 확인되지 않음. 읽거나 확보했다고 표시하지 않음 |
| [PMLR 논문 소개](https://proceedings.mlr.press/v248/hegselmann24a.html), [제작자 README](https://github.com/stefanhgm/patient_summaries_with_llms), [PhysioNet](https://physionet.org/content/ann-pt-summ/1.0.1/) | 연구 설명 및 데이터 접근 조건 확인. 코드 MIT와 데이터의 credentialed license/DUA/교육 요건을 구분 | 미채택. 제한된 원문·요약은 미다운로드/미전송. 연구가 사용한 임상 정답을 확보한 것처럼 표현하지 않음 |
| [PLABA](https://bionlp.nlm.nih.gov/plaba2024/) | 공식 페이지의 실제 초록–쉬운 표현 대응 예시·작성 지침 확인 | 미채택. 진료 대화가 아니며 제품 예시 재사용 조건 별도 미확인. 쉬운 표현 원칙만 참고 |
| [PriMock57](https://github.com/babylonhealth/primock57) | 저장된 원본 전사·영문/번역 전체 대화·note·저장 모델 응답 대조 | CC BY 4.0 모의 진료의 부분 예시 2개 채택. 원문 note를 가족용 정답으로 사용하지 않음 |

논문을 참고하는 것과 임상 자료를 제품 프롬프트로 재배포·외부 전송하는 것은 구분했다. 대용량 음성, 제한 데이터, 새 번역·채점 API는 사용하지 않았다.

## 실제 채택 예시와 검토

상세 출처·원문/번역 SHA·발화·원래 모델 출력·변경 내용은 [예시 추적 JSON](../../src/test/resources/ai/eval/reviewed-prompt-examples-v1.0.0.json)에 있다. 제작자: Babylon Health/PriMock57 기여자, 고정 commit `cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5`, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). 한국어 번역 및 아래 요약은 AI 수정본이다. 제작자의 검증이나 보증을 뜻하지 않는다.

| 예시 | 원문 핵심 및 근거 | 채택한 부분 요약 | 원래 응답과의 관계 |
| --- | --- | --- | --- |
| A: day1_consultation12/090 | `if you're feeling feverish and weak` → 열이 나고 기운이 없으면. 두 조건의 결합 | 열이 나고 기운이 없으면 Paracetamol 복용 가능. | v1.5 `/details/medicationMentions/2`의 올바른 조건을 유지하고 종결 전달 화법만 축약. v1.6의 OR 변경 실패에 대응 |
| B: day5_consultation12/083~086 | 더 강한 약을 Ibuprofen 대신, 동시 복용 금지, 규칙적·음식과 함께, Paracetamol 병용 가능 | 이름이 확인되지 않은 더 강한 진통제를 Ibuprofen 대신 음식과 함께 규칙적으로 복용. Ibuprofen과 함께 먹지 않으며, Paracetamol은 함께 복용 가능. | v1.6 `/summary/3` 수정. 원문 084의 병용 금지를 명시하고 새 약 이름 불명확함 보존. v1.5의 Ibuprofen 새 약 오인에 대응 |

- A의 note에는 정기 Paracetamol이 있지만 전사는 조건부다. note 내용으로 조건을 덮어쓰지 않았다.
- B의 note에는 naproxen이 있지만 전사에는 새 약 이름이 없다. 이름을 복원하지 않았다. 원문 085의 환자 ‘네’는 맥락용 입력에 보존하되 이 약 지시의 근거로 선택하지 않았다.
- 두 부모 응답은 **전체 사례 품질 실패**다. 해당 응답 전체를 통과 사례로 승격하지 않고, 재대조한 부분 주장만 편집 예시로 사용했다. 새 모델의 개선된 실출력인 것처럼 보고하지 않는다.
- 프롬프트의 예시는 전체 JSON 응답이 아닌 claim/근거 선택 부분 예시임을 명시했다. 실제 입력에는 전체 스키마와 필요한 약/행동 후보를 그대로 출력해야 한다.

## 실제 실행 경로와 변경 파일

| 파일 | 변경 |
| --- | --- |
| `docs/AI-SUMMARY-GUIDELINES.md` | 0.5.0 병합, 연구 확보·권한·예시 검토·평가 기준, 최신 PRD/API 파일 참조만 갱신 |
| `src/main/resources/ai/analysis-prompt-v1.7.txt` | v1.6 기반 두 규칙 보강과 대조된 부분 예시 2개 |
| `OpenAiProcessingAdapter.java`, `application.yaml` | 프롬프트 버전 명시 선택. 프로토콜/버전 조합 허용 목록, 기존 기본값 유지 |
| `reviewed-prompt-examples-v1.0.0.json`, `ReviewedPromptExamplesTests.java` | 원문–번역–저장 응답 추적 및 6개 로컬 검증 |
| `SummaryEvaluationMain.java`, `SummaryEvidenceEvaluationMain.java`, `EvidenceEvaluationReportMain.java` | 동일 요청/파서로 v1.7 평가 가능, 이미 본 12건의 개발 분할, 사용된 holdout을 새 독립 평가로 재사용하지 못하도록 제한 |
| 두 PriMock57 자료 폴더의 `README.md` | 과거 분할 이력을 보존하면서 현재 개발/예시 오염 상태 명시 |
| 이 보고서, `docs/progress.md` | 검증·한계·운영 미승격 기록 |

`analyze()`에서 실제 선택 리소스를 developer 메시지로 전송하고 동일 Responses API·발화 ID 스키마·서버 근거 연결·기존 외부 검증을 사용한다. 본문/원문 위치를 만드는 서버 코드나 검증 기준은 완화하지 않았다. 예시 ID는 실제 요청 스키마의 허용 ID 목록에 들어가지 않으며 복사된 예시 ID는 오류가 된다.

후보 선택은 `OPENAI_ANALYSIS_PROTOCOL=utterance-id-v2`, `OPENAI_ANALYSIS_PROMPT_VERSION=1.7` 동시 설정이다. **승격 승인이나 운영 서버 설정 변경을 한 것이 아니다.** 미지정 시 legacy/v1.1, v2만 지정하면 기존 v1.6이다. 모델·생성 상한 12,000·store=false·외부 API/DB·처방/일정 정책 유지. PRD/API/DB 본문과 FE는 수정하지 않았다.

## 검증 및 한계

실행 후 집계·파일 SHA·비용 원장 확인은 [검증 기록 JSON](../../eval-results/reference-addendum-2026-10-09/verification.json)에 보관했다.

- 비DB 테스트 9개 클래스 **54개 통과**, 실패/오류/skip 0. 기존 48개와 신규 6개 포함. `bootJar` 성공, JAR 안에 v1.1/v1.6/v1.7 리소스 존재 확인.
- 신규 테스트는 원문 TextGrid/영문/한국어/실제 저장 응답/프롬프트 문구 일치, 허용 버전 조합, 기본 v1.6 보존, v1.7 실제 `analyze()` HTTP 요청과 기존 파서, 예시 ID 거절, 이전 holdout 재사용 거절을 확인했다.
- HTTP 검증은 **로컬 모의 서버 응답**이다. 새 모델 출력이나 임상 정확성 검증이 아니다.
- 최초 출처 해시 테스트에서 BOM 포함 원본 파일 해시와 BOM 제거 읽기 해시의 차이를 발견했다. 바이트 단위 원본 해시를 그대로 검사하도록 테스트를 고치고 전체 재실행했다. 검증 기준 완화나 원문 수정 없음.
- 공개 개발 12건·합성 8건 요청 구성 dry-run 완료. 의료기록·DB·처방·돌봄 일정은 생성하지 않았다. dry-run은 파싱/내용 품질 평가 점수가 아니다. ID 프롬프트를 레거시 평가기에 잘못 넘기는 경우도 호출 전에 거절한다.
- **이번 추가 실제 모델 호출 0회, 추가 비용 $0.** 인증 실패 때문이 아니라 저장 응답 대조·로컬 연결 검증 범위로 수행했다. 기존 추가 평가 원장 38회/$0.38839050/$1 한도 유지, 앞선 별도 캠페인 51회/$0.4525758와 구분한다.
- v1.7 변경 전후 모델 품질·전체 의미 회귀·새 독립 평가·운영 음성/화면 E2E는 미실행이다. 이전의 질문–답변 근거 누락·약 범위·조건·일정 오류가 해결됐다고 주장하지 않는다. 운영 승격 보류.
- 기존 10건과 후속 별도 2건은 v1.7의 `reviewed-development` 12건이다. `new-holdout`/v1.7 실행은 거절한다. 새 독립 사례 확보 및 후보 고정 후 실제 품질 평가가 필요하다.

## 재실행과 서버 반영

```powershell
# API/DB 없는 회귀 검사 및 빌드
.\gradlew.bat test --tests '*ReviewedPromptExamplesTests' --tests '*UtteranceEvidenceTests' --tests '*SummaryEvaluationTests' --tests '*OpenAiProcessingAdapterTests' --tests '*AnalysisOutputValidatorTests' --tests '*AiContractResourceTests' --tests '*FileValidationServiceTests' --tests '*PreviewTokenServiceTests' --tests '*WebPushAdapterTests' bootJar
# 공개 개발 12건 및 합성 8건 요청 구성/비용 상한 확인 (호출 안 함)
.\gradlew.bat summaryEvidenceEval '-PevalSet=reviewed-development' '-PevalPrompt=1.7'
.\gradlew.bat summaryEvidenceEval '-PevalSet=synthetic' '-PevalPrompt=1.7'
```

실제 개발 평가가 필요하면 같은 명령에 `'-PevalLive=true'`를 추가한다. 기존 $1 원장을 공유하며 다음 호출 최대 비용을 예약할 수 없으면 중단한다. 실패 포함 재사용·재시도 없음. 검증 범위를 넘긴 fresh holdout은 새 자료와 고정 절차를 먼저 추가해야 한다. 후보 데이터나 원장을 지워 예산/독립성을 초기화하지 않는다.

서버에는 아직 올리지 않았다. 중요 근거 오류/회귀 및 새 독립 평가를 해결한 뒤 승인된 후보를 빌드·배포하고 두 환경변수를 함께 지정해야 한다. 기본값을 그대로 배포하면 v1.7로 바뀌지 않는다.

OpenAI Docs 스킬의 [공식 프롬프트 가이드](https://developers.openai.com/api/docs/guides/prompt-engineering)를 따라 소수의 대조된 예시, 버전 분리, 요청 경로 검증을 적용했다. 새 모델·파인튜닝·대규모 평가 프레임워크는 도입하지 않았다.
