import { currentUserId } from './familyMock'
import { getCalendarWeeks } from './familyScheduleMock'
import { getTodaySchedules, todaySchedulesStorageKey, updateTodaySchedule } from './todayAddMock'

export const personalAvailabilityStorageKey = `family-care:mock:availability:${currentUserId}`
export const availabilityConfig = { activeStartMinute: 420, activeEndMinute: 1320 }

const previews = new Map()
const initialRanges = [
  { mode: 'FULL', fromDate: '2026-10-01', toDate: '2026-10-04' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-05', toDate: '2026-10-09' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-12', toDate: '2026-10-16' },
  { mode: 'FULL', fromDate: '2026-10-17', toDate: '2026-10-18' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-19', toDate: '2026-10-30' },
]
const initialPartialDates = ['2026-10-07', '2026-10-22', '2026-10-25', '2026-10-26']

export function shiftAvailabilityDate(date, offset) {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}

function getDates(fromDate, toDateExclusive) {
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(fromDate) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(toDateExclusive) ||
    fromDate >= toDateExclusive
  )
    throw new Error('날짜 범위를 확인해 주세요.')

  const dates = []
  for (let date = fromDate; date < toDateExclusive; date = shiftAvailabilityDate(date, 1)) {
    if (new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date) {
      throw new Error('날짜 범위를 확인해 주세요.')
    }
    dates.push(date)
  }
  return dates
}

function getDefaultIntervals(mode) {
  if (mode === 'UNAVAILABLE' || mode === null) return []
  return [
    {
      startMinute: mode === 'PARTIAL' ? 1080 : availabilityConfig.activeStartMinute,
      endMinute: availabilityConfig.activeEndMinute,
    },
  ]
}

function getState() {
  const stored = sessionStorage.getItem(personalAvailabilityStorageKey)
  const state =
    stored === null
      ? {
          items: getCalendarWeeks(2026, 9)
            .flat()
            .filter(Boolean)
            .map((day) => {
              const range = initialRanges.find(
                (item) => item.fromDate <= day.date && day.date <= item.toDate,
              )
              const mode = initialPartialDates.includes(day.date)
                ? 'PARTIAL'
                : (range?.mode ?? null)
              return {
                date: day.date,
                mode,
                customIntervals: mode === 'PARTIAL',
                intervals: getDefaultIntervals(mode),
                version: mode === null ? null : 0,
              }
            }),
          ranges: initialRanges,
        }
      : JSON.parse(stored)

  if (!state || !Array.isArray(state.items) || !Array.isArray(state.ranges)) {
    throw new Error('더미 개인 일정 저장 데이터를 확인해 주세요.')
  }
  return state
}

export function getPersonalAvailability(year, month) {
  const state = getState()
  const dates = getCalendarWeeks(year, month)
    .flat()
    .filter(Boolean)
    .map((day) => day.date)
  const ranges = state.ranges.filter(
    (range) => range.fromDate <= dates[dates.length - 1] && dates[0] <= range.toDate,
  )
  const registeredDays = new Map(state.items.map((item) => [item.date, item.mode]))
  const days = Object.fromEntries(dates.map((date) => [date, registeredDays.get(date) ?? null]))
  return { ranges, days }
}

export function parseAvailabilityTime(value) {
  const match = /^(\d{1,2}):([0-5]\d)$/.exec(value.trim())
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  return hour < 24 || (hour === 24 && minute === 0) ? hour * 60 + minute : null
}

