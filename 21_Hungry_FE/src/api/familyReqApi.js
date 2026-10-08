import { api } from './http'
import { approveGroupRequest, rejectGroupRequest } from './authApi'
import { getTodayAddContext } from './todayAddApi'

export async function getFamilyRequestContext(config = {}) {
  const context = await getTodayAddContext(config)

  const myMember = context.members.find((member) => member.user.id === context.user.id)

  const canApprove = myMember?.role === 'CAREGIVER' && myMember.priority === 1

  if (!context.group || !canApprove) {
    return {
      ...context,
      canApprove,
      requests: [],
    }
  }

  const requests = new Map()
  const cursors = new Set()
  let cursor

  while (true) {
    const { data } = await api.get(
      `/care-groups/${encodeURIComponent(context.group.id)}/join-requests`,
      {
        ...config,
        params: {
          limit: 100,
          ...(cursor ? { cursor } : {}),
        },
      },
    )

    const page = data.data

    page.items.forEach((request) => {
      requests.set(request.id, request)
    })

    if (!page.hasMore) break

    if (!page.nextCursor || cursors.has(page.nextCursor)) {
      throw new Error('요청의 다음 페이지를 불러오지 못했어요. 다시 조회해 주세요.')
    }

    cursor = page.nextCursor
    cursors.add(cursor)
  }

  return {
    ...context,
    canApprove,
    requests: [...requests.values()],
  }
}

export function decideFamilyRequest(groupId, request, decision, key) {
  const decide = decision === 'APPROVE' ? approveGroupRequest : rejectGroupRequest

  return decide(groupId, request.id, request.version, key)
}
