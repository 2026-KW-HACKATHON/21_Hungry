import { api } from './http.js'

export async function requestRecordApi(path, accessToken, options = {}) {
  const { method = 'GET', headers = {}, body, signal, timeout } = options

  const requestHeaders = { ...headers }

  if (accessToken) {
    requestHeaders.Authorization = `Bearer ${accessToken}`
  }

  const config = {
    url: path,
    method,
    headers: requestHeaders,
    data: body,
    signal,
  }

  if (timeout !== undefined) {
    config.timeout = timeout
  }

  const response = await api.request(config)

  if (response.status === 204) {
    return null
  }

  if (!response.data || !Object.prototype.hasOwnProperty.call(response.data, 'data')) {
    throw new Error('서버 응답 형식을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.')
  }

  return response.data.data
}

export function createAudioUploadAttempt(accessToken, groupId, wavBlob, hospitalName, occurredOn = null) {
  return {
    accessToken,
    groupId,
    wavBlob,
    createKey: crypto.randomUUID(),
    uploadKey: crypto.randomUUID(),
    recordBody: {
      recordType: 'VISIT',
      title: '진료 기록',
      hospitalName: hospitalName?.trim() || null,
      occurredOn,
    },
    encounter: null,
    formData: null,
  }
}

export async function getRecordGroup(accessToken, signal) {
  const groups = await requestRecordApi('/me/care-groups', accessToken, { signal })
  if (groups.items?.length !== 1) {
    throw new Error('연결된 가족이 없어요. 가족 연결과 가입 승인을 확인해 주세요.')
  }
  return groups.items[0]
}

export function listRecords(groupId, accessToken, filters = {}, signal) {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  }
  return requestRecordApi(`/care-groups/${encodeURIComponent(groupId)}/encounters?${query}`, accessToken, { signal })
}

// Keep the key and exact body together so a lost response can be retried safely.
export function createRecordMutation(path, body, method = 'POST') {
  return { path, method, body: structuredClone(body), key: crypto.randomUUID() }
}

export function sendRecordMutation(attempt, accessToken) {
  return requestRecordApi(attempt.path, accessToken, {
    method: attempt.method,
    body: attempt.body,
    headers: { 'Idempotency-Key': attempt.key },
  })
}

export async function getSourceBlob(sourceId, accessToken) {
  const response = await api.get(`/sources/${encodeURIComponent(sourceId)}/content`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    responseType: 'blob',
  })
  return response.data
}

export function createDocumentUploadAttempt(accessToken, groupId, file, documentType, occurredOn, hospitalName) {
  return {
    accessToken, groupId, file, documentType,
    createKey: crypto.randomUUID(), uploadKey: crypto.randomUUID(), encounter: null, formData: null,
    recordBody: { recordType: 'DOCUMENT', title: file.name.slice(0, 150), occurredOn: occurredOn || null, hospitalName: hospitalName?.trim() || null },
  }
}

export async function uploadDocumentAttempt(attempt) {
  if (!attempt.encounter) {
    attempt.encounter = await requestRecordApi(`/care-groups/${encodeURIComponent(attempt.groupId)}/encounters`, attempt.accessToken, {
      method: 'POST', headers: { 'Idempotency-Key': attempt.createKey }, body: attempt.recordBody,
    })
  }
  if (!attempt.formData) {
    const form = new FormData()
    form.append('metadata', new Blob([JSON.stringify({ expectedVersion: attempt.encounter.version, expectedInputVersion: attempt.encounter.inputVersion, documents: [{ documentType: attempt.documentType }] })], { type: 'application/json' }))
    form.append('files', attempt.file, attempt.file.name)
    attempt.formData = form
  }
  const upload = await requestRecordApi(`/encounters/${encodeURIComponent(attempt.encounter.id)}/documents`, attempt.accessToken, {
    method: 'POST', headers: { 'Idempotency-Key': attempt.uploadKey }, body: attempt.formData, timeout: 120000,
  })
  return { encounterId: attempt.encounter.id, upload }
}

export async function uploadAudioAttempt(attempt) {
  const { accessToken, groupId } = attempt

  if (!attempt.encounter) {
    attempt.encounter = await requestRecordApi(
      `/care-groups/${encodeURIComponent(groupId)}/encounters`,
      accessToken,
      {
        method: 'POST',
        headers: {
          'Idempotency-Key': attempt.createKey,
        },
        body: attempt.recordBody,
      },
    )
  }

  if (!attempt.formData) {
    const { version, inputVersion } = attempt.encounter

    const form = new FormData()

    form.append(
      'metadata',
      new Blob(
        [
          JSON.stringify({
            expectedVersion: version,
            expectedInputVersion: inputVersion,
          }),
        ],
        {
          type: 'application/json',
        },
      ),
    )

    form.append('file', attempt.wavBlob, 'recording.wav')

    attempt.formData = form
  }

  const upload = await requestRecordApi(
    `/encounters/${encodeURIComponent(attempt.encounter.id)}/audio`,
    accessToken,
    {
      method: 'POST',
      headers: {
        'Idempotency-Key': attempt.uploadKey,
      },
      body: attempt.formData,
      timeout: 120000,
    },
  )

  return {
    encounterId: attempt.encounter.id,
    upload,
  }
}
