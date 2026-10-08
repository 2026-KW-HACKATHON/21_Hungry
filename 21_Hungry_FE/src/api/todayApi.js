import { me } from './authApi'
import { api } from './http'

export async function getTodayContext(config = {}) {
  const [user, response] = await Promise.all([me(config), api.get('/me/care-groups', config)])

  return { user, group: response.data.data.items[0] ?? null }
}

export async function getDateTasks(groupId, date, mineOnly = false, config = {}) {
  const tasks = new Map()
  const cursors = new Set()
  let cursor

  while (true) {
    const { data } = await api.get(`/care-groups/${encodeURIComponent(groupId)}/tasks`, {
      ...config,
      params: { date, mineOnly, limit: 100, ...(cursor ? { cursor } : {}) },
    })
    const page = data.data

    page.items.forEach((task) => tasks.set(task.id, task))
    if (!page.hasMore) break

    if (!page.nextCursor || cursors.has(page.nextCursor)) {
      throw new Error('일정의 다음 페이지를 확인할 수 없어요. 다시 조회해 주세요.')
    }

    cursor = page.nextCursor
    cursors.add(cursor)
  }

  return [...tasks.values()]
}

export async function getTaskCalendar(groupId, month, config = {}) {
  const { data } = await api.get(`/care-groups/${encodeURIComponent(groupId)}/calendar`, {
    ...config,
    params: { month },
  })

  return data.data
}

export async function completeTask(task, performedByUserId, idempotencyKey) {
  const { data } = await api.post(
    `/tasks/${encodeURIComponent(task.id)}/complete`,
    { expectedVersion: task.version, performedByUserId },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )

  return data.data
}

async function respondToHandoff(task, action, idempotencyKey) {
  const handoff = task.openHandoff

  if (!handoff?.id) {
    throw new Error('수행 요청 정보를 확인할 수 없어요. 다시 조회해 주세요.')
  }

  const { data } = await api.post(
    `/handoffs/${encodeURIComponent(handoff.id)}/${action}`,
    { expectedVersion: handoff.version, expectedTaskVersion: task.version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )

  return data.data
}

export const acceptHandoff = (task, key) => respondToHandoff(task, 'accept', key)
export const declineHandoff = (task, key) => respondToHandoff(task, 'decline', key)
