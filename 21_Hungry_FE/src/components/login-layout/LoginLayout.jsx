import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Icon } from '../icon/Icon.jsx'
import './LoginLayout.css'

function LoginLayout() {
  const navigate = useNavigate()
  const { pathname } = useLocation()

  const handleBack = () => {
    if (window.history.state?.idx > 0) {
      navigate(-1)
      return
    }

    navigate(pathname === '/loginselect' ? '/' : '/loginselect', { replace: true })
  }

  return (
    <div className='loginlayout__container'>
      <div className='loginlayout__page'>
        <header className='loginlayout__header'>
          <button type='button' className='loginlayout__backButton' aria-label='뒤로가기' onClick={handleBack}>
            <Icon name='back-button' width={11} height={19} aria-hidden='true' />
          </button>
        </header>
        <Outlet />
      </div>
    </div>
  )
}

export default LoginLayout
