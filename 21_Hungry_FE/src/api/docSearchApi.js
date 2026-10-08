import { api } from './http'
import { requestRecordApi } from './recordApi'

export async function getDocumentDetail(encounterId, accessToken, signal) {
  const document = await requestRecordApi(
    `/encounters/${encodeURIComponent(encounterId)}`,
    accessToken,
    { signal },
  )

  if (document.recordType !== 'DOCUMENT') {
    throw new Error('문서 기록을 찾을 수 없어요. 보관함에서 다시 선택해 주세요.')
  }

  const sources = (document.sources || []).filter(
    (source) => source.sourceType === 'DOCUMENT' && !source.removedAt,
  )

  if (sources.length > 1) {
    throw new Error('첨부 문서가 여러 개로 조회됐어요. 문서 한 개만 첨부되는지 확인해 주세요.')
  }

  return {
    document,
    source: sources[0] || null,
  }
}

export async function getDocumentFile(source, accessToken, signal) {
  const { data } = await api.get(`/sources/${encodeURIComponent(source.id)}/content`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
    responseType: 'blob',
    signal,
  })

  return new File([data], source.file.originalName || 'document', {
    type: data.type || source.file.mediaType,
  })
}
