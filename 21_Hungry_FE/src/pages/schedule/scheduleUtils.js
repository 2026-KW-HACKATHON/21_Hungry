export const availabilityOptions = [
  { mode: 'UNAVAILABLE', label: '불가능', icon: 'availability-unavailable' },
  { mode: 'FULL', label: '가능', icon: 'availability-full' },
  { mode: 'PARTIAL', label: '일부 가능', icon: 'availability-partial' },
]

export function shiftAvailabilityDate(date, offset) {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}

export function getMonthRange(year, month) {
  return {
    fromDate: new Date(Date.UTC(year, month, 1)).toISOString().slice(0, 10),
    toDateExclusive: new Date(Date.UTC(year, month + 1, 1)).toISOString().slice(0, 10),
  }
}

export function getCalendarWeeks(year, month) {
  const firstWeekday = new Date(Date.UTC(year, month, 1)).getUTCDay()
  const dayCount = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  const cellCount = Math.ceil((firstWeekday + dayCount) / 7) * 7

  const cells = Array.from({ length: cellCount }, (_, index) => {
    const day = index - firstWeekday + 1

    return day < 1 || day > dayCount
      ? null
      : {
          day,
          date: `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
        }
  })

  const weeks = []

  for (let index = 0; index < cells.length; index += 7) {
    weeks.push(cells.slice(index, index + 7))
  }

  return weeks
}

export function toAvailabilityCalendar(items) {
  const days = {}
  const ranges = []

  for (const item of [...items].sort((a, b) => a.date.localeCompare(b.date))) {
    days[item.date] = item.mode

    if (!['FULL', 'UNAVAILABLE'].includes(item.mode)) continue

    const previous = ranges[ranges.length - 1]

    if (previous?.mode === item.mode && shiftAvailabilityDate(previous.toDate, 1) === item.date) {
      previous.toDate = item.date
    } else {
      ranges.push({
        mode: item.mode,
        fromDate: item.date,
        toDate: item.date,
      })
    }
  }

  return { days, ranges }
}

export function isAvailabilityVisible(mode, selectedModes) {
  return mode != null && (selectedModes.length === 0 || selectedModes.includes(mode))
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
        days[day.date] === range.mode &&
        isAvailabilityVisible(range.mode, selectedModes)

      if (visible && startColumn === null) {
        startColumn = column
      }

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

function parseTime(value) {
  const match = /^(\d{2}):([0-5]\d)$/.exec(value)
  if (!match) return null

  const hour = Number(match[1])
  const minute = Number(match[2])

  return hour < 24 || (hour === 24 && minute === 0) ? hour * 60 + minute : null
}

export function buildAvailabilityRequest({ mode, fromDate, toDate, startTime, endTime }, items) {
  if (!availabilityOptions.some((option) => option.mode === mode)) {
    throw new Error('돌봄 가능 상태를 선택해 주세요.')
  }

  if (!fromDate || !toDate || fromDate > toDate) {
    throw new Error('달력에서 날짜를 선택해 주세요.')
  }

  const toDateExclusive = shiftAvailabilityDate(toDate, 1)
  const registered = new Map(items.map((item) => [item.date, item]))
  const expectedDays = []

  for (let date = fromDate; date < toDateExclusive; date = shiftAvailabilityDate(date, 1)) {
    if (!registered.has(date)) {
      throw new Error('선택한 날짜의 일정 정보를 다시 불러와 주세요.')
    }

    expectedDays.push({
      date,
      version: registered.get(date).version,
    })
  }

  const request = {
    fromDate,
    toDateExclusive,
    mode,
    customIntervals: mode === 'PARTIAL',
    intervals: [],
    expectedDays,
  }

  if (mode === 'PARTIAL') {
    const startMinute = parseTime(startTime)
    const endMinute = parseTime(endTime)

    if (startMinute === null || endMinute === null) {
      throw new Error('시작 시각과 종료 시각을 입력해 주세요.')
    }

    if (startMinute >= endMinute) {
      throw new Error('종료 시각은 시작 시각보다 늦어야 해요.')
    }

    request.intervals = [{ startMinute, endMinute }]
  }

  return request
}
