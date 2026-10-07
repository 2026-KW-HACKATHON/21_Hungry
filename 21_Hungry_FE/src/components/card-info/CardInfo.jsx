import './CardInfo.css'

import CardIcon from '../card-icon/CardIcon'

function CardInfo({ category, label1, value1, label2, value2, label3, value3 }) {
  return (
    <div className='cardInfo__info'>
      <CardIcon name={category} content={category} />
      <div className='cardInfo__info--details'>
        <div className='cardInfo__info--detail'>
          <p className='cardInfo__info--label'>{label1}</p>
          <p className='cardInfo__info--value'>{value1}</p>
        </div>
        <div className='cardInfo__info--detail'>
          <p className='cardInfo__info--label'>{label2}</p>
          <p className='cardInfo__info--value'>{value2}</p>
        </div>
        <div className='cardInfo__info--detail'>
          <p className='cardInfo__info--label'>{label3}</p>
          <p className='cardInfo__info--value'>{value3}</p>
        </div>
      </div>
    </div>
  )
}

export default CardInfo
