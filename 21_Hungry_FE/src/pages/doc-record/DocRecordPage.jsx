import './DocRecordPage.css'

import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import BackHeader from '../../components/back-header/BackHeader'
import CardInfo from '../../components/card-info/CardInfo'
import PopupButton from '../../components/popup-button/PopupButton'
import { getAccessToken, messageOf } from '../../api/http'
import { requestRecordApi } from '../../api/recordApi'
import { recordProgress } from '../../api/recordModel'
import { useRecordDetail } from '../record-analysis/useRecordDetail'

const summarySections = ['symptoms', 'tests', 'medicationMentions', 'precautions', 'followUps']

function formatDate(date) {
  if (!date) return '--년 --월 --일'
  const [year, month, day] = date.split('-')
  return `${year}년 ${Number(month)}월 ${Number(day)}일`
}

function AudioTranscript({ source, accessToken }) {
  const [result, setResult] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const pendingRef = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    pendingRef.current = true
    requestRecordApi(`/sources/${encodeURIComponent(source.id)}/text`, accessToken, {
      signal: controller.signal,
    })
      .then((data) => {
        if (isActive()) setResult({ attempt, data, error: '' })
      })
      .catch((error) => {
        if (isActive()) {
          setResult({ attempt, data: null, error: error.message || messageOf(error) })
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) pendingRef.current = false
      })

    return () => controller.abort()
  }, [source.id, accessToken, attempt])

  const current = result?.attempt === attempt ? result : null
  const handleRetry = () => {
    if (pendingRef.current) return
    pendingRef.current = true
    setAttempt((value) => value + 1)
  }

  if (!current) return <p role='status'>녹음 원문을 불러오는 중이에요.</p>

  if (current.error) {
    return (
      <>
        <p className='docRecord__error' role='alert'>
          {current.error}
        </p>
        <PopupButton content='원문 다시 조회' color='gray' onClick={handleRetry} />
      </>
    )
  }

  if (current.data.status !== 'READY') {
    return (
      <>
        <p role={current.data.status === 'FAILED' ? 'alert' : 'status'}>
          {current.data.status === 'FAILED'
            ? '녹음 원문 처리에 실패했어요.'
            : '녹음 원문이 아직 준비되지 않았어요.'}
        </p>
        <PopupButton content='원문 다시 조회' color='gray' onClick={handleRetry} />
      </>
    )
  }

  return <p>{current.data.text || '-'}</p>
}

function VisitRecord({ encounterId, accessToken }) {
  const navigate = useNavigate()
  const { detail, message, stopped, reload } = useRecordDetail(encounterId, accessToken)
  const summary = detail?.summary
  const isStale = Boolean(
    detail?.isSummaryStale || (summary && summary.inputVersion !== detail.inputVersion),
  )
  const progress = detail ? recordProgress(detail) : null
  const showStatus = !detail || !progress.done || detail.processingState !== 'READY' || isStale
  const points = summarySections
    .flatMap((section) => summary?.details?.[section] || [])
    .filter((point) => typeof point?.text === 'string' && point.text.trim())
  const statusMessage =
    detail?.processingState === 'NEEDS_REVIEW' && !isStale
      ? '분석이 완료됐어요. 확인이 필요한 항목이 있어요.'
      : message
  const audioSources = (detail?.sources || []).filter(
    (source) => source.sourceType === 'AUDIO' && !source.removedAt,
  )

  if (detail && detail.recordType !== 'VISIT') {
    return (
      <div className='docRecord__content'>
        <p className='docRecord__error' role='alert'>
          진료 기록을 선택해 주세요.
        </p>
        <PopupButton content='보관함으로 이동' color='gray' onClick={() => navigate('/doc')} />
      </div>
    )
  }

  return (
    <div
      className='docRecord__content'
      aria-busy={Boolean(accessToken && encounterId && !detail && !stopped)}
    >
      {showStatus && (
        <div className='docRecord__status'>
          <p role={stopped && !detail ? 'alert' : 'status'}>{statusMessage}</p>
          {!accessToken && (
            <PopupButton
              content='로그인하기'
              color='gray'
              onClick={() => navigate('/loginselect')}
            />
          )}
          {accessToken && !encounterId && (
            <PopupButton content='보관함으로 이동' color='gray' onClick={() => navigate('/doc')} />
          )}
          {accessToken && encounterId && stopped && (
            <PopupButton content='상태 다시 조회' color='gray' onClick={reload} />
          )}
        </div>
      )}
      {detail && (
        <>
          <div className='docRecord__card'>
            <CardInfo
              category='진료 기록'
              label1='등록일'
              value1={formatDate(detail.occurredOn)}
              label2='발급기관'
              value2={detail.hospitalName || '-'}
              label3='등록인'
              value3={detail.createdBy?.displayName || '-'}
            />
          </div>
          <section className='docRecord__summary'>
            <h2>쉬운 요약</h2>
            {isStale && (
              <p role='status'>이전 자료 기준의 분석이에요. 최신 결과는 아직 준비되지 않았어요.</p>
            )}
            <p>{summary?.text || '-'}</p>
          </section>
          {points.length > 0 && (
            <section className='docRecord__keyPoints'>
              <h2>진료 핵심</h2>
              <ol>
                {points.map((point, index) => (
                  <li key={index}>
                    <span className='docRecord__number' aria-hidden='true'>
                      {index + 1}
                    </span>
                    <p>{point.text}</p>
                  </li>
                ))}
              </ol>
            </section>
          )}
          <section className='docRecord__transcript'>
            <h2>녹음 원문</h2>
            {audioSources.length ? (
              audioSources.map((source) => (
                <AudioTranscript
                  key={`${source.id}:${source.textVersion}:${source.status}`}
                  source={source}
                  accessToken={accessToken}
                />
              ))
            ) : (
              <p>-</p>
            )}
          </section>
          <p className='docRecord__notice'>
            내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를
            몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
          </p>
        </>
      )}
    </div>
  )
}

function DocRecordPage() {
  const [params] = useSearchParams()
  const { state } = useLocation()
  const encounterId = params.get('encounterId') || state?.encounterId || ''
  const accessToken = getAccessToken()

  return (
    <div className='docRecord__page'>
      <BackHeader content='문서 열람' />
      <VisitRecord
        key={`${encounterId}:${accessToken || 'signed-out'}`}
        encounterId={encounterId}
        accessToken={accessToken}
      />
    </div>
  )
}

export default DocRecordPage
