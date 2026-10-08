import { familyData, getTodayDate } from './todayAddMock'

export const documentTypeData = [
  { type: 'VISIT', label: '진료 기록', category: '진료 기록' },
  { type: 'PRESCRIPTION', label: '처방전', category: '처방전' },
  { type: 'DIAGNOSIS', label: '진단서', category: '진단서' },
  { type: 'MEDICINE_BAG', label: '약 봉투', category: '약 봉투' },
  { type: 'OTHER', label: '기타', category: '기타 문서' },
]

export const medicalDocumentsStorageKey = 'family-care:mock:medical-documents'

const [currentYear, currentMonth, currentDay] = getTodayDate().split('-')
const seedDocuments = [
  {
    documentType: 'OTHER',
    title: '가상 검사 안내문',
    hospitalName: '가상 월계의원',
    memberIndex: 1,
  },
  {
    documentType: 'VISIT',
    title: '가상 정기 진료 기록',
    hospitalName: '가상 노원병원',
    memberIndex: 2,
    visitInputMethod: 'AUDIO',
  },
  {
    documentType: 'PRESCRIPTION',
    title: '가상 처방전',
    hospitalName: '가상 월계의원',
    memberIndex: 1,
  },
  {
    documentType: 'DIAGNOSIS',
    title: '가상 진단서',
    hospitalName: '가상 노원병원',
    memberIndex: 3,
  },
  {
    documentType: 'MEDICINE_BAG',
    title: '가상 약 봉투',
    hospitalName: '가상 월계약국',
    memberIndex: 0,
  },
].map(({ memberIndex, ...document }, index) => {
  const date = `${currentYear}-${currentMonth}-${String(Math.max(1, Number(currentDay) - index)).padStart(2, '0')}`
  const member = familyData[memberIndex]
  return {
    ...document,
    id: `88888888-8888-4888-8888-88888888888${index + 1}`,
    groupId: '99999999-9999-4999-8999-999999999999',
    recordType: document.documentType === 'VISIT' ? 'VISIT' : 'DOCUMENT',
    occurredOn: date,
    createdAt: `${date}T09:00:00+09:00`,
    createdBy: { id: member.userId, displayName: member.name },
    inputVersion: 1,
    version: 0,
    processingState: 'READY',
    hasReviewItems: false,
    isSummaryStale: false,
  }
})

export function getMedicalDocuments() {
  const stored = sessionStorage.getItem(medicalDocumentsStorageKey)
  const documents = stored === null ? seedDocuments : JSON.parse(stored)
  if (!Array.isArray(documents)) throw new Error('더미 의료 문서 저장 데이터를 확인해 주세요.')
  return documents
}

export function getMedicalDocument(id) {
  return getMedicalDocuments().find((document) => document.id === id) ?? null
}

export function getDocumentRegisteredDate(document) {
  return new Date(Date.parse(document.createdAt) + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

export function filterMedicalDocuments(documents, { year, month, query, selectedTypes }) {
  const monthPrefix = `${year}-${String(month + 1).padStart(2, '0')}-`
  const keyword = query.trim().toLocaleLowerCase('ko-KR')
  return documents
    .filter(
      (document) =>
        getDocumentRegisteredDate(document).startsWith(monthPrefix) &&
        (selectedTypes.length === 0 || selectedTypes.includes(document.documentType)) &&
        (!keyword ||
          [document.title, document.hospitalName].some((value) =>
            value?.toLocaleLowerCase('ko-KR').includes(keyword),
          )),
    )
    .sort(
      (first, second) =>
        Date.parse(second.createdAt) - Date.parse(first.createdAt) ||
        second.id.localeCompare(first.id),
    )
}

export function getDocumentReadPath(document) {
  if (document.recordType !== 'VISIT') return '/doc-search'
  if (document.visitInputMethod === 'AUDIO') return '/doc-record'
  if (document.visitInputMethod === 'TEXT') return '/doc-write'
  throw new Error('진료 기록의 녹음/직접 기록 구분을 확인해 주세요.')
}
