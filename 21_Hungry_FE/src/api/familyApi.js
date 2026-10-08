import { api } from './http'

export async function getFamilyMembers(groupId, config = {}) {
  const { data } = await api.get(`/care-groups/${encodeURIComponent(groupId)}/members`, config)

  return data.data.items
}

export async function changeFamilyPriority(groupId, member, priority, key, config = {}) {
  const { data } = await api.put(
    `/care-groups/${encodeURIComponent(groupId)}/member-priorities`,
    {
      items: [
        {
          memberId: member.id,
          expectedVersion: member.version,
          priority,
        },
      ],
    },
    {
      ...config,
      headers: {
        ...config.headers,
        'Idempotency-Key': key,
      },
    },
  )

  return data.data.items
}
