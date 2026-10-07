import './BackHeader.css'
import { Icon } from '../icon/Icon'

function BackHeader({ content, subcontent }) {
  return (
    <div className='backHeader__container'>
      <Icon className='backHeader__button' name='back-button' width={11} height={19} />
      <p className='backHeader__title'>{content}</p>
      <p className='backHeader__subtitle'>{subcontent}</p>
    </div>
  )
}

export default BackHeader
