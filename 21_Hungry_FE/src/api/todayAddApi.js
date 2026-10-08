import { api } from './http'
import { getTodayContext } from './todayApi'

export async function getTodayAddContext(config = {}) {
  const context = await getTodayContext(config)

  if (!context.group) {
    return { ...context, members: [] }
  }

  const { data } = await api.get(
    `/care-groups/${encodeURIComponent(context.group.id)}/members`,
    config,
  )

  return { ...context, members: data.data.items }
}

export async function createTodayTask(groupId, payload, key) {
  const { data } = await api.post(
    `/care-groups/${encodeURIComponent(groupId)}/task-series`,
    payload,
    { headers: { 'Idempotency-Key': key } },
  )

  return data.data
}

export async function getCreatedTask(taskId) {
  const { data } = await api.get(`/tasks/${encodeURIComponent(taskId)}`)
  return data.data
}

export async function assignCreatedTask(task, userId, key) {
  const { data } = await api.put(
    `/tasks/${encodeURIComponent(task.id)}/assignment`,
    {
      expectedVersion: task.version,
      assigneeUserId: userId,
    },
    { headers: { 'Idempotency-Key': key } },
  )

  return data.data
}
