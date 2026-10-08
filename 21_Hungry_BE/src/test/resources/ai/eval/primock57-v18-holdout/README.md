# v1.8 고정 후보의 새 공개 모의 진료 4건

출처: Babylon Health, Papadopoulos Korfiatis·Moramarco 외, [PriMock57](https://github.com/babylonhealth/primock57), commit `cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5`. 라이선스: **CC BY 4.0** (`original/LICENSE.md`). 실제 환자 치료기록이 아니라 임상의와 직원 배우가 진행한 공개 모의 진료다. 제작자 README·전사/기록 README와 라이선스를 함께 보관한다. 음성은 다운로드하지 않았다.

| 원본 사례 ID | 전체 발화 수 | 주요 관계 |
| --- | ---: | --- |
| day1_consultation03 | 141 | 진통제 선택과 소염제, 기존 피임약, 불확실한 편두통, 조건부 재진 |
| day2_consultation04 | 153 | 기존 Methotrexate, 항생제 처방 보류, 당일 진찰, 호전 없을 때 검사 |
| day4_consultation01 | 140 | 심장 우려, 응급 이송, 의료진 연락 주체, 약명 미상 복용 금지 |
| day5_consultation01 | 113 | 질문–단답 부정, 약물군 권고와 환자 선택, 즉시/장기 상담 조건 |

`.en.json`은 원본 doctor/patient TextGrid의 전체 비어 있지 않은 발화를 시간순으로 병합한 것이다. `.ko.json`은 같은 순서의 **전체 547발화** 한국어 번역과 원문 ID 기준 사실/금지 추론 목록이다. 원본은 수정하지 않았다. 각 사례의 `noteComparison`(또는 원본 비교 메모)에 note만의 정보·전사 혼선을 기록했다. 약·의료 내용을 한국 처방으로 치환하지 않았으며 원문의 불명확 태그·자기정정·이름/나이 혼선은 보존했다. day1/03의 Lyme's 전사 불명확, day4/01의 질문–답 불일치 등에 설명 표지를 추가했다. 원문과 번역의 SHA는 `provenance.json`에 있다.

번역·정답 기준·의미 평가는 **Codex AI 작성 및 원문 대조 단계**다. 의료진 검증 또는 사람 개발자 승인으로 표시하지 않는다. 진료 기록(note)은 참고만 했고 모델 입력에 보내지 않았다. 예를 들어 note만의 Aspirin 알레르기(day4/01), 식후 정기 Ibuprofen(day5/01), 피임약 변경(day1/03)은 대화 기반 정답에 추가하지 않았다.

`v1.8-candidate-freeze.json`으로 프롬프트/스키마/변환기를 고정한 후 원문을 처음 열었다. 기존 12 공개 사례와 ID가 겹치지 않는다. 개발 결과가 실패했더라도 사용자 지시에 따라 4건 모두 실제 호출했고, 그 결과를 보고 후보를 재수정하지 않았다. 따라서 **이 고정 후보의 진단용 별도 평가**이며 승격 승인 아님. 다음 튜닝에 내용을 사용하면 반드시 개발/회귀 자료로 전환한다.

재확보(네트워크 승인 필요): `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-primock-eval.ps1 -V18Holdout`. 이미 확보된 원본/번역/응답을 지우거나 예산을 초기화하지 않는다. 재평가는 `docs/ai-summary-evals/2026-10-09-v1.8-semantics.md` 참조.
