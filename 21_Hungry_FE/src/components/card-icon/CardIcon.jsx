import './CardIcon.css'
import { Icon } from '../icon/Icon'

function CardIcon({ name, content }) {
  return (
    <div className='cardIcon__container'>
      <Icon name={name} width={50} height={50} />
      <p className='cardIcon__content'>{content}</p>
    </div>
  )
}

export default CardIcon
