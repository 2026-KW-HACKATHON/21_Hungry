
import test from 'node:test'
import assert from 'node:assert/strict'
import { AxiosError } from 'axios'
import { api } from '../src/api/http.js'
import { createAudioUploadAttempt, uploadAudioAttempt, requestRecordApi, getRecordGroup, listRecords, createRecordMutation, sendRecordMutation, createDocumentUploadAttempt, uploadDocumentAttempt } from '../src/api/recordApi.js'
import { evidenceExcerpt, recordProgress, reviewProblem } from '../src/api/recordModel.js'

globalThis.sessionStorage = { getItem: () => null, removeItem() {} }
const ok = (config, data, status = 200) => ({ config, status, data: { data }, headers: {} })
function adapter(t, callback) {
  const previous = api.defaults.adapter
  api.defaults.adapter = callback
  t.after(() => { api.defaults.adapter = previous })
}
function fail(config, status, code) {
  return new AxiosError('failure', 'ERR_BAD_RESPONSE', config, null, { status, data: { error: { code, message: '서버에서 거절했어요.', details: { currentVersion: 4 } } } })
}

test('audio retry preserves encounter, binary, version metadata and idempotency key after lost response', async (t) => {
  const calls = []
  let uploads = 0
  adapter(t, async (config) => {
    calls.push(config)
    if (config.url.endsWith('/encounters')) return ok(config, { id: 'record-1', version: 3, inputVersion: 4 }, 201)
    if (++uploads === 1) throw new AxiosError('Network response lost', 'ERR_NETWORK', config)
    return ok(config, { job: { id: 'job-1' } }, 202)
  })
  const attempt = createAudioUploadAttempt('token', 'group-1', new Blob(['audio'], { type: 'audio/wav' }), ' 병원 ', '2026-10-08')
  await assert.rejects(uploadAudioAttempt(attempt))
  const result = await uploadAudioAttempt(attempt)
  assert.equal(result.encounterId, 'record-1')
  assert.equal(calls.filter((call) => call.url.endsWith('/encounters')).length, 1)
  assert.equal(calls[1].headers.get('Idempotency-Key'), calls[2].headers.get('Idempotency-Key'))
  assert.equal(calls[1].data, calls[2].data)
  assert.equal(calls[1].headers.get('Authorization'), 'Bearer token')
  assert.notEqual(calls[1].headers.get('Content-Type'), 'multipart/form-data')
  assert.deepEqual(JSON.parse(await attempt.formData.get('metadata').text()), { expectedVersion: 3, expectedInputVersion: 4 })
  assert.equal(await attempt.formData.get('file').text(), 'audio')
  assert.deepEqual(JSON.parse(calls[0].data), { recordType: 'VISIT', title: '진료 기록', hospitalName: '병원', occurredOn: '2026-10-08' })
})

test('lost creation response is retried with the exact same key and body', async (t) => {
  const creations = []
  adapter(t, async (config) => {
    if (config.url.endsWith('/encounters')) {
      creations.push(config)
      if (creations.length === 1) throw new AxiosError('Lost response', 'ERR_NETWORK', config)
      return ok(config, { id: 'record-2', version: 0, inputVersion: 1 })
    }
    return ok(config, {})
  })
  const attempt = createAudioUploadAttempt('token', 'group-1', new Blob(['audio']), '')
  await assert.rejects(uploadAudioAttempt(attempt))
  await uploadAudioAttempt(attempt)
  assert.equal(creations[0].headers.get('Idempotency-Key'), creations[1].headers.get('Idempotency-Key'))
  assert.equal(creations[0].data, creations[1].data)
  assert.equal(JSON.parse(creations[0].data).hospitalName, null)
})

test('document upload uses files parts and a per-file document type, and retries the same bytes', async (t) => {
  const calls = []
  let uploaded = false
  adapter(t, async (config) => {
    calls.push(config)
    if (config.url.endsWith('/encounters')) return ok(config, { id: 'doc-1', version: 0, inputVersion: 1 })
    if (!uploaded) { uploaded = true; throw new AxiosError('lost', 'ERR_NETWORK', config) }
    return ok(config, { sources: [] }, 202)
  })
  const attempt = createDocumentUploadAttempt('token', 'group-1', new File(['image'], 'test.png', { type: 'image/png' }), 'PRESCRIPTION', '2026-10-08', '')
  await assert.rejects(uploadDocumentAttempt(attempt))
  await uploadDocumentAttempt(attempt)
  assert.deepEqual(JSON.parse(await attempt.formData.get('metadata').text()), { expectedVersion: 0, expectedInputVersion: 1, documents: [{ documentType: 'PRESCRIPTION' }] })
  assert.equal(attempt.formData.getAll('files').length, 1)
  assert.equal(calls[1].data, calls[2].data)
  assert.equal(calls[1].headers.get('Idempotency-Key'), calls[2].headers.get('Idempotency-Key'))
})

