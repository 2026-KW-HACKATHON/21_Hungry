import { Outlet } from 'react-router-dom'

function MainLayout() {
  return (
    <div className='mainlayout__container'>
      <div className='mainlayout__page'>
        <Outlet />
      </div>
    </div>
  )
}

export default MainLayout
