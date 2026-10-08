import { currentUserId } from './familyMock'
import { getCalendarWeeks } from './familyScheduleMock'

export const personalAvailabilityStorageKey = `family-care:mock:availability:${currentUserId}`

const initialRanges = [
  { mode: 'FULL', fromDate: '2026-10-01', toDate: '2026-10-04' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-05', toDate: '2026-10-09' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-12', toDate: '2026-10-16' },
  { mode: 'FULL', fromDate: '2026-10-17', toDate: '2026-10-18' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-19', toDate: '2026-10-30' },
]
const initialPartialDates = ['2026-10-07', '2026-10-22', '2026-10-25', '2026-10-26']

function getInitialAvailability() {
  const items = getCalendarWeeks(2026, 9)
    .flat()
    .filter(Boolean)
    .map((day) => {
      const range = initialRanges.find(
        (item) => item.fromDate <= day.date && day.date <= item.toDate,
      )
      const mode = initialPartialDates.includes(day.date) ? 'PARTIAL' : (range?.mode ?? null)
      return { date: day.date, mode, version: mode === null ? null : 0 }
    })

  return { items, ranges: initialRanges }
}

export function getPersonalAvailability(year, month) {
  const stored = sessionStorage.getItem(personalAvailabilityStorageKey)
  const state = stored === null ? getInitialAvailability() : JSON.parse(stored)
  if (!state || !Array.isArray(state.items) || !Array.isArray(state.ranges)) {
    throw new Error('더미 개인 일정 저장 데이터를 확인해 주세요.')
  }

  const dates = getCalendarWeeks(year, month)
    .flat()
    .filter(Boolean)
    .map((day) => day.date)
  const fromDate = dates[0]
  const lastDate = dates[dates.length - 1]
  const ranges = state.ranges.filter(
    (range) => range.fromDate <= lastDate && fromDate <= range.toDate,
  )
  const registeredDays = new Map(state.items.map((item) => [item.date, item.mode]))
  const days = Object.fromEntries(dates.map((date) => [date, registeredDays.get(date) ?? null]))

  return { ranges, days }
}
