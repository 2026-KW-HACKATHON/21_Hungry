import './FamilyReqPage.css'

import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import PopupButton from '../../components/popup-button/PopupButton'
import {
  approveFamilyRequest,
  canApproveFamilyRequests,
  getFamilyRequests,
} from '../../mocks/familyMock'
import { formatDate } from '../../mocks/todayAddMock'

function FamilyReqPage() {
  const navigate = useNavigate()
  const [requests] = useState(() => getFamilyRequests())
  const [isApproving, setIsApproving] = useState(false)
  const approvingRef = useRef(false)
  const canApprove = canApproveFamilyRequests()

  const handleApprove = async (requestId) => {
    if (!canApprove || approvingRef.current) return

    approvingRef.current = true
    setIsApproving(true)
    try {
      await approveFamilyRequest(requestId)
      navigate('/family')
    } catch (error) {
      window.alert(
        error instanceof Error ? error.message : '요청을 승인하지 못했어요. 다시 시도해 주세요.',
      )
    } finally {
      approvingRef.current = false
      setIsApproving(false)
    }
  }

  return (
    <div className='familyReq__page'>
      <BackHeader
        content='가족 참여 요청'
        subcontent={'함께 돌보는 가족의 참여를\n승인할 수 있어요'}
      />

      <div className='familyReq__content'>
        {requests.length === 0 ? (
          <div className='familyReq__empty'>가족 참여 요청이 없어요</div>
        ) : (
          requests.map((request) => (
            <div className='familyReq__card' key={request.id}>
              <div className='familyReq__card--info'>
                <div className='familyReq__card--name'>{request.name}</div>
                <div className='familyReq__card--role'>{request.roleLabel}</div>
                <div className='familyReq__card--date'>
                  {formatDate(request.requestedDate)} 요청
                </div>
              </div>

              <fieldset className='familyReq__card--button' disabled={!canApprove || isApproving}>
                <PopupButton
                  content='승인하기'
                  color='green'
                  onClick={() => handleApprove(request.id)}
                />
              </fieldset>
            </div>
          ))
        )}

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