export function createPersonalAvailabilityRequest({ mode, fromDate, toDate, startTime, endTime }) {
  if (!fromDate || !toDate) throw new Error('달력에서 날짜를 선택해 주세요.')
  const toDateExclusive = shiftAvailabilityDate(toDate, 1)
  const registeredDays = new Map(getState().items.map((item) => [item.date, item.version]))
  const request = {
    fromDate,
    toDateExclusive,
    mode,
    customIntervals: mode === 'PARTIAL',
    expectedDays: getDates(fromDate, toDateExclusive).map((date) => ({
      date,
      version: registeredDays.get(date) ?? null,
    })),
  }

  if (mode === 'PARTIAL') {
    const startMinute = parseAvailabilityTime(startTime)
    const endMinute = parseAvailabilityTime(endTime)
    if (startMinute === null || endMinute === null)
      throw new Error('시간을 24시간제 시:분 형식으로 입력해 주세요.')
    if (startMinute >= endMinute) throw new Error('종료 시각은 시작 시각보다 늦어야 해요.')
    if (
      startMinute < availabilityConfig.activeStartMinute ||
      endMinute > availabilityConfig.activeEndMinute
    ) {
      throw new Error('활동시간인 07:00~22:00 안에서 시간을 입력해 주세요.')
    }
    request.intervals = [{ startMinute, endMinute }]
  }
  return request
}

function getTaskFingerprint() {
  return JSON.stringify(getTodaySchedules())
}

