const KST_OFFSET = 9 * 60 * 60 * 1000

const categoryNames = {
  EXAM: '건강검진',
  HOSPITAL: '병원 내원',
  MEDICATION: '약 복용',
  OTHER: '기타 돌봄',
}

export function getKstDate(value = Date.now()) {
  return new Date(new Date(value).getTime() + KST_OFFSET).toISOString().slice(0, 10)
}

export function formatDate(date) {
  const [year, month, day] = date.split('-')
  return `${year}년 ${Number(month)}월 ${Number(day)}일`
}

function formatTime(value) {
  return new Date(new Date(value).getTime() + KST_OFFSET).toISOString().slice(11, 16)
}

export function getParentName(group) {
  return group?.recipient?.displayName || group?.parentProfile?.name || '부모 정보 입력 전'
}

export function toScheduleCard(task, group) {
  const isParent = task.assignee?.id === group.recipient.id
  const relation = { MOTHER: '어머니', FATHER: '아버지' }[group.parentProfile?.relation]
  const name = isParent ? getParentName(group) : task.assignee?.displayName

  return {
    id: task.id,
    category: categoryNames[task.kind] || '기타 돌봄',
    date: formatDate(getKstDate(task.startsAt)),
    time: formatTime(task.startsAt),
    family: task.assignee
      ? `${name || '-'}${isParent && relation ? `(${relation})` : ''}`
      : '미지정',
    description: task.description,
    requester:
      task.openHandoff?.requestedBy?.displayName ||
      task.openHandoff?.previousAssignee?.displayName ||
      null,
  }
}

export function isRequestTask(task) {
  return (
    task.executionStatus === 'PENDING' &&
    !task.assignee &&
    task.myUnassignedState === 'UNRESOLVED' &&
    task.openHandoff?.status === 'OPEN' &&
    task.openHandoff.reason === 'USER_REQUEST'
  )
}

export function getVisibleTasks(allTasks, filteredTasks) {
  const requests = allTasks.filter(isRequestTask)
  const requestIds = new Set(requests.map((task) => task.id))
  const schedules = filteredTasks.filter(
    (task) => task.executionStatus === 'PENDING' && !requestIds.has(task.id),
  )

  return { requests, schedules }
}

export function shiftMonth(month, amount) {
  const [year, number] = month.split('-').map(Number)
  return new Date(Date.UTC(year, number - 1 + amount, 1)).toISOString().slice(0, 7)
}

export function getCalendarDays(month) {
  const [year, number] = month.split('-').map(Number)
  const first = new Date(Date.UTC(year, number - 1, 1))
  const offset = first.getUTCDay()
  const count = new Date(Date.UTC(year, number, 0)).getUTCDate()
  const cellCount = Math.ceil((offset + count) / 7) * 7

  return Array.from({ length: cellCount }, (_, index) => {
    const value = new Date(Date.UTC(year, number - 1, index - offset + 1))
    const date = value.toISOString().slice(0, 10)

    return { date, day: value.getUTCDate(), isCurrentMonth: date.startsWith(month) }
  })
}
