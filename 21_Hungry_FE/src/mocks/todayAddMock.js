export const categoryData = [
  { kind: 'EXAM', name: '건강검진' },
  { kind: 'HOSPITAL', name: '병원 내원' },
  { kind: 'MEDICATION', name: '약 복용' },
  { kind: 'OTHER', name: '기타 돌봄' },
]

export const familyData = [
  { userId: '11111111-1111-4111-8111-111111111111', name: '부모1', category: '어머니' },
  { userId: '22222222-2222-4222-8222-222222222222', name: '자녀1', category: '나' },
  { userId: '33333333-3333-4333-8333-333333333333', name: '자녀2', category: null },
  { userId: '44444444-4444-4444-8444-444444444444', name: '자녀3', category: null },
]

export function parseDateInput(value) {
  const match = /^(\d{4})년 (\d{2})월 (\d{2})일$/.exec(value.trim())
  if (!match) return null

  const [, yearText, monthText, dayText] = match
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)

  if (year < 1 || month < 1 || month > 12 || day < 1 || day > 31) return null

  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)

  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null
  }

  return `${yearText}-${monthText}-${dayText}`
}

export function isValidTime(value) {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(value.trim())
}

export const requestData = [
  {
    id: 'request-1',
    category: '기타 돌봄',
    date: '2026년 06월 01일',
    time: '10:00',
    family: '가족1',
    description: '떠넘긴 일정 1',
  },
  {
    id: 'request-2',
    category: '약 복용',
    date: '2026년 06월 02일',
    time: '10:00',
    family: '가족1',
    description: '떠넘긴 일정 1',
  },
]

export const todaySchedulesStorageKey = 'family-care:mock:today-schedules'
export const durationMinutes = 60

export function getTodayDate() {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

export function formatDate(date) {
  const [year, month, day] = date.split('-')
  return `${year}년 ${month}월 ${day}일`
}

export function getTaskTimes(date, localTime) {
  const startsAt = `${date}T${localTime}:00+09:00`
  const end = new Date(Date.parse(startsAt) + durationMinutes * 60 * 1000)
  const endsAt = new Date(end.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 19) + '+09:00'
  return { startsAt, endsAt }
}

const seedSchedules = [
  {
    id: '55555555-5555-4555-8555-555555555551',
    kind: 'OTHER',
    localTime: '10:00',
    description: '돌봄 일정 1',
    assigneeUserId: familyData[0].userId,
  },
  {
    id: '55555555-5555-4555-8555-555555555552',
    kind: 'MEDICATION',
    localTime: '14:00',
    description: '돌봄 일정 2',
    assigneeUserId: familyData[1].userId,
  },
  {
    id: '55555555-5555-4555-8555-555555555553',
    kind: 'HOSPITAL',
    localTime: '16:00',
    description: '돌봄 일정 3',
    assigneeUserId: familyData[2].userId,
  },
  {
    id: '55555555-5555-4555-8555-555555555554',
    kind: 'EXAM',
    localTime: '12:00',
    description: '돌봄 일정 4',
    assigneeUserId: familyData[3].userId,
  },
].map((schedule) => ({ ...schedule, date: getTodayDate(), version: 0 }))

function normalizeSchedule(schedule) {
  return {
    ...schedule,
    durationMinutes,
    version: schedule.version ?? 0,
    ...getTaskTimes(schedule.date, schedule.localTime),
  }
}

export function getTodaySchedules() {
  const stored = sessionStorage.getItem(todaySchedulesStorageKey)
  const schedules = stored === null ? [] : JSON.parse(stored)
  if (!Array.isArray(schedules)) throw new Error('더미 일정 저장 데이터를 확인해 주세요.')

  const missingSeeds = seedSchedules.filter(
    (seed) => !schedules.some((schedule) => schedule.id === seed.id),
  )
  return [...missingSeeds, ...schedules].map(normalizeSchedule)
}

export function getTodaySchedule(id) {
  return getTodaySchedules().find((schedule) => schedule.id === id) ?? null
}

export function toScheduleCard(schedule) {
  const category = categoryData.find((item) => item.kind === schedule.kind)
  const family = familyData.find((item) => item.userId === schedule.assigneeUserId)
  return {
    ...schedule,
    category: category?.name ?? '-',
    date: formatDate(schedule.date),
    time: schedule.localTime,
    family: family?.name ?? '-',
    description: schedule.description || '-',
  }
}

export async function saveTodaySchedule(draft) {
  const schedule = normalizeSchedule({ ...draft, id: crypto.randomUUID(), version: 0 })
  sessionStorage.setItem(
    todaySchedulesStorageKey,
    JSON.stringify([...getTodaySchedules(), schedule]),
  )
  return schedule
}

export async function updateTodaySchedule(id, changes) {
  const schedules = getTodaySchedules()
  const index = schedules.findIndex((schedule) => schedule.id === id)
  if (index === -1) throw new Error('수정할 일정을 찾지 못했어요.')

  const previous = schedules[index]
  const schedule = normalizeSchedule({ ...previous, ...changes, id, version: previous.version + 1 })
  schedules[index] = schedule
  sessionStorage.setItem(todaySchedulesStorageKey, JSON.stringify(schedules))
  return schedule
}
