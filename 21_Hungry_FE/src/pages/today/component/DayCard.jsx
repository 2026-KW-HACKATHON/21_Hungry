import './DayCard.css'
import CardInfo from '../../../components/card-info/CardInfo'
import PopupButton from '../../../components/popup-button/PopupButton'
import { useNavigate } from 'react-router-dom'

/* schedule : { id, category, date, time, family, description } */

function DayCard({ schedule }) {
  const navigate = useNavigate()

  const handleEdit = () => {
    navigate('/todayedit')
  }

  return (
    <div className='dayCard__container'>
      <CardInfo
        category={schedule.category}
        label1={'날짜'}
        label2={'시간'}
        label3={'담당 가족'}
        value1={schedule.date}
        value2={schedule.time}
        value3={schedule.family}
      />
      <div className='dayCard__description'>
        <p>{schedule.description}</p>
      </div>
      <div className='dayCard__buttons'>
        <PopupButton content='완료로 표시하기' color='gray' />
        <PopupButton content='이 일정 수정하기' onClick={() => handleEdit()} color='green' />
      </div>
    </div>
  )
}

export default DayCard
