import { api } from './http'

export async function getMemberAvailabilityDays(
  groupId,
  memberId,
  fromDate,
  toDateExclusive,
  config = {},
) {
  const { data } = await api.get(
    `/care-groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(memberId)}/availability-days`,
    {
      ...config,
      params: { fromDate, toDateExclusive },
    },
  )

  return data.data
}
