import './FamilyReqPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import PopupButton from '../../components/popup-button/PopupButton'
import { getAccessToken, messageOf } from '../../api/http'
import { decideFamilyRequest, getFamilyRequestContext } from '../../api/familyReqApi'

function formatRequestDate(value) {
  const date = new Date(value)

  if (!value || Number.isNaN(date.getTime())) return ''

  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
  }).formatToParts(date)

  const part = (type) => parts.find((item) => item.type === type)?.value

  return `${part('year')}년 ${part('month')}월 ${part('day')}일 요청`
}

function FamilyReqPage() {
  const navigate = useNavigate()

  const [result, setResult] = useState(null)
  const [retry, setRetry] = useState(0)
  const [submittingId, setSubmittingId] = useState('')
  const [actionError, setActionError] = useState('')

  const submittingRef = useRef(false)
  const mountedRef = useRef(false)
  const requestKeys = useRef(new Map())

  const current = result?.key === retry ? result : null
  const context = current?.data
  const group = context?.group
  const requests = context?.requests || []

  const canApprove = Boolean(context?.canApprove)
  const isLoading = !current
  const isSubmitting = Boolean(submittingId)
  const error = current?.error || actionError

  useEffect(() => {
    mountedRef.current = true
    const controller = new AbortController()

    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
    } else {
      getFamilyRequestContext({ signal: controller.signal })
        .then((data) => {
          if (!controller.signal.aborted) {
            setResult({
              key: retry,
              data,
              error: '',
            })
          }
        })
        .catch((error) => {
          if (!controller.signal.aborted) {
            setResult({
              key: retry,
              data: null,
              error: error.message || messageOf(error),
            })
          }
        })
    }

    return () => {
      mountedRef.current = false
      controller.abort()
    }
  }, [navigate, retry])

  const handleDecision = async (request, decision) => {
    if (!group || !canApprove || submittingRef.current) return

    submittingRef.current = true
    setSubmittingId(request.id)
    setActionError('')

    const signature = JSON.stringify([group.id, request.id, request.version, decision])

    if (!requestKeys.current.has(signature)) {
      requestKeys.current.set(signature, crypto.randomUUID())
    }

    try {
      await decideFamilyRequest(group.id, request, decision, requestKeys.current.get(signature))

      if (mountedRef.current) {
        navigate('/family')
      }
    } catch (error) {
      if (!mountedRef.current) return

      setActionError(error.message || messageOf(error))

      if (error.status === 409 || error.status === 403) {
        setRetry((value) => value + 1)
      }
    } finally {
      submittingRef.current = false

      if (mountedRef.current) {
        setSubmittingId('')
      }
    }
  }

  const reload = () => {
    setActionError('')
    setRetry((value) => value + 1)
  }

  return (
    <div className='familyReq__page'>
      <BackHeader
        content='가족 참여 요청'
        subcontent={'함께 돌보는 가족의 참여를\n승인할 수 있어요'}
      />

      <div className='familyReq__content'>
        {isLoading && (
          <p role='status' className='familyReq__status'>
            요청을 불러오고 있어요.
          </p>
        )}

        {error && (
          <div role='alert' className='familyReq__error'>
            <p>{error}</p>

            <PopupButton
              content='다시 불러오기'
              color='gray'
              disabled={isLoading || isSubmitting}
              onClick={reload}
            />
          </div>
        )}

        {context && !group && (
          <p className='familyReq__status'>가족 연결을 완료하면 참여 요청을 확인할 수 있어요.</p>
        )}

        {group && !canApprove && (
          <p className='familyReq__status'>가족 참여 요청은 주돌봄자녀만 확인할 수 있어요.</p>
        )}

        {canApprove && requests.length === 0 && (
          <div className='familyReq__empty'>가족 참여 요청이 없어요</div>
        )}

        {requests.map((request) => (
          <div className='familyReq__card' key={request.id}>
            <div className='familyReq__card--info'>
              <div className='familyReq__card--name'>{request.user.displayName || '-'}</div>

              <div className='familyReq__card--role'>공동돌봄자녀</div>

              <div className='familyReq__card--date'>{formatRequestDate(request.createdAt)}</div>
            </div>

            <fieldset className='familyReq__card--button' disabled={!canApprove || isSubmitting}>
              <PopupButton
                content='거부하기'
                color='gray'
                onClick={() => handleDecision(request, 'REJECT')}
              />

              <PopupButton
                content='승인하기'
                color='blue'
                onClick={() => handleDecision(request, 'APPROVE')}
              />
            </fieldset>

            {submittingId === request.id && (
              <p role='status' className='familyReq__status'>
                요청을 처리하고 있어요.
              </p>
            )}
          </div>
        ))}

        <div className='familyReq__notices'>
          <div className='familyReq__notice'>동의 전에는 기록을 열람할 수 없어요</div>

          <div className='familyReq__notice'>
            승인하면 가족 공간에 추가되고, 자녀가 같은 건강 기록과 돌봄 일정 기능을 사용할 수 있어요
          </div>
        </div>
      </div>
    </div>
  )
}

export default FamilyReqPage
