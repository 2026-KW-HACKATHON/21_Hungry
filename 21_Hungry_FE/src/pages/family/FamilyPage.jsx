import './FamilyPage.css'

import { useNavigate } from 'react-router-dom'

import TitleHeader from '../../components/title-header/TitleHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import { currentUserId, familyMemberData } from '../../mocks/familyMock'
import { formatDate } from '../../mocks/todayAddMock'

function FamilyPage() {
  const navigate = useNavigate()

  return (
    <div className='family__page'>
      <TitleHeader
        content='가족 관리'
        subcontent={'AI가 개인 일정을 분석해\n돌봄 일정마다 담당자를 정해드려요.'}
      />

      <div className='family__content'>
        {familyMemberData.map((member) => {
          const isMe = member.userId === currentUserId
          const relationship = member.role === 'RECIPIENT' ? member.category : null

          return (
            <div
              className={`family__card${member.role === 'RECIPIENT' ? ' family__card--parent' : ''}`}
              key={member.userId}
            >
              <div className='family__card--info'>
                <div className='family__card--name'>
                  {member.name}
                  {isMe ? '(나)' : relationship && `(${relationship})`}
                </div>
                <div className='family__card--role'>{member.roleLabel}</div>
                {member.approvedDate && (
                  <div className='family__card--date'>
                    {formatDate(member.approvedDate)} 요청 승인
                  </div>
                )}
              </div>

              {!isMe && (
                <div className='family__card--button'>
                  <PopupButton
                    content='개인 일정 조회하기'
                    color='gray'
                    onClick={() =>
                      navigate(`/familyschedule?userId=${encodeURIComponent(member.userId)}`)
                    }
                  />
                </div>
              )}
            </div>
          )
        })}
      </div>

      <BottomButton content='가족 참여 요청 보기' onClick={() => navigate('/familyreq')} />
    </div>
  )
}

export default FamilyPage
