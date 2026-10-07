import './BackHeader.css'
import { Icon } from '../icon/Icon'
import { useNavigate } from 'react-router-dom'

function BackHeader({ content, subcontent }) {
  const navigate = useNavigate()

  const handleBack = () => {
    console.log('back clicked')
    navigate(-1)
  }

  return (
    <div className='backHeader__container'>
      <button className='backHeader__button' onClick={handleBack}>
        <Icon name='back-button' width={11} height={19} />
      </button>
      <p className='backHeader__title'>{content}</p>
      <p className='backHeader__subtitle'>{subcontent}</p>
    </div>
  )
}

export default BackHeader
