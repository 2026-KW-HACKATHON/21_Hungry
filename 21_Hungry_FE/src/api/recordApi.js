import { api } from './http'

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

export function createAudioUploadAttempt(accessToken, groupId, wavBlob, hospitalName) {
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
    },
    encounter: null,
    formData: null,
  }
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
