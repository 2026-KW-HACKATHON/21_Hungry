import {
  createDocumentUploadAttempt,
  createRecordMutation,
  requestRecordApi,
  sendRecordMutation,
  uploadDocumentAttempt,
} from './recordApi'

export const editableDocumentTypes = ['PRESCRIPTION', 'DIAGNOSIS', 'MEDICINE_BAG']

export function createDocumentEditAttempt(document, source, values, accessToken) {
  if (values.file && (!source || !editableDocumentTypes.includes(source.documentType))) {
    throw new Error('이 문서는 등록일과 발급기관만 수정할 수 있어요.')
  }

  const changes = {}
  if (values.registeredOn !== (document.occurredOn || ''))
    changes.occurredOn = values.registeredOn || null
  if (values.hospitalName.trim() !== (document.hospitalName || '')) {
    changes.hospitalName = values.hospitalName.trim() || null
  }

  return {
    accessToken,
    current: document,
    sourceId: source?.id || null,
    documentType: source?.documentType,
    file: values.file || null,
    changes,
    values: { registeredOn: values.registeredOn, hospitalName: values.hospitalName.trim() },
    metadataSaved: Object.keys(changes).length === 0,
    metadataAttempt: null,
    uploadAttempt: null,
    uploaded: null,
    removalAttempt: null,
    sourceRemoved: false,
    stage: '',
    needsRefresh: false,
  }
}

function applyVersions(attempt, response) {
  attempt.current = {
    ...attempt.current,
    version: response.version,
    inputVersion: response.inputVersion,
  }
}

async function refreshAttempt(attempt, signal) {
  const latest = await requestRecordApi(
    `/encounters/${encodeURIComponent(attempt.current.id)}`,
    attempt.accessToken,
    { signal },
  )
  signal?.throwIfAborted()

  if (latest.recordType !== 'DOCUMENT') {
    throw new Error('수정할 문서를 찾을 수 없어요. 보관함에서 다시 선택해 주세요.')
  }

  if (
    attempt.uploaded &&
    !attempt.uploaded.sources.every((uploaded) =>
      latest.sources.some((source) => source.id === uploaded.id && !source.removedAt),
    )
  ) {
    throw new Error(
      '새로 올린 파일이 변경되거나 제거됐어요. 기존 파일을 유지했으니 보관함에서 확인해 주세요.',
    )
  }

  attempt.current = latest
  if (!attempt.metadataSaved) attempt.metadataAttempt = null
  if (attempt.uploadAttempt && !attempt.uploaded) {
    attempt.uploadAttempt.encounter = latest
    attempt.uploadAttempt.formData = null
    attempt.uploadAttempt.uploadKey = crypto.randomUUID()
  }
  if (attempt.uploaded) {
    attempt.sourceRemoved = !latest.sources.some(
      (source) => source.id === attempt.sourceId && !source.removedAt,
    )
    attempt.removalAttempt = null
  }
  attempt.needsRefresh = false
}

export async function saveDocumentEditAttempt(attempt, { signal, onProgress } = {}) {
  try {
    signal?.throwIfAborted()
    if (attempt.needsRefresh) {
      onProgress?.('최신 문서 정보를 확인하고 있어요.')
      await refreshAttempt(attempt, signal)
    }

    const path = `/encounters/${encodeURIComponent(attempt.current.id)}`
    if (!attempt.metadataSaved) {
      attempt.stage = 'METADATA'
      onProgress?.('문서 정보를 저장하고 있어요.')
      attempt.metadataAttempt ||= createRecordMutation(
        path,
        {
          expectedVersion: attempt.current.version,
          ...attempt.changes,
        },
        'PATCH',
      )
      const result = await sendRecordMutation(attempt.metadataAttempt, attempt.accessToken)
      attempt.current = { ...attempt.current, ...result }
      attempt.metadataSaved = true
    }

    if (attempt.file && !attempt.uploaded) {
      attempt.stage = 'UPLOAD'
      onProgress?.('새 파일을 업로드하고 있어요.')
      if (!attempt.uploadAttempt) {
        attempt.uploadAttempt = createDocumentUploadAttempt(
          attempt.accessToken,
          attempt.current.groupId,
          attempt.file,
          attempt.documentType,
          attempt.current.occurredOn,
          attempt.current.hospitalName,
        )
        attempt.uploadAttempt.encounter = attempt.current
      }
      const result = await uploadDocumentAttempt(attempt.uploadAttempt)
      attempt.uploaded = result.upload
      applyVersions(attempt, result.upload)
    }

    if (attempt.uploaded && attempt.sourceId && !attempt.sourceRemoved) {
      attempt.stage = 'REMOVE'
      onProgress?.('기존 파일을 정리하고 있어요.')
      attempt.removalAttempt ||= createRecordMutation(
        `${path}/sources/${encodeURIComponent(attempt.sourceId)}`,
        {
          expectedVersion: attempt.current.version,
          expectedInputVersion: attempt.current.inputVersion,
        },
        'DELETE',
      )
      const result = await sendRecordMutation(attempt.removalAttempt, attempt.accessToken)
      attempt.sourceRemoved = true
      applyVersions(attempt, result)
    }

    attempt.stage = 'DONE'
    return attempt.current
  } catch (error) {
    if (
      error.apiCode === 'VERSION_CONFLICT' ||
      (error.status === 404 && attempt.stage === 'REMOVE')
    ) {
      attempt.needsRefresh = true
    }
    throw error
  }
}
