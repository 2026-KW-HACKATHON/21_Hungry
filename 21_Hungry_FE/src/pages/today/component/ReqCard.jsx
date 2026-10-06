import './ReqCard.css'
import CardIcon from '../../../components/card-icon/CardIcon'
import PopupButton from '../../../components/popup-button/PopupButton'

/* schedule : { id, category, date, time, family, description } */

function ReqCard({ schedule }) {
  const categoryIconMap = {
    '기타 돌봄': 'card-etc',
    '약 복용': 'card-drug',
    '병원 내원': 'card-hospital',
    건강검진: 'card-doctor',
  }

  return (
    <div className='reqCard__container'>
      <div className='reqCard__title'>
        <div className='reqCard__title--title'>
          {`${schedule.family} 님으로부터`}
          <br />
          일정 수행 요청을 받았어요
        </div>
        <div className='reqCard__title--text'>
          아래의 돌봄 일정을 수행해달라는 요청이에요. 자세한 정보는 투데이 탭에서 확인할 수 있어요.
        </div>
      </div>
      <div className='reqCard__info'>
        <CardIcon
          name={categoryIconMap[schedule.category] || 'card-etc'}
          content={schedule.category}
        />
        <div className='reqCard__info--details'>
          <div className='reqCard__info--detail'>
            <p className='reqCard__info--label'>날짜</p>
            <p className='reqCard__info--value'>{schedule.date}</p>
          </div>
          <div className='reqCard__info--detail'>
            <p className='reqCard__info--label'>시간</p>
            <p className='reqCard__info--value'>{schedule.time}</p>
          </div>
          <div className='reqCard__info--detail'>
            <p className='reqCard__info--label'>담당 가족</p>
            <p className='reqCard__info--value'>{schedule.family}</p>
          </div>
        </div>
      </div>
      <div className='reqCard__description'>
        <p>{schedule.description}</p>
      </div>
      <div className='reqCard__buttons'>
        <PopupButton content='거절하기' color='red' />
        <PopupButton content='수락하기' color='blue' />
      </div>
    </div>
  )
}

export default ReqCard
