import './DayCard.css'
import { useNavigate } from 'react-router-dom'
import CardInfo from '../../../components/card-info/CardInfo'
import PopupButton from '../../../components/popup-button/PopupButton'

/* schedule : { id, category, date, time, family, description } */

function DayCard({ schedule, task, userId, recipientId, busy, onComplete }) {
  const navigate = useNavigate()
  const isMine = task.assignee?.id === userId
  const isParent = task.assignee?.id === recipientId
  const isCompleting = busy?.id === task.id && busy.action === 'complete'

  const statusText = !task.assignee
    ? '미지정된 일정이에요'
    : isMine
      ? '완료로 표시하기'
      : isParent
        ? '부모님께 알림 보내기'
        : '다른 가족이 맡은 일정이에요'

  return (
    <div className='dayCard__container'>
      <CardInfo
        category={schedule.category}
        label1='날짜'
        label2='시간'
        label3='담당 가족'
        value1={schedule.date}
        value2={schedule.time}
        value3={schedule.family}
      />

      {(schedule.description?.trim() || !isMine) && (
        <div className='dayCard__description'>
          <p>{schedule.description || '-'}</p>
        </div>
      )}

      <div className='dayCard__buttons'>
        <PopupButton
          content={isCompleting ? '완료 처리 중...' : statusText}
          color={task.assignee ? 'gray' : 'notice'}
          disabled={!isMine || Boolean(busy)}
          onClick={isMine ? onComplete : undefined}
          title={isParent && !isMine ? '부모님 알림 발송 API 연결 후 사용할 수 있어요.' : undefined}
        />
        <PopupButton
          content='이 일정 수정하기'
          color='green'
          disabled={Boolean(busy)}
          onClick={() => navigate(`/todayedit?id=${encodeURIComponent(schedule.id)}`)}
        />
      </div>
    </div>
  )
}

export default DayCard
