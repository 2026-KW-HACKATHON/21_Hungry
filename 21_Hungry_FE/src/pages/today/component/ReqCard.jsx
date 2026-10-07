import './ReqCard.css'
import CardInfo from '../../../components/card-info/CardInfo'
import PopupButton from '../../../components/popup-button/PopupButton'

/* schedule : { id, category, date, time, family, description } */

function ReqCard({ schedule }) {
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
        <CardInfo
          category={schedule.category}
          label1={'날짜'}
          label2={'시간'}
          label3={'담당 가족'}
          value1={schedule.date}
          value2={schedule.time}
          value3={schedule.family}
        />
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
