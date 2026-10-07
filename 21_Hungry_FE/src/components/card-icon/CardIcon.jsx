import './CardIcon.css'
import { Icon } from '../icon/Icon'

function CardIcon({ name, content }) {
  const categoryIconMap = {
    '기타 돌봄': 'card-etc',
    '약 복용': 'card-drug',
    '병원 내원': 'card-hospital',
    건강검진: 'card-doctor',
  }

  return (
    <div className='cardIcon__container'>
      <Icon name={categoryIconMap[name] || 'card-etc'} width={50} height={50} />
      <p className='cardIcon__content'>{content}</p>
    </div>
  )
}

export default CardIcon
