import { api } from './http'

export async function getAvailabilityDays(fromDate, toDateExclusive, config = {}) {
  const { data } = await api.get('/me/availability-days', {
    ...config,
    params: { fromDate, toDateExclusive },
  })

  return data.data
}

export async function previewAvailability(request, key) {
  const { data } = await api.post('/me/availability-days/preview', request, {
    headers: { 'Idempotency-Key': key },
  })

  return data.data
}

export async function saveAvailability(request, key) {
  const { data } = await api.put('/me/availability-days', request, {
    headers: { 'Idempotency-Key': key },
  })

  return data.data
}
