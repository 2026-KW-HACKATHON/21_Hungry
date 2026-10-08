import { useEffect, useRef, useState } from 'react'
import { getSourceBlob, requestRecordApi, createRecordMutation, sendRecordMutation } from '../../api/recordApi'
import { evidenceExcerpt } from '../../api/recordModel'

function SourceText({ source, accessToken, inputVersion, onSaved, evidence }) {
  const [result, setResult] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const [draft, setDraft] = useState(null)
  const [busy, setBusy] = useState(false)
  const [attempt, setAttempt] = useState(null)
  const [message, setMessage] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    requestRecordApi(`/sources/${encodeURIComponent(source.id)}/text`, accessToken, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setResult({ data }) })
      .catch((error) => { if (!controller.signal.aborted) setResult({ error: error.message }) })
    return () => controller.abort()
  }, [source.id, source.textVersion, accessToken, refresh])
  async function save() {
    if (busy || !draft?.trim()) return
    const next = attempt || createRecordMutation(`/sources/${encodeURIComponent(source.id)}/text`, {
      expectedInputVersion: inputVersion, expectedTextVersion: result.data.textVersion, text: draft,
    }, 'PATCH')
    setAttempt(next)
    setBusy(true)
    try { await sendRecordMutation(next, accessToken); setDraft(null); setAttempt(null); onSaved() }
    catch (error) { setMessage(error.message); if (error.status >= 400 && error.status < 500 && error.apiCode !== 'REQUEST_IN_PROGRESS') { setAttempt(null); if (error.status === 409) onSaved() } }
    finally { setBusy(false) }
  }
  if (result?.error) return <><p role="alert">{result.error}</p><button onClick={() => setRefresh((value) => value + 1)}>원문 다시 조회</button></>
  if (!result) return <p>원문을 불러오는 중이에요.</p>
  if (result.data.status !== 'READY') return <p>원문이 아직 준비되지 않았어요.</p>
  const stale = evidence && (evidence.isStale || evidence.textVersion !== result.data.textVersion)
  return <>
    {stale && <p role="status">원문이 변경되어 이전 근거 구간과 다를 수 있어요.</p>}
    <p className="recordDetail__text">{evidence && !stale ? evidenceExcerpt(result.data.text, evidence) : result.data.text || '원문 없음'}</p>
    {!evidence && source.sourceType === 'AUDIO' && draft === null && <button onClick={() => setDraft(result.data.text || '')}>원문 수정</button>}
    {draft !== null && <div>
      <label>녹음 원문<textarea value={draft} disabled={busy || Boolean(attempt)} onChange={(event) => setDraft(event.target.value)} /></label>
      <button disabled={busy || !draft.trim()} onClick={save}>{busy ? '저장 중…' : attempt ? '같은 내용 다시 저장' : '수정하고 다시 분석'}</button>
      <button disabled={busy || Boolean(attempt)} onClick={() => setDraft(null)}>취소</button>
    </div>}
    {message && <p role="alert">{message}</p>}
  </>
}

function EvidenceItem({ source, evidence, index, accessToken, inputVersion }) {
  const [open, setOpen] = useState(false)
  return <details onToggle={(event) => setOpen(event.currentTarget.open)} className="recordDetail__evidence">
    <summary>원문 근거 {index + 1}{evidence.page ? ` · ${evidence.page}페이지` : ''}</summary>
    {open && (source ? <SourceText key={`${source.id}:${source.textVersion}`} source={source} evidence={evidence} accessToken={accessToken} inputVersion={inputVersion} /> : <p>이 근거의 원본을 이용할 수 없어요.</p>)}
  </details>
}

export function Evidence({ items = [], sources, accessToken, inputVersion }) {
  return items.map((evidence, index) => {
    const source = sources.find((item) => item.id === evidence.sourceId && !item.removedAt)
    return <EvidenceItem key={`${evidence.sourceId}-${index}`} source={source} evidence={evidence} index={index} accessToken={accessToken} inputVersion={inputVersion} />
  })
}

function Source({ source, accessToken, inputVersion, onSaved }) {
  const [open, setOpen] = useState(false)
  const [url, setUrl] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  async function showFile() {
    if (busy) return
    setBusy(true)
    try {
      const blob = await getSourceBlob(source.id, accessToken)
      if (!mounted.current) return
      setUrl(URL.createObjectURL(blob))
    } catch (error) { if (mounted.current) setMessage(error.message) }
    finally { if (mounted.current) setBusy(false) }
  }
  return <article className="recordDetail__source">
    <h3>{source.sourceType === 'AUDIO' ? '녹음 원문' : source.file?.originalName || '관련 문서'}</h3>
    <button onClick={() => setOpen(!open)}>{open ? '원문 접기' : '원문 보기'}</button>
    {open && <SourceText source={source} accessToken={accessToken} inputVersion={inputVersion} onSaved={onSaved} />}
    {source.sourceType === 'DOCUMENT' && source.contentPath && <button disabled={busy} onClick={showFile}>문서 원본 열기</button>}
    {url && <div>
      {source.file?.mediaType === 'application/pdf' ? <iframe title="문서 원본" src={url} style={{ width: '100%', height: 450 }} /> : <img src={url} alt="첨부 문서 원본" style={{ width: '100%' }} />}
      <a href={url} download={source.file?.originalName || 'document'}>원본 다운로드</a>
      <button onClick={() => setUrl('')}>원본 닫기</button>
    </div>}
    {message && <p role="alert">{message}</p>}
  </article>
}

export default function RecordSources({ detail, accessToken, onSaved }) {
  return <section className="recordDetail__panel"><h2>원문·관련 문서</h2>
    {(detail.sources ?? []).filter((source) => !source.removedAt).map((source) => <Source key={`${source.id}:${source.textVersion}`} source={source} accessToken={accessToken} inputVersion={detail.inputVersion} onSaved={onSaved} />)}
  </section>
}
