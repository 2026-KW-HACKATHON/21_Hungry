import './DayCard.css'
import CardIcon from '../../../components/card-icon/CardIcon'
import PopupButton from '../../../components/popup-button/PopupButton'

/* schedule : { id, category, date, time, family, description } */

function DayCard({ schedule }) {
  const categoryIconMap = {
    '기타 돌봄': 'card-etc',
    '약 복용': 'card-drug',
    '병원 내원': 'card-hospital',
    건강검진: 'card-doctor',
  }

  return (
    <div className='dayCard__container'>
      <div className='dayCard__info'>
        <CardIcon
          name={categoryIconMap[schedule.category] || 'card-etc'}
          content={schedule.category}
        />
        <div className='dayCard__info--details'>
          <div className='dayCard__info--detail'>
            <p className='dayCard__info--label'>날짜</p>
            <p className='dayCard__info--value'>{schedule.date}</p>
          </div>
          <div className='dayCard__info--detail'>
            <p className='dayCard__info--label'>시간</p>
            <p className='dayCard__info--value'>{schedule.time}</p>
          </div>
          <div className='dayCard__info--detail'>
            <p className='dayCard__info--label'>담당 가족</p>
            <p className='dayCard__info--value'>{schedule.family}</p>
          </div>
        </div>
      </div>
      <div className='dayCard__description'>
        <p>{schedule.description}</p>
      </div>
      <div className='dayCard__buttons'>
        <PopupButton content='완료로 표시하기' color='gray' />
        <PopupButton content='이 일정 수정하기' color='green' />
      </div>
    </div>
  )
}

export default DayCard
