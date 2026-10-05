import { Outlet } from 'react-router-dom'

function LoginLayout() {
  return (
    <div className='loginlayout__container'>
      <div className='loginlayout__page'>
        <Outlet />
      </div>
    </div>
  )
}

export default LoginLayout
