import './Navigation.css'

import { useNavigate, useLocation } from 'react-router-dom'
import { Icon } from '../../icon/Icon'

function Navigation() {
  const navigate = useNavigate()
  const location = useLocation()

  const navigationItems = [
    { key: 'Doc', path: '/doc', defaultIcon: 'save-default', activeIcon: 'save-active' },
    {
      key: 'Schedule',
      path: '/schedule',
      defaultIcon: 'calendar-default',
      activeIcon: 'calendar-active',
    },
    { key: 'Today', path: '/today', defaultIcon: 'today-default', activeIcon: 'today-active' },
    { key: 'Family', path: '/family', defaultIcon: 'family-default', activeIcon: 'family-active' },
    { key: 'Record', path: '/record', defaultIcon: 'doctor-default', activeIcon: 'doctor-active' },
  ]

  return (
    <div className='navigation__container'>
      {navigationItems.map((item) => (
        <button key={item.key} onClick={() => navigate(item.path)} className='nav__item'>
          {location.pathname === item.path ||
          (item.key === 'Doc' &&
            ['/doc-record', '/doc-search', '/doc-edit', '/doc-select', '/doc-add'].includes(
              location.pathname,
            )) ||
          (item.key === 'Record' && location.pathname.startsWith('/record/')) ||
          (item.key === 'Schedule' && location.pathname === '/scheduleedit') ||
          (item.key === 'Today' && ['/todayadd', '/todayedit'].includes(location.pathname)) ||
          (item.key === 'Family' &&
            ['/familyreq', '/familyschedule'].includes(location.pathname)) ? (
            item.path === '/today' ? (
              <Icon className='nav__item' name={item.activeIcon} width={50} />
            ) : (
              <Icon className='nav__item' name={item.activeIcon} width={54} height={54} />
            )
          ) : item.path === '/today' ? (
            <Icon className='nav__item' name={item.defaultIcon} width={50} />
          ) : (
            <div className='nav__item--default'>
              <Icon className='nav__item' name={item.defaultIcon} width={36} height={36} />
            </div>
          )}
        </button>
      ))}
    </div>
  )
}

export default Navigation
