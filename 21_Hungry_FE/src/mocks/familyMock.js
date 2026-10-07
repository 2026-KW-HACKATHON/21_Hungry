import { familyData, getTodayDate } from './todayAddMock'

export const currentUserId = familyData[1].userId
export const primaryCaregiverUserId = familyData[1].userId

export const familyMemberData = [
  { ...familyData[0], role: 'RECIPIENT', roleLabel: '부모', approvedDate: null },
  { ...familyData[1], role: 'CAREGIVER', roleLabel: '주돌봄자녀', approvedDate: null },
  { ...familyData[2], role: 'CAREGIVER', roleLabel: '공동돌봄자녀', approvedDate: '2026-10-01' },
  { ...familyData[3], role: 'CAREGIVER', roleLabel: '공동돌봄자녀', approvedDate: '2026-10-03' },
]

export const familyRequestData = [
  {
    id: '66666666-6666-4666-8666-666666666666',
    userId: '77777777-7777-4777-8777-777777777777',
    name: '자녀4',
    category: null,
    role: 'CAREGIVER',
    roleLabel: '공동돌봄자녀',
    requestedDate: '2026-10-08',
  },
]

const storageKey = 'family-care:mock:family'

function getFamilyState() {
  const stored = sessionStorage.getItem(storageKey)
  if (stored === null) {
    return { members: familyMemberData, requests: familyRequestData }
  }

  const state = JSON.parse(stored)
  if (!state || !Array.isArray(state.members) || !Array.isArray(state.requests)) {
    throw new Error('더미 가족 저장 데이터를 확인해 주세요.')
  }
  return state
}

export function getFamilyMembers() {
  return getFamilyState().members
}

export function getFamilyRequests() {
  return getFamilyState().requests
}

export function canApproveFamilyRequests() {
  return currentUserId === primaryCaregiverUserId
}

export async function approveFamilyRequest(requestId) {
  if (!canApproveFamilyRequests()) {
    throw new Error('주돌봄자녀만 가족 참여 요청을 승인할 수 있어요.')
  }

  const state = getFamilyState()
  const request = state.requests.find((item) => item.id === requestId)
  if (!request) throw new Error('승인할 가족 참여 요청을 찾지 못했어요.')
  if (state.members.some((member) => member.userId === request.userId)) {
    throw new Error('이미 참여한 가족이에요.')
  }

  const member = {
    userId: request.userId,
    name: request.name,
    category: request.category,
    role: request.role,
    roleLabel: request.roleLabel,
    approvedDate: getTodayDate(),
  }

  sessionStorage.setItem(
    storageKey,
    JSON.stringify({
      members: [...state.members, member],
      requests: state.requests.filter((item) => item.id !== requestId),
    }),
  )
  return member
}