function getAssignmentImpact(days, state) {
  const changedDays = new Map(days.map((item) => [item.date, item]))
  const previousDays = new Map(state.items.map((item) => [item.date, item]))
  const releasedOccurrenceIds = []
  let retainedPastOccurrenceCount = 0

  for (const task of getTodaySchedules()) {
    if (
      task.assigneeUserId !== currentUserId ||
      (task.executionStatus && task.executionStatus !== 'PENDING')
    )
      continue
    const start = Date.parse(task.startsAt)
    const end = Date.parse(task.endsAt)
    const fromDate = new Date(start + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
    const toDateExclusive = shiftAvailabilityDate(
      new Date(end - 1 + 9 * 60 * 60 * 1000).toISOString().slice(0, 10),
      1,
    )
    const dates = getDates(fromDate, toDateExclusive)
    if (!dates.some((date) => changedDays.has(date))) continue

    const available = dates.every((date) => {
      const day = changedDays.get(date) ?? previousDays.get(date)
      const intervals = day?.intervals ?? getDefaultIntervals(day?.mode ?? null)
      const midnight = Date.parse(`${date}T00:00:00+09:00`)
      const startMinute = (Math.max(start, midnight) - midnight) / 60000
      const endMinute = (Math.min(end, midnight + 86400000) - midnight) / 60000
      return intervals.some(
        (interval) => interval.startMinute <= startMinute && endMinute <= interval.endMinute,
      )
    })

    if (available) continue
    if (start <= Date.now()) retainedPastOccurrenceCount += 1
    else releasedOccurrenceIds.push(task.id)
  }
  return { releasedOccurrenceIds, retainedPastOccurrenceCount }
}

export async function previewPersonalAvailability(request) {
  const state = getState()
  const dates = getDates(request.fromDate, request.toDateExclusive)
  if (!['FULL', 'UNAVAILABLE', 'PARTIAL'].includes(request.mode))
    throw new Error('돌봄 가능 상태를 선택해 주세요.')
  if (request.customIntervals !== (request.mode === 'PARTIAL'))
    throw new Error('가능 시간 입력 방식을 확인해 주세요.')
  const registeredDays = new Map(state.items.map((item) => [item.date, item]))
  const expectedDays = new Map(request.expectedDays.map((item) => [item.date, item.version]))
  if (
    request.expectedDays.length !== dates.length ||
    expectedDays.size !== dates.length ||
    dates.some(
      (date) =>
        !expectedDays.has(date) ||
        expectedDays.get(date) !== (registeredDays.get(date)?.version ?? null),
    )
  ) {
    throw new Error('일정이 변경됐어요. 다시 날짜를 선택해 주세요.')
  }

  const intervals =
    request.mode === 'PARTIAL' ? request.intervals : getDefaultIntervals(request.mode)
  if (
    !Array.isArray(intervals) ||
    intervals.some(
      (item) =>
        !Number.isInteger(item.startMinute) ||
        !Number.isInteger(item.endMinute) ||
        item.startMinute < availabilityConfig.activeStartMinute ||
        item.endMinute > availabilityConfig.activeEndMinute ||
        item.startMinute >= item.endMinute,
    )
  )
    throw new Error('가능한 시간 구간을 확인해 주세요.')

  const days = dates.map((date) => ({
    date,
    mode: request.mode,
    customIntervals: request.customIntervals,
    intervals,
    version: registeredDays.get(date)?.version ?? null,
  }))
  const impact = getAssignmentImpact(days, state)
  const previewToken = crypto.randomUUID()
  const expiresAt = Date.now() + 5 * 60 * 1000
  for (const [token, preview] of previews)
    if (preview.expiresAt <= Date.now()) previews.delete(token)
  previews.set(previewToken, {
    request: JSON.stringify(request),
    state: JSON.stringify(state),
    tasks: getTaskFingerprint(),
    expiresAt,
    days,
    ...impact,
  })
  return {
    previewToken,
    expiresAt: new Date(expiresAt).toISOString(),
    recalculatedDateCount: days.length,
    clippedCustomDates: [],
    days,
    ...impact,
  }
}

function overwriteRanges(ranges, request) {
  if (request.mode === 'PARTIAL') return ranges
  const toDate = shiftAvailabilityDate(request.toDateExclusive, -1)
  const remaining = ranges.flatMap((range) => {
    if (range.toDate < request.fromDate || toDate < range.fromDate) return [range]
    const pieces = []
    if (range.fromDate < request.fromDate)
      pieces.push({ ...range, toDate: shiftAvailabilityDate(request.fromDate, -1) })
    if (toDate < range.toDate) pieces.push({ ...range, fromDate: request.toDateExclusive })
    return pieces
  })
  return [...remaining, { mode: request.mode, fromDate: request.fromDate, toDate }].sort((a, b) =>
    a.fromDate.localeCompare(b.fromDate),
  )
}

export async function savePersonalAvailability({ previewToken, ...request }) {
  const preview = previews.get(previewToken)
  const state = getState()
  if (
    !preview ||
    preview.expiresAt <= Date.now() ||
    preview.request !== JSON.stringify(request) ||
    preview.state !== JSON.stringify(state) ||
    preview.tasks !== getTaskFingerprint()
  ) {
    throw new Error('미리보기 이후 일정이 변경됐어요. 추가하기를 다시 눌러 주세요.')
  }

  const updatedDays = new Map(
    preview.days.map((day) => [
      day.date,
      { ...day, version: day.version === null ? 0 : day.version + 1 },
    ]),
  )
  const items = [
    ...state.items.filter((item) => !updatedDays.has(item.date)),
    ...updatedDays.values(),
  ].sort((a, b) => a.date.localeCompare(b.date))
  const previousAvailability = sessionStorage.getItem(personalAvailabilityStorageKey)
  const previousTasks = sessionStorage.getItem(todaySchedulesStorageKey)
  try {
    sessionStorage.setItem(
      personalAvailabilityStorageKey,
      JSON.stringify({ items, ranges: overwriteRanges(state.ranges, request) }),
    )
    for (const id of preview.releasedOccurrenceIds)
      await updateTodaySchedule(id, { assigneeUserId: null })
  } catch (error) {
    if (previousAvailability === null) sessionStorage.removeItem(personalAvailabilityStorageKey)
    else sessionStorage.setItem(personalAvailabilityStorageKey, previousAvailability)
    if (previousTasks === null) sessionStorage.removeItem(todaySchedulesStorageKey)
    else sessionStorage.setItem(todaySchedulesStorageKey, previousTasks)
    throw error
  }
  previews.delete(previewToken)
  return { items: [...updatedDays.values()], releasedOccurrenceIds: preview.releasedOccurrenceIds }
}
