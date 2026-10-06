import { Outlet } from 'react-router-dom'
import Navigation from './navigation/Navigation'

function MainLayout() {
  return (
    <div className='mainlayout__container'>
      <div className='mainlayout__page'>
        <Outlet />
      </div>
      <Navigation />
    </div>
  )
}

export default MainLayout
