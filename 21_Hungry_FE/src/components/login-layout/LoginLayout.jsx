import { Outlet } from 'react-router-dom'
import { Icon } from '../icon/Icon.jsx'
import './LoginLayout.css'

function LoginLayout() {
  return (
    <div className='loginlayout__container'>    
        <Icon name='back-button' width={11} height={19} />
        <div className='loginlayout__page'>
            <Outlet />
        </div>
        <button className='loginlayout__nextButton'>다음</button>
    </div>
  )
}

export default LoginLayout
