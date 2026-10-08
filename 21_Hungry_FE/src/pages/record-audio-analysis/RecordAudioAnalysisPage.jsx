import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'
import { requestRecordApi } from '../../api/recordApi'
import './RecordAudioAnalysisPage.css'

const companionLabels = { self: '본인', other: '다른 자녀', none: '동행 없음' }
const detailSections = [
  ['symptoms', '증상'],
  ['tests', '검사'],
  ['medicationMentions', '약 관련 내용'],
  ['precautions', '주의 사항'],
  ['followUps', '추후 진료'],
]

function getData(path, accessToken, signal) {
  return requestRecordApi(path, accessToken, { signal })
}

function getProgress(record) {
  const jobs = (record.jobs ?? []).filter((job) => job.inputVersion === record.inputVersion)
  const failed = jobs.find((job) => job.status === 'FAILED')
  if (record.processingState === 'FAILED' || failed) {
    return { done: true, message: [failed?.error?.message, failed?.error?.userAction].filter(Boolean).join(' ') || '녹음 처리에 실패했어요. 잠시 후 상태를 다시 확인해 주세요.' }
  }
  if (['READY', 'NEEDS_REVIEW'].includes(record.processingState)) {
    if (record.isSummaryStale || record.summary?.inputVersion !== record.inputVersion) {
      return { done: true, message: '최신 분석 결과가 아직 없어요. 잠시 후 다시 조회해 주세요.' }
    }
    return {
      done: true,
      message: record.processingState === 'NEEDS_REVIEW'
        ? '분석이 완료됐어요. 약·일정 등 확인이 필요한 항목이 있어요.'
        : '분석이 완료됐어요. 기록은 서버에 저장되어 있어요.',
    }
  }
  if (record.processingState === 'EMPTY') {
    return { done: true, message: '이 기록에 업로드된 자료가 없어요.' }
  }
  if (jobs.length && jobs.every((job) => job.status === 'OBSOLETE')) {
    return { done: true, message: '입력이 변경되어 이전 작업이 종료됐어요. 최신 상태를 다시 조회해 주세요.' }
  }
  const messages = {
    TRANSCRIBING: '녹음을 글로 변환하고 있어요.',
    OCR_PROCESSING: '첨부 자료를 읽고 있어요.',
    ANALYZING: '진료 내용을 분석하고 있어요.',
  }
  return { done: false, message: messages[record.processingState] || '분석 작업을 기다리고 있어요.' }
}

function RecordAudioAnalysisPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const record = state ?? {}
  const encounterId = record.encounterId
  const accessToken = sessionStorage.getItem('accessToken')
  const [result, setResult] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const [message, setMessage] = useState('')
  const [retrying, setRetrying] = useState(false)
  const retryLock = useRef(false)
  const retryKeys = useRef(new Map())
  const seconds = Math.max(0, Math.floor(Number(record.seconds) || 0))
  const duration = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value) => String(value).padStart(2, '0')).join(':')

  useEffect(() => {
    if (!encounterId || !accessToken) return
    const controller = new AbortController()
    const { signal } = controller
    const textCache = new Map()
    const startedAt = Date.now()
    let timer

    async function poll() {
      try {
        const detail = await getData(`/encounters/${encodeURIComponent(encounterId)}`, accessToken, signal)
        const sources = (detail.sources ?? []).filter((source) => source.sourceType === 'AUDIO' && !source.removedAt)
        const texts = await Promise.all(sources.map(async (source) => {
          if (source.status !== 'READY') return ''
          const cacheKey = `${source.id}:${source.textVersion}`
          if (!textCache.has(cacheKey)) {
            const text = await getData(`/sources/${encodeURIComponent(source.id)}/text`, accessToken, signal)
            if (text.status === 'READY') textCache.set(cacheKey, text.text ?? '')
          }
          return textCache.get(cacheKey) ?? ''
        }))
        if (signal.aborted) return
        const progress = getProgress(detail)
        const timedOut = Date.now() - startedAt >= 10 * 60 * 1000
        setResult({
          encounterId,
          accessToken,
          refresh,
          detail,
          transcript: texts.filter(Boolean).join('\n\n'),
          message: timedOut && !progress.done
            ? '처리가 오래 걸리고 있어요. 서버 처리는 계속되며 다시 조회할 수 있어요.'
            : progress.message,
          stopped: progress.done || timedOut,
        })
        if (!progress.done && !timedOut) {
          timer = setTimeout(poll, Date.now() - startedAt > 60000 ? 5000 : 2000)
        }
      } catch (error) {
        if (signal.aborted) return
        setResult({
          encounterId,
          accessToken,
          refresh,
          detail: null,
          transcript: '',
          message: error instanceof Error ? error.message : '기록 조회에 실패했어요.',
          stopped: true,
        })
      }
    }

    timer = setTimeout(poll, 0)
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [encounterId, accessToken, refresh])

  const current = result?.encounterId === encounterId && result?.accessToken === accessToken && result?.refresh === refresh ? result : null
  const detail = current?.detail
  const summary = detail?.summary
  const summaryStale = Boolean(summary && (detail.isSummaryStale || summary.inputVersion !== detail.inputVersion))
  const retryableJobs = (detail?.jobs ?? []).filter((job) => job.inputVersion === detail.inputVersion && job.status === 'FAILED' && job.canRetry)
  const highlights = detailSections.flatMap(([key, label]) =>
    (summary?.details?.[key] ?? []).filter((item) => item.text).map((item) => `${label}: ${item.text}`)
  )
  const feedback = !encounterId ? '기록 ID가 없어요. 녹음 업로드 후 이 화면으로 이동해 주세요.'
    : !accessToken ? '로그인이 필요해요.' : current?.message || '기록을 불러오고 있어요.'

  async function retryJob(job) {
    if (retryLock.current) return
    retryLock.current = true
    setRetrying(true)
    setMessage('실패한 작업을 다시 요청하고 있어요.')
    const operation = `${accessToken}:${job.id}:${job.inputVersion}:${job.attemptCount}`
    if (!retryKeys.current.has(operation)) retryKeys.current.set(operation, crypto.randomUUID())
    try {
      await requestRecordApi(`/processing-jobs/${encodeURIComponent(job.id)}/retry`, accessToken, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': retryKeys.current.get(operation) },
        body: JSON.stringify({ expectedInputVersion: detail.inputVersion }),
      })
      retryKeys.current.delete(operation)
      setMessage('')
      setRefresh((value) => value + 1)
    } catch (error) {
      setMessage(error.message || '작업 재시도에 실패했어요.')
      if ([403, 404, 409].includes(error.status)) setRefresh((value) => value + 1)
    } finally {
      retryLock.current = false
      setRetrying(false)
    }
  }
  return (
    <main className="recordAudioAnalysisPage">
      <header className="recordAudioAnalysisPage__header">
        <button type="button" className="recordAudioAnalysisPage__back" aria-label="진료 녹음하기로 돌아가기"
          onClick={() => navigate('/record/audio', { state: record })}>
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>진료 기록 분석</h1>
      </header>
      <div className="recordAudioAnalysisPage__content">
        <dl className="recordAudioAnalysisPage__details">
          <div><dt>병원</dt><dd>{detail ? detail.hospitalName || '-' : record.hospital || '-'}</dd></div>
          <div><dt>진료 과목</dt><dd>{record.department || '-'}</dd></div>
          <div><dt>동행한 자녀</dt><dd>{companionLabels[record.companion] || '-'}</dd></div>
        </dl>
        <div className="recordAudioAnalysisPage__duration" aria-label="녹음 시간">{duration}</div>
        <section className="recordAudioAnalysisPage__summary" aria-labelledby="audio-summary-title">
          <h2 id="audio-summary-title">쉬운 요약</h2>
          {summaryStale && <p role="status">이전 입력 기준의 요약과 진료 핵심이에요. 최신 분석 결과는 아직 준비되지 않았어요.</p>}
          <p>{summary?.text || '-'}</p>
        </section>
        <section className="recordAudioAnalysisPage__highlights" aria-labelledby="audio-highlights-title">
          <h2 id="audio-highlights-title">진료 핵심</h2>
          <ol>
            {(highlights.length ? highlights : ['-']).map((text, index) => (
              <li key={index}><span aria-hidden="true">{index + 1}</span><p>{text}</p></li>
            ))}
          </ol>
        </section>
        <section className="recordAudioAnalysisPage__memo" aria-labelledby="audio-transcript-title">
          <h2 id="audio-transcript-title">녹음 원문</h2>
          <p>{current?.transcript || '-'}</p>
        </section>
        <p className="recordAudioAnalysisPage__notice recordAudioAnalysisPage__legal">
          내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
        </p>
        <p className="recordAudioAnalysisPage__notice">업로드한 기록은 서버에 저장되어 있어요.</p>
        <p className="recordAudioAnalysisPage__message" role="status">{message || feedback}</p>
        {retryableJobs.map((job) => (
          <button key={job.id} type="button" className="recordAudioAnalysisPage__refresh" disabled={retrying}
            onClick={() => retryJob(job)}>{job.jobType === 'TRANSCRIBE' ? '전사' : job.jobType === 'ANALYZE' ? '분석' : '문서 추출'} 다시 시도</button>
        ))}
        {current?.stopped && (
          <button type="button" className="recordAudioAnalysisPage__refresh" disabled={retrying} onClick={() => {
            setMessage('')
            setRefresh((value) => value + 1)
          }}>상태 다시 조회</button>
        )}
      </div>
      <BottomButton content="저장하기" onClick={() => {
        if (!detail) { setMessage('기록 조회가 완료된 뒤 다시 눌러 주세요.'); return }
        navigate('/doc', { state: { encounterId } })
      }} />
    </main>
  )
}

export default RecordAudioAnalysisPage