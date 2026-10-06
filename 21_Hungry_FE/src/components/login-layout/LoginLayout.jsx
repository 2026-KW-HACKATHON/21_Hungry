import { Outlet } from 'react-router-dom'
import { Icon } from '../icon/Icon.jsx'
import './LoginLayout.css'

function LoginLayout() {
  return (
    <div className='loginlayout__container'>
        <button className='loginlayout__backButton'>
            <Icon className='loginlayout__backButton' name='back-button' width={11} height={19} />
        </button>
        <div className='loginlayout__page'>
            <Outlet />
        </div>
        <button className='loginlayout__nextButton'>다음</button>
    </div>
  )
}

export default LoginLayout
