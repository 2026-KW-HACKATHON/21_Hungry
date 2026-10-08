import { getRecordGroup, listRecords, requestRecordApi } from './recordApi'

export const documentTypes = [
  { value: 'VISIT', label: '진료 기록' },
  { value: 'PRESCRIPTION', label: '처방전' },
  { value: 'DIAGNOSIS', label: '진단서' },
  { value: 'MEDICINE_BAG', label: '약 봉투' },
  { value: 'LEGACY_UNCLASSIFIED', label: '기타' },
]

export function monthRange(month) {
  const [year, number] = month.split('-').map(Number)

  return {
    fromDate: `${month}-01`,
    toDateExclusive: `${number === 12 ? year + 1 : year}-${String(
      number === 12 ? 1 : number + 1,
    ).padStart(2, '0')}-01`,
  }
}

export function moveMonth(month, offset) {
  const [year, number] = month.split('-').map(Number)
  const date = new Date(0)

  date.setUTCFullYear(year, number - 1 + offset, 1)

  return date.toISOString().slice(0, 7)
}

export function formatDocumentDate(date) {
  if (!date) return '미상'

  const [year, month, day] = date.split('-').map(Number)

  return `${year}년 ${month}월 ${day}일`
}

export function categoriesOf(record) {
  if (record.recordType === 'VISIT') return ['진료 기록']

  const categories = record.documentTypes.map((value) =>
    value === 'LEGACY_UNCLASSIFIED'
      ? '기타 문서'
      : documentTypes.find((type) => type.value === value)?.label,
  )

  return [...new Set(categories.filter(Boolean))]
}

export function filterDocuments(records, query, type) {
  const search = query.trim().toLocaleLowerCase('ko-KR')

  return records.filter((record) => {
    const matchesType = !type || record.documentTypes.includes(type)
    const text = `${record.title} ${record.hospitalName || ''}`.toLocaleLowerCase('ko-KR')

    return matchesType && text.includes(search)
  })
}

export async function getDocumentsForMonth(month, accessToken, signal) {
  const group = await getRecordGroup(accessToken, signal)
  const records = new Map()
  const cursors = new Set()
  let cursor = null

  do {
    signal.throwIfAborted()

    const page = await listRecords(
      group.id,
      accessToken,
      { ...monthRange(month), limit: 100, cursor },
      signal,
    )

    for (const record of page.items) records.set(record.id, record)

    if (!page.hasMore) break

    if (!page.nextCursor || cursors.has(page.nextCursor)) {
      throw new Error('목록의 다음 페이지를 확인할 수 없어요. 다시 조회해 주세요.')
    }

    cursor = page.nextCursor
    cursors.add(cursor)
  } while (cursor)

  const items = [...records.values()]
  const result = []

  for (let index = 0; index < items.length; index += 4) {
    signal.throwIfAborted()

    const batch = await Promise.allSettled(
      items.slice(index, index + 4).map(async (record) => {
        if (record.recordType === 'VISIT') {
          return { ...record, documentTypes: ['VISIT'] }
        }

        const detail = await requestRecordApi(
          `/encounters/${encodeURIComponent(record.id)}`,
          accessToken,
          { signal },
        )

        return {
          ...detail,
          documentTypes: [
            ...new Set(
              detail.sources
                .filter((source) => source.sourceType === 'DOCUMENT' && !source.removedAt)
                .map((source) => source.documentType),
            ),
          ],
        }
      }),
    )

    signal.throwIfAborted()

    const failed = batch.find((item) => item.status === 'rejected')

    if (failed) throw failed.reason

    result.push(...batch.map((item) => item.value))
  }

  return result.filter((record) => record.occurredOn?.startsWith(`${month}-`))
}
