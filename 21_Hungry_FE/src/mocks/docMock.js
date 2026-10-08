import { familyData, formatDate, getTodayDate, parseDateInput } from './todayAddMock'
import { currentUserId } from './familyMock'
import {
  getMedicalDocumentFile,
  removeMedicalDocumentFile,
  saveMedicalDocumentFile,
} from './docFileMock'

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
  if (document.registeredOn) return document.registeredOn
  return new Date(Date.parse(document.createdAt) + 9 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

export async function saveMedicalDocument({
  documentType,
  registeredOn,
  hospitalName,
  file,
  source,
}) {
  if (!documentTypeData.some((type) => type.type === documentType && type.type !== 'VISIT')) {
    throw new Error('문서 종류를 다시 선택해 주세요.')
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(registeredOn) || !parseDateInput(formatDate(registeredOn))) {
    throw new Error('등록일을 선택해 주세요.')
  }
  if (!hospitalName.trim() || hospitalName.trim().length > 150) {
    throw new Error('발급기관을 1자부터 150자 사이로 입력해 주세요.')
  }
  if (!(file instanceof File) || file.size === 0 || file.size > 10000000) {
    throw new Error('첨부 파일을 확인해 주세요.')
  }

  const documents = getMedicalDocuments()
  const member = familyData.find((family) => family.userId === currentUserId)
  const id = crypto.randomUUID()
  const sourceId = crypto.randomUUID()
  const isPdf = file.type === 'application/pdf' || (!file.type && /\.pdf$/i.test(file.name))
  const document = {
    id,
    groupId: '99999999-9999-4999-8999-999999999999',
    recordType: 'DOCUMENT',
    documentType,
    title: file.name.trim().slice(0, 150) || '의료 문서',
    registeredOn,
    occurredOn: null,
    hospitalName: hospitalName.trim(),
    createdAt: new Date().toISOString(),
    createdBy: { id: currentUserId, displayName: member.name },
    inputVersion: 2,
    version: 1,
    processingState: 'OCR_PROCESSING',
    hasReviewItems: false,
    isSummaryStale: false,
    source,
    sources: [
      {
        id: sourceId,
        encounterId: id,
        sourceType: 'DOCUMENT',
        status: 'PENDING',
        textVersion: 0,
        removedAt: null,
        file: {
          id: crypto.randomUUID(),
          originalName: file.name,
          mediaType:
            file.type ||
            (isPdf
              ? 'application/pdf'
              : `image/${/\.jpe?g$/i.test(file.name) ? 'jpeg' : file.name.split('.').pop().toLowerCase()}`),
          byteSize: file.size,
          state: 'AVAILABLE',
        },
        contentPath: null,
      },
    ],
    activeImageCount: isPdf ? 0 : 1,
  }

  await saveMedicalDocumentFile(id, file)
  try {
    sessionStorage.setItem(medicalDocumentsStorageKey, JSON.stringify([document, ...documents]))
  } catch (error) {
    await removeMedicalDocumentFile(id).catch(() => {})
    throw error
  }
  return document
}

export async function updateMedicalDocument({
  id,
  expectedVersion,
  registeredOn,
  hospitalName,
  file,
}) {
  const documents = getMedicalDocuments()
  const index = documents.findIndex((document) => document.id === id)
  const original = documents[index]
  if (!original || original.recordType !== 'DOCUMENT') {
    throw new Error('문서를 찾을 수 없어요. 보관함에서 다시 선택해 주세요.')
  }
  if (original.version !== expectedVersion) {
    throw new Error('문서 정보가 변경됐어요. 보관함에서 다시 열어 주세요.')
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(registeredOn) || !parseDateInput(formatDate(registeredOn))) {
    throw new Error('등록일을 선택해 주세요.')
  }
  const issuer = hospitalName.trim()
  if (!issuer || issuer.length > 150) {
    throw new Error('발급기관을 1자부터 150자 사이로 입력해 주세요.')
  }
  if (file && (!(file instanceof File) || file.size === 0 || file.size > 10000000)) {
    throw new Error('첨부 파일을 확인해 주세요.')
  }
  const changed =
    registeredOn !== getDocumentRegisteredDate(original) ||
    issuer !== (original.hospitalName || '') ||
    Boolean(file)
  if (!changed) return original

  const document = {
    ...original,
    registeredOn,
    hospitalName: issuer,
    version: original.version + 1,
  }
  let previousFile = null
  if (file) {
    const isPdf = file.type === 'application/pdf' || (!file.type && /\.pdf$/i.test(file.name))
    const mediaType =
      file.type ||
      (isPdf
        ? 'application/pdf'
        : /\.jpe?g$/i.test(file.name)
          ? 'image/jpeg'
          : `image/${file.name.split('.').pop().toLowerCase()}`)
    if (!['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(mediaType)) {
      throw new Error('JPG, PNG, WEBP 이미지 또는 PDF 파일을 선택해 주세요.')
    }
    previousFile = await getMedicalDocumentFile(id)
    document.source = 'FILE'
    document.inputVersion = original.inputVersion + 1
    document.processingState = 'OCR_PROCESSING'
    document.isSummaryStale = Boolean(original.summary)
    document.activeImageCount = isPdf ? 0 : 1
    document.sources = [
      {
        id: crypto.randomUUID(),
        encounterId: id,
        sourceType: 'DOCUMENT',
        status: 'PENDING',
        textVersion: 0,
        removedAt: null,
        file: {
          id: crypto.randomUUID(),
          originalName: file.name,
          mediaType,
          byteSize: file.size,
          state: 'AVAILABLE',
        },
        contentPath: null,
      },
    ]
    await saveMedicalDocumentFile(id, file)
  }

  try {
    documents[index] = document
    sessionStorage.setItem(medicalDocumentsStorageKey, JSON.stringify(documents))
  } catch (error) {
    if (file) {
      if (previousFile instanceof File) await saveMedicalDocumentFile(id, previousFile)
      else await removeMedicalDocumentFile(id)
    }
    throw error
  }
  return document
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
  return '/doc-record'
}
