export const availabilityOptions = [
  { mode: 'UNAVAILABLE', label: '불가능', icon: 'availability-unavailable' },
  { mode: 'FULL', label: '가능', icon: 'availability-full' },
  { mode: 'PARTIAL', label: '일부 가능', icon: 'availability-partial' },
]

const demoUserId = '33333333-3333-4333-8333-333333333333'
const demoRanges = [
  { mode: 'FULL', fromDate: '2026-10-01', toDate: '2026-10-04' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-05', toDate: '2026-10-09' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-12', toDate: '2026-10-16' },
  { mode: 'FULL', fromDate: '2026-10-17', toDate: '2026-10-18' },
  { mode: 'UNAVAILABLE', fromDate: '2026-10-19', toDate: '2026-10-30' },
]
const partialDates = ['2026-10-07', '2026-10-22', '2026-10-25', '2026-10-26']

function toDateString(year, month, day) {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

export function getCalendarWeeks(year, month) {
  const firstWeekday = new Date(Date.UTC(year, month, 1)).getUTCDay()
  const dayCount = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  const cellCount = Math.ceil((firstWeekday + dayCount) / 7) * 7
  const cells = Array.from({ length: cellCount }, (_, index) => {
    const day = index - firstWeekday + 1
    return day < 1 || day > dayCount ? null : { day, date: toDateString(year, month, day) }
  })

  const weeks = []
  for (let index = 0; index < cells.length; index += 7) {
    weeks.push(cells.slice(index, index + 7))
  }
  return weeks
}

export function getFamilyAvailability(userId, year, month) {
  const ranges = userId === demoUserId && year === 2026 && month === 9 ? demoRanges : []
  const days = {}
  for (const week of getCalendarWeeks(year, month)) {
    for (const day of week) {
      if (!day) continue
      const range = ranges.find((item) => item.fromDate <= day.date && day.date <= item.toDate)
      days[day.date] =
        ranges.length > 0 && partialDates.includes(day.date) ? 'PARTIAL' : (range?.mode ?? null)
    }
  }
  return { ranges, days }
}

export function isAvailabilityVisible(mode, selectedModes) {
  return mode !== null && (selectedModes.length === 0 || selectedModes.includes(mode))
}

export function getVisibleRangeSegments(week, ranges, days, selectedModes) {
  const segments = []
  for (const range of ranges) {
    if (!isAvailabilityVisible(range.mode, selectedModes)) continue

    let startColumn = null
    for (let column = 0; column <= 7; column += 1) {
      const day = week[column]
      const visible =
        day &&
        range.fromDate <= day.date &&
        day.date <= range.toDate &&
        isAvailabilityVisible(days[day.date] ?? null, selectedModes)

      if (visible && startColumn === null) startColumn = column
      if (!visible && startColumn !== null) {
        const endColumn = column - 1
        segments.push({
          mode: range.mode,
          startColumn,
          endColumn,
          showStart: week[startColumn].date === range.fromDate,
          showEnd: week[endColumn].date === range.toDate,
        })
        startColumn = null
      }
    }
  }
  return segments
}
