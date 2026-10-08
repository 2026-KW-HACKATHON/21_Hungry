# PriMock57 한국어 가족용 요약 평가 자료 — v1.0.1

실제 환자 치료 기록이 아닌 **공개 모의 진료**다. 음성을 내려받거나 전사 API를 호출하지 않았다. 전체 비어 있지 않은 발화를 보존하고, doctor/patient TextGrid를 시작 시각·화자 순으로 합쳤다. 비어 있는 구간만 제외했다. 음성 대조는 하지 않았다.

## 출처와 사용 권리

- 제작자: Babylon Health 및 PriMock57 기여자. [저장소·README](https://github.com/babylonhealth/primock57), [라이선스](https://github.com/babylonhealth/primock57/blob/main/LICENSE.md).
- 원본 고정 커밋: `cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5`.
- 원본과 이 한국어 번역·각색 자료의 라이선스: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). 제작자의 보증이나 검증을 뜻하지 않는다. 저작권·라이선스 전문은 `original/LICENSE.md`에 보존했다.
- 원본 경로: `transcripts/{caseId}_{doctor|patient}.TextGrid`, `notes/{caseId}.json`. 각 사례의 원본 ID를 파일명과 manifest에 유지했다.
- 변경: 시간순 JSON 변환, 한국어 번역, 발화 ID, 검토 메모·사실 체크리스트 추가. 원본 TextGrid/진료 기록은 수정하지 않았다.
- ACI-BENCH [제작자 README](https://github.com/wyim/aci-bench)도 확인했다. README는 일부 doctor/patient 태그가 뒤바뀐 원본 ASR 문제를 명시한다. README가 연결한 [Figshare 배포](https://doi.org/10.6084/m9.figshare.22494601)는 공식 메타데이터 조회에서 CC BY 4.0이었다. PriMock57 10건으로 구성할 수 있어 ACI-BENCH 자료는 사용하지 않았고 화자 태그 수정도 하지 않았다.

## 고정 분할

아래는 최초 평가의 고정 분할 이력이다. 후속 근거 평가에서 기존 10건 모두 개발 자료로 편입했다. 보완안 병합 후보 v1.7은 `day1_consultation12`/`day5_consultation12`의 원문 대조된 부분 예시를 사용하므로, 이 두 사례 역시 독립 평가로 보고하지 않는다. 원본 분할 manifest는 과거 결과 재현을 위해 변경하지 않는다.

첫 모델 평가 전에 manifest의 개발 6건/별도 평가 4건을 고정했다. 전체 대화는 `.en.json` 및 `.ko.json`에 있다. 의료진용 note는 사실 대조 참고만 하고 모델 입력에 넣지 않는다. 전사에 없는 note의 진단·약명·용량을 기대 정답에 넣지 않았다.

| 원본 사례 ID | 분할 | 선정 이유 |
| --- | --- | --- |
| day3_consultation06 | 개발 | 말벌 쏘임, 응급 이동·가족의 약 투여 도움 |
| day3_consultation08 | 개발 | 여러 증상, 가래 정정, 목 관찰 결과, 기존 약과 일반약 |
| day3_consultation02 | 개발 | 해외여행 후 두통·발열감·발진, 미확정 진단, 긴급 진찰 |
| day2_consultation09 | 개발 | 갑작스런 왼쪽 증상, 뇌졸중 가능성, 기존 여러 약 |
| day3_consultation04 | 개발 | 흡입 횟수·빈도 조정, 평소 수치/새 검사 구분, 조건부 응급실 |
| day3_consultation05 | 개발 | 어지럼, 약명 불명확하지만 용량·기간 명시, 여러 재진 조건 |
| day2_consultation01 | 별도 평가 | 청력·이명, 원인 확인 전, 예약 절차와 완료 구분 |
| day2_consultation03 | 별도 평가 | 청력·얼굴 감각, 가족력, 미확정 판단, 당일 진찰 |
| day5_consultation12 | 별도 평가 | 새 두통, 약명 없는 처방, 병용 금지·허용, 조건부 연락 |
| day1_consultation12 | 별도 평가 | 설사·탈수 느낌, 필요 시 약, 업무 복귀·재진 조건 |

검사 영역은 목 관찰·환자 자가 관찰·평소 측정치·검사 계획을 포함한다. 확정된 영상/검사실 수치 중심 사례는 부족하며 대표성·임상적 정확성을 일반화할 수 없다. 문서 충돌은 기존 합성 D11로 별도 회귀 평가한다.

## 번역·평가 상태와 구조

- AI(Codex) 작성·원문 대조 초안, 사람 개발자 승인 및 의료진 검증 전. 자동 정렬 검증은 번역 정확도 보증이 아니다.
- `.en.json.turns[i]`와 `.ko.json.turns[i]`는 1:1 대응한다. 영어의 `id`, `speaker`, `start/end`, `interval`로 원래 TextGrid까지 추적한다. `facts[].turnIds`는 그 영어 발화 ID를 가리킨다.
- 중요한 불명확 태그를 보존했다. 의미가 불분명한 말은 원어/불명확 표시를 남겼으며 약명·의학적 설명을 상식으로 수리하지 않았다.
- 각 `facts`는 summary와 details를 합쳐 보존해야 할 사실 묶음이며, 후보 items에도 모순이 없어야 한다. 단순 키워드 일치로 통과시키지 않는다. `forbidden`과 `referenceNoteDifferences`도 확인한다.
- 평가자는 사실 누락/추가, 화자, 부정·정정·조건, 약·날짜, 문체, 대화 나열, 근거 불일치, 출력 형식을 구분한다. 정확한 인용 존재 여부와 인용이 주장을 뒷받침하는지 여부는 별개다.
- 모든 입력은 고정 합성 `occurredOn=2026-10-07`, `analyzedAt=2026-10-09T00:00:00Z`, `Asia/Seoul`을 사용한다. 실제 원본 진료 날짜가 아니며 영국 진료를 한국 처방으로 바꾸지 않았다.
- 별도 평가 대사/정답은 실행 프롬프트 예시에 넣지 않는다. 별도 평가 후 규칙을 고치려면 새 미사용 사례를 확보해야 한다.

## 번역 수정 이력

- v1.0.0: 10건 896개 발화 번역 준비.
- v1.0.1: 첫 기준 호출 뒤 day3_consultation06/046의 약을 주는 사람/받는 사람을 명시했다. 해당 첫 호출은 번역 오류 영향이 있어 비교에서 제외하지만 결과·비용은 보존하고 수정된 입력으로 재호출했다. day2_consultation09는 해당 사례 첫 호출 전에 기상 시점, 요청 진행 상태, 아버지 현재 병력, 주중 추가 음주량을 원문에 맞게 고쳤다. 이들은 모델 프롬프트 수정으로 해결한 오류가 아니다.

## 재실행

```powershell
# 원본 텍스트만 다시 준비 (기존 원본 파일은 덮어쓰지 않음)
powershell -NoProfile -File scripts/prepare-primock-eval.ps1
# 네트워크 호출 없는 정렬/요청/예산 검증
.\gradlew.bat test --tests '*SummaryEvaluationTests'
# 실제 호출은 명시적으로 opt-in. 개발 세트 예시
.\gradlew.bat summaryEval '-PevalSet=development' '-PevalPrompt=1.1' '-PevalLive=true'
```

결과는 `eval-results/primock57-2026-10-09`에 있으며 `clean`으로 삭제되지 않는다. `record.json`의 요청/입력/프롬프트/가이드라인 SHA와 토큰·시간·비용을 사용한다. 동일 요청의 실패도 재사용하여 자동 재시도하지 않는다. 원본이나 결과를 삭제해 예산을 초기화하지 않는다. 명령에서 `evalLive`를 생략하면 dry-run이다. 인증은 기존 환경변수 또는 ignored `secrets/openai.env`를 사용하며 출력하지 않는다. 운영 API, DB, 저장소, 처방·일정 워커는 실행하지 않는다.
