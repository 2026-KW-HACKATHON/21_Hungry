import { api } from './http'

export async function getTodayEditTask(taskId, config = {}) {
  const { data } = await api.get(`/tasks/${encodeURIComponent(taskId)}`, config)
  return data.data
}

export function buildTimePatch(task, date, time) {
  const startsAt = `${date}T${time}:00+09:00`
  const duration = new Date(task.endsAt).getTime() - new Date(task.startsAt).getTime()
  const end = new Date(new Date(startsAt).getTime() + duration)
  const endsAt = new Date(end.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 19) + '+09:00'

  return { startsAt, endsAt }
}

export async function updateTodayEditTask(task, patch, key) {
  const { data } = await api.patch(
    `/tasks/${encodeURIComponent(task.id)}`,
    { scope: 'OCCURRENCE', expectedVersion: task.version, patch },
    { headers: { 'Idempotency-Key': key } },
  )
  return data.data.occurrence
}

export async function releaseTodayEditTask(task, key) {
  const { data } = await api.post(
    `/tasks/${encodeURIComponent(task.id)}/handoffs`,
    { expectedVersion: task.version },
    { headers: { 'Idempotency-Key': key } },
  )
  return data.data
}
