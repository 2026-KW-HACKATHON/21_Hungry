
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import { me, navigateForOnboarding, getJoinRequests, cancelJoinRequest } from '../../api/authApi'
import { messageOf } from '../../api/http'
import './SignupWaiting.css'

export default function SignupWaiting() {
  const navigate=useNavigate()
  const [error,setError]=useState('')
  const [busy,setBusy]=useState(false)

  const check=async()=>{
    if (busy) return
    setBusy(true)
    try{
      const request=await getJoinRequests()
      const status=request?.status ?? request?.item?.status ?? request?.items?.[0]?.status
      if(status === 'REJECTED' || status === 'CANCELED' || status === 'CANCELLED') {
        setError(status === 'REJECTED' ? '가족 참여 요청이 거절되었어요.' : '가족 참여 요청이 취소되었어요.')
        return
      }
      const user=await me()
      if(user.onboardingState==='WAITING_APPROVAL') setError('아직 승인 대기 중입니다.')
      else navigateForOnboarding(navigate,user)
    }catch(e){ setError(messageOf(e)) }
    finally { setBusy(false) }
  }

  const cancel=async()=>{
    if (busy) return
    setBusy(true)
    setError('')
    try {
      const data=await getJoinRequests()
      const request=data?.item ?? data?.items?.[0] ?? data
      if (!request?.id && !request?.requestId) throw new Error('취소할 가족 참여 요청이 없어요.')
      await cancelJoinRequest(request.id ?? request.requestId, request.version)
      navigate('/loginselect', { replace: true })
    } catch(e) { setError(messageOf(e)) }
    finally { setBusy(false) }
  }

  return (
    <main className="SignupWaitingPage">
      <h1 className="SignupWaitingPage__title">
        요청을 승인하고 있어요
      </h1>
      {error && <p role="status">{error}</p>}
      <BottomButton content="승인 상태 확인" onClick={check} disabled={busy}/>
      <button type="button" onClick={cancel} disabled={busy}>가족 참여 요청 취소</button>
    </main>
  )
}
