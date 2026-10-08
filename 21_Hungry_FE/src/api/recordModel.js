export function todayInSeoul() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}

export const recordStateLabels = {
  EMPTY: '자료 없음', TRANSCRIBING: '전사 중', OCR_PROCESSING: '문서 읽는 중',
  ANALYZING: '분석 중', NEEDS_REVIEW: '확인 필요', READY: '완료', FAILED: '처리 실패',
}

export function recordProgress(record) {
  const jobs = (record.jobs ?? []).filter((job) => job.inputVersion === record.inputVersion)
  const failed = jobs.find((job) => job.status === 'FAILED')
  if (record.processingState === 'FAILED' || failed) {
    return { done: true, message: [failed?.error?.message, failed?.error?.userAction].filter(Boolean).join(' ') || '자료 처리에 실패했어요. 아래 작업 상태를 확인해 주세요.' }
  }
  if (['READY', 'NEEDS_REVIEW', 'EMPTY'].includes(record.processingState)) {
    return { done: true, message: record.processingState === 'EMPTY' ? '아직 업로드된 자료가 없어요.' : record.isSummaryStale ? '이전 분석 결과예요. 최신 상태를 다시 조회해 주세요.' : record.processingState === 'NEEDS_REVIEW' ? '분석이 완료됐어요. 아래 확인할 내용을 확인해 주세요.' : '분석이 완료됐어요. 기록은 가족과 공유되어 있어요.' }
  }
  return { done: false, message: `${recordStateLabels[record.processingState] || '처리 대기 중'}이에요. 화면을 나가도 서버 처리는 계속돼요.` }
}

export function evidenceExcerpt(text, evidence) {
  if (!text) return ''
  if (evidence.startOffset == null || evidence.endOffset == null) return text
  return Array.from(text).slice(evidence.startOffset, evidence.endOffset).join('')
}

export function reviewProblem(item, payload = item.payload, resolution = null) {
  if (item.reviewReasons?.includes('CONFLICT') && !(resolution?.mode === 'MANUAL' && resolution.note?.trim())) return '서로 다른 자료의 내용을 대조하고 수정한 뒤 확인해 주세요.'
  if (item.evidence?.some((evidence) => evidence.isStale)) return '원문이 변경됐어요. 최신 분석을 기다려 주세요.'
  const validDate = (value) => /^\d{4}-\d{2}-\d{2}$/.test(value ?? '') && !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
  const validTime = (value) => /^([01]\d|2[0-3]):[0-5]\d$/.test(value ?? '')
  const validDuration = (value) => Number.isInteger(value) && value >= 1 && value <= 1440
  const validRule = (rule) => ['ONCE', 'DAILY', 'WEEKLY'].includes(rule.recurrence) && (rule.recurrence !== 'WEEKLY' || (rule.weekdays?.length && new Set(rule.weekdays).size === rule.weekdays.length && rule.weekdays.every((day) => Number.isInteger(day) && day >= 1 && day <= 7)))
  if (item.itemType === 'MEDICATION') {
    if (![payload.name, payload.doseText, payload.frequencyText].every((value) => value?.trim()) || !validDate(payload.startsOn) || !validDate(payload.endsOn) || payload.endsOn < payload.startsOn) return '약명·용량·횟수·복용 시작일과 종료일을 확인해 주세요.'
    if (!payload.schedulePlans?.length || payload.schedulePlans.some((plan) => !validDate(plan.firstDate) || !validDate(plan.lastDate) || plan.firstDate < payload.startsOn || plan.lastDate > payload.endsOn || plan.lastDate < plan.firstDate || !validTime(plan.localTime) || !validDuration(plan.durationMinutes) || !validRule(plan))) return '복용 일정의 날짜·시각·반복·소요 시간을 확인해 주세요.'
    if (new Set(payload.schedulePlans.map((plan) => plan.localTime)).size !== payload.schedulePlans.length || payload.schedulePlans.some((plan) => plan.recurrence === 'ONCE' && plan.firstDate !== plan.lastDate)) return '복용 시각은 중복 없이 입력하고, 한 번인 일정의 시작일과 종료일을 같게 지정해 주세요.'
  } else if (item.itemType === 'TASK') {
    if (!payload.title?.trim() || !['EXAM', 'HOSPITAL', 'OTHER'].includes(payload.kind) || !validDate(payload.date) || !validTime(payload.time) || !validDuration(payload.durationMinutes) || !validRule(payload) || (payload.lastDate && (!validDate(payload.lastDate) || payload.lastDate < payload.date))) return '일정 이름·종류·날짜·시각·소요 시간·반복을 확인해 주세요.'
  } else return '지원하지 않는 확인 항목이에요.'
  return ''
}
