import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { me, getJoinRequests, cancelJoinRequest } from '../../api/authApi'
import { getAccessToken, messageOf } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import './SignupWaiting.css'

function SignupWaiting() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const cancelAttempt = useRef(null)

  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    if (busy) return

    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()
    let timer

    const poll = async () => {
      try {
        const data = await getJoinRequests({
          signal: controller.signal,
        })

        if (controller.signal.aborted) return

        const request = data.request

        if (request?.status === 'REJECTED') {
          setError('가족 참여 요청이 거절되었어요.')
          return
        }

        if (request?.status === 'CANCELED') {
          setError('가족 참여 요청이 취소되었어요.')
          return
        }

        const user = await me({
          signal: controller.signal,
        })

        if (controller.signal.aborted) return

        if (request?.status === 'APPROVED' && user.membership?.status === 'ACTIVE') {
          navigate('/signupwaitingdone', { replace: true })
          return
        }

        setError('')
      } catch (e) {
        if (controller.signal.aborted) return

        setError(e.message || messageOf(e))

        if (e.response?.status === 401) return
      }

      if (!controller.signal.aborted) {
        timer = window.setTimeout(poll, 5000)
      }
    }

    poll()

    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [navigate, busy, refresh])

  const cancel = async () => {
    if (submitting.current) return

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      const data = await getJoinRequests()
      const request = data.request

      if (!request?.id) {
        throw new Error('취소할 가족 참여 요청이 없어요.')
      }

      if (request.status === 'APPROVED') {
        const user = await me()

        if (user.membership?.status === 'ACTIVE') {
          navigate('/signupwaitingdone', { replace: true })
          return
        }

        throw new Error('현재 가족 연결 상태를 다시 확인해 주세요.')
      }

      if (request.status !== 'PENDING') {
        throw new Error('이미 처리된 가족 참여 요청이에요.')
      }

      const operation = `${request.id}:${request.version}`

      if (cancelAttempt.current?.operation !== operation) {
        cancelAttempt.current = {
          operation,
          key: crypto.randomUUID(),
        }
      }

      await cancelJoinRequest(request.id, request.version, cancelAttempt.current.key)

      cancelAttempt.current = null

      navigate('/signupsubuser', { replace: true })
    } catch (e) {
      setError(e.message || messageOf(e))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <main className='SignupWaitingPage'>
      <h1 className='SignupWaitingPage__title'>요청을 승인하고 있어요</h1>

      {error && <p role='status'>{error}</p>}

      <BottomButton
        content='승인 상태 확인'
        onClick={() => setRefresh((value) => value + 1)}
        disabled={busy}
      />

      <button type='button' onClick={cancel} disabled={busy}>
        가족 참여 요청 취소
      </button>
    </main>
  )
}

export default SignupWaiting
