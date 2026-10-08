const serverBaseUrl = (import.meta.env?.VITE_API_BASE_URL ?? '').trim().replace(/\/+$/, '')
const apiBaseUrl = import.meta.env?.DEV || !serverBaseUrl
  ? '/api/v1'
  : serverBaseUrl.endsWith('/api/v1') ? serverBaseUrl
    : serverBaseUrl.endsWith('/api') ? `${serverBaseUrl}/v1` : `${serverBaseUrl}/api/v1`
export async function requestRecordApi(path, accessToken, options = {}) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    cache: 'no-store',
    headers: { ...options.headers, Authorization: `Bearer ${accessToken}` },
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    const messages = {
      401: '로그인이 만료됐어요. 다시 로그인해 주세요.',
      403: '이 가족의 기록에 접근할 권한이 없어요. 가입 승인 상태를 확인해 주세요.',
      404: '기록을 찾을 수 없거나 삭제된 기록이에요.',
      413: '녹음 파일이 서버 용량 제한을 넘었어요.',
      415: '서버에서 지원하지 않는 녹음 형식이에요.',
    }
    const error = new Error(messages[response.status] || body?.error?.message || '서버 요청에 실패했어요.')
    error.status = response.status
    error.code = body?.error?.code
    throw error
  }
  if (!body?.data) throw new Error('서버 응답을 확인할 수 없어요. 같은 요청으로 다시 시도해 주세요.')
  return body.data
}

export function createAudioUploadAttempt(accessToken, groupId, wavBlob, hospitalName) {
  return {
    accessToken, groupId, wavBlob,
    createKey: crypto.randomUUID(),
    uploadKey: crypto.randomUUID(),
    recordBody: { recordType: 'VISIT', title: '진료 기록', hospitalName: hospitalName?.trim() || null },
    encounter: null,
    formData: null,
  }
}

export async function uploadAudioAttempt(attempt) {
  const { accessToken, groupId } = attempt
  if (!attempt.encounter) {
    attempt.encounter = await requestRecordApi(`/care-groups/${encodeURIComponent(groupId)}/encounters`, accessToken, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.createKey },
      body: JSON.stringify(attempt.recordBody),
    })
  }
  if (!attempt.formData) {
    const { version, inputVersion } = attempt.encounter
    const form = new FormData()
    // AUDIO has no documentType. The v1.1 E06 documents example conflicts with section 2.5.
    form.append('metadata', new Blob([JSON.stringify({ expectedVersion: version, expectedInputVersion: inputVersion })], { type: 'application/json' }))
    form.append('file', attempt.wavBlob, 'recording.wav')
    attempt.formData = form
  }
  const upload = await requestRecordApi(`/encounters/${encodeURIComponent(attempt.encounter.id)}/audio`, accessToken, {
    method: 'POST',
    headers: { 'Idempotency-Key': attempt.uploadKey },
    body: attempt.formData,
  })
  return { encounterId: attempt.encounter.id, upload }
}