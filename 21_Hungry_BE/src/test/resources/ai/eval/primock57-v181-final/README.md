# v1.8.1 후보 고정 후 새 최종 입력

Babylon Health PriMock57, CC BY 4.0, 공개 모의 진료. 실제 환자 기록 아님.
출처: https://github.com/babylonhealth/primock57/tree/cd2ac707ad03cb4d2531f4ec6b90c659bf4357c5

선정: day4_consultation02 (166발화), day5_consultation02 (131발화). 기존 16개 공개 사례와 중복 없음.
`v1.8.1-plan-r2.json`의 후보 고정 후 텍스트만 확보. 전체 원본 TextGrid·영문 전사·대응 note·README/LICENSE 보관. 음성 다운로드 없음.
한국어는 Codex AI가 전체 맥락을 번역하고 약·수치·부정·조건을 원문과 대조했다. 임상 검증/사람 개발자 승인 아님. speaker와 순서 변경 없음. 불명확한 전사 표시는 유지하고 설명을 괄호에 추가한 부분을 각 ko JSON에 기록했다.
day4/02 원문 hyperthyroidism/Thyroxine과 note hypothyroid 차이는 임의 정정하지 않는다. day5/02 note의 Nitrofurantoin/NKDA를 정답에 넣지 않는다: 전사는 이름 미상 항생제, 명시 Clindamycin 알레르기이다.
notes는 모델 입력에 넣지 않는다. 원문/번역 쌍과 보존 사실은 en/ko JSON에 있다. 준비됨과 실제 API 평가 완료는 다르며 실행 여부는 원장/비교 보고서 참조.

새 합성 2건은 상위 폴더 `v181-final-synthetic.json`. 공개 사례와 별도로 집계한다. 후보 고정 뒤 작성했으며 프롬프트 예시나 튜닝에 사용하지 않는다. 결과를 본 뒤 후보를 고치면 해당 입력은 개발 세트로 재분류한다.