test('active group, opaque cursor and optional date filters match API contract', async (t) => {
  const calls = []
  adapter(t, async (config) => { calls.push(config); return ok(config, { items: config.url === '/me/care-groups' ? [{ id: 'g1' }] : [], hasMore: false, nextCursor: null }) })
  assert.equal((await getRecordGroup('token')).id, 'g1')
  await listRecords('g1', 'token', { recordType: 'VISIT', cursor: 'a+b/=', fromDate: null, limit: 20 })
  assert.equal(calls[0].url, '/me/care-groups')
  assert.equal(calls[0].headers.get('Authorization'), 'Bearer token')
  const query = new URLSearchParams(calls[1].url.split('?')[1])
  assert.equal(query.get('cursor'), 'a+b/=')
  assert.equal(query.has('fromDate'), false)
})

test('pending members cannot proceed without an active care group', async (t) => {
  adapter(t, async (config) => ok(config, { items: [] }))
  await assert.rejects(getRecordGroup('token'), /가족 연결/)
})

test('HTTP errors expose status, API code, and conflict details; 200 FAILED is data', async (t) => {
  adapter(t, async (config) => { throw fail(config, 409, 'VERSION_CONFLICT') })
  await assert.rejects(requestRecordApi('/encounters/r1', 'token'), (error) => error.status === 409 && error.apiCode === 'VERSION_CONFLICT' && error.details.currentVersion === 4)
  api.defaults.adapter = async (config) => ok(config, { status: 'FAILED', canRetry: false })
  assert.equal((await requestRecordApi('/processing-jobs/j1', 'token')).status, 'FAILED')
  api.defaults.adapter = async (config) => { throw new AxiosError('too large', 'ERR_BAD_RESPONSE', config, null, { status: 413, data: '<html>too large</html>' }) }
  await assert.rejects(requestRecordApi('/test', 'token'), (error) => error.status === 413 && error.message.includes('용량'))
})

test('review and deletion mutations freeze the request for safe replay', async (t) => {
  const calls = []
  adapter(t, async (config) => { calls.push(config); return ok(config, {}) })
  const body = { expectedInputVersion: 7, revisionId: 'revision', items: [{ itemId: 'item', expectedVersion: 3 }] }
  const attempt = createRecordMutation('/encounters/r1/review-items/confirm', body)
  body.items[0].expectedVersion = 99
  await sendRecordMutation(attempt, 'token')
  await sendRecordMutation(attempt, 'token')
  assert.equal(calls[0].data, calls[1].data)
  assert.equal(JSON.parse(calls[0].data).items[0].expectedVersion, 3)
  assert.equal(calls[0].headers.get('Idempotency-Key'), calls[1].headers.get('Idempotency-Key'))
  await sendRecordMutation(createRecordMutation('/encounters/r1', { expectedVersion: 5, previewToken: 'signed' }, 'DELETE'), 'token')
  assert.equal(calls[2].method, 'delete')
  assert.deepEqual(JSON.parse(calls[2].data), { expectedVersion: 5, previewToken: 'signed' })
})

test('stale and failed analysis states never appear as completed current results', () => {
  assert.equal(recordProgress({ inputVersion: 2, processingState: 'ANALYZING', jobs: [] }).done, false)
  assert.equal(recordProgress({ inputVersion: 2, processingState: 'READY', isSummaryStale: true }).message.includes('이전'), true)
  assert.equal(recordProgress({ inputVersion: 2, processingState: 'FAILED', jobs: [{ inputVersion: 2, status: 'FAILED', error: { message: '입력 초과', userAction: '파일 조정' } }] }).message, '입력 초과 파일 조정')
  assert.equal(recordProgress({ inputVersion: 2, processingState: 'ANALYZING', jobs: [{ inputVersion: 1, status: 'FAILED' }] }).done, false)
})

test('missing medical fields, conflicts, and stale evidence block confirmation', () => {
  const item = { itemType: 'TASK', reviewReasons: [], evidence: [], payload: { schemaVersion: 2, kind: 'HOSPITAL', title: '방문', date: '2026-10-10', time: '10:00', durationMinutes: 120, recurrence: 'ONCE' } }
  assert.equal(reviewProblem(item), '')
  assert.notEqual(reviewProblem(item, { ...item.payload, time: null }), '')
  const conflict = { ...item, reviewReasons: ['CONFLICT'] }
  assert.notEqual(reviewProblem(conflict), '')
  assert.equal(reviewProblem(conflict, item.payload, { mode: 'MANUAL', note: '원문 대조 후 시간 정정' }), '')
  assert.notEqual(reviewProblem({ ...item, evidence: [{ isStale: true }] }), '')
  assert.notEqual(reviewProblem({ itemType: 'MEDICATION', payload: { name: '약', doseText: '1정', frequencyText: '하루 1회', startsOn: '2026-10-08', endsOn: '2026-10-10', schedulePlans: [] } }), '')
})

test('evidence offsets use Unicode code points rather than UTF-16 indices', () => {
  assert.equal(evidenceExcerpt('가😀나 검사', { startOffset: 2, endOffset: 3 }), '나')
})
