import { familyData } from './todayAddMock'

export const currentUserId = familyData[1].userId

export const familyMemberData = [
  { ...familyData[0], role: 'RECIPIENT', roleLabel: '부모', approvedDate: null },
  { ...familyData[1], role: 'CAREGIVER', roleLabel: '주돌봄자녀', approvedDate: null },
  { ...familyData[2], role: 'CAREGIVER', roleLabel: '공동돌봄자녀', approvedDate: '2026-10-01' },
  { ...familyData[3], role: 'CAREGIVER', roleLabel: '공동돌봄자녀', approvedDate: '2026-10-03' },
]
