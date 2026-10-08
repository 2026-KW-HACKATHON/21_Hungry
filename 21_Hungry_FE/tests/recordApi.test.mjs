import test from 'node:test'
import assert from 'node:assert/strict'
import { createAudioUploadAttempt, uploadAudioAttempt, requestRecordApi } from '../src/api/recordApi.js'

const ok = (data) => ({ ok: true, json: async () => ({ data }) })

test('upload retry reuses encounter, bytes, metadata and key after response loss', async (t) => {
  const calls = []
  let uploads = 0
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('/encounters')) return ok({ id: 'record-1', version: 3, inputVersion: 4 })
    if (++uploads === 1) throw new TypeError('Network response lost')
    return ok({ job: { id: 'job-1' } })
  })
  const attempt = createAudioUploadAttempt('token', 'group-1', new Blob(['audio'], { type: 'audio/wav' }), ' 병원 ')
  await assert.rejects(uploadAudioAttempt(attempt), /Network/)
  const result = await uploadAudioAttempt(attempt)
  assert.equal(result.encounterId, 'record-1')
  assert.equal(calls.filter((call) => call.url.endsWith('/encounters')).length, 1)
  assert.equal(calls[1].options.headers['Idempotency-Key'], calls[2].options.headers['Idempotency-Key'])
  assert.equal(calls[1].options.body, calls[2].options.body)
  assert.equal(calls[1].options.headers['Content-Type'], undefined)
  assert.equal(calls[1].options.cache, 'no-store')
  assert.deepEqual(JSON.parse(await attempt.formData.get('metadata').text()), { expectedVersion: 3, expectedInputVersion: 4 })
  assert.equal(await attempt.formData.get('file').text(), 'audio')
  assert.equal(JSON.parse(calls[0].options.body).hospitalName, '병원')
})

test('record creation retry keeps its key and body when response is lost', async (t) => {
  const creations = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/encounters')) {
      creations.push(options)
      if (creations.length === 1) throw new TypeError('Network response lost')
      return ok({ id: 'record-2', version: 0, inputVersion: 1 })
    }
    return ok({})
  })
  const attempt = createAudioUploadAttempt('token', 'group-1', new Blob(['audio']), '')
  await assert.rejects(uploadAudioAttempt(attempt))
  await uploadAudioAttempt(attempt)
  assert.equal(creations[0].headers['Idempotency-Key'], creations[1].headers['Idempotency-Key'])
  assert.equal(creations[0].body, creations[1].body)
  assert.equal(JSON.parse(creations[0].body).hospitalName, null)
})

test('v1.1 group path, auth, and validation failures remain distinguishable', async (t) => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/v1/me/care-groups')
    assert.equal(options.headers.Authorization, 'Bearer token')
    return ok({ items: [] })
  })
  assert.deepEqual(await requestRecordApi('/me/care-groups', 'token'), { items: [] })
  globalThis.fetch = async () => ({ ok: false, status: 409, json: async () => ({ error: { code: 'VERSION_CONFLICT', message: 'refresh required' } }) })
  await assert.rejects(requestRecordApi('/test', 'token'), (error) => error.status === 409 && error.code === 'VERSION_CONFLICT')
})