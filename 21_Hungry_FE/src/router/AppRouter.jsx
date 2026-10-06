import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'
import LoginLayout from '../components/login-layout/LoginLayout'

import LoginSelect from '../pages/login-select/LoginSelect'
import LoginPage from '../pages/login/LoginPage'
import SignupPage from '../pages/signup/SignupPage'
import ServiceInfo from '../pages/service_Info/ServiceInfo'

import TodayPage from '../pages/today/TodayPage'
import DocPage from '../pages/doc/DocPage'
import SchdulePage from '../pages/schedule/SchedulePage'
import FamilyPage from '../pages/family/FamilyPage'
import RecordPage from '../pages/record/RecordPage'

export const AppRouter = createBrowserRouter([
  {
    path: '/login',
    element: <LoginLayout />,
    children: [{ path: '', element: <LoginPage /> }],
  },
  {
    path: '/loginselect',
    element: <LoginLayout />,
    children: [{ path: '', element: <LoginSelect /> }],
  },
  {
    path: '/serviceinfo',
    element: "",
    children: [{ path: '', element: <ServiceInfo /> }],
  },
  {
    path : '/signup', 
    element:<LoginLayout/>,
    children: [
      { path: '', element: <SignupPage /> }
    ]
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { path: 'today', element: <TodayPage /> },
      { path: 'doc', element: <DocPage /> },
      { path: 'schedule', element: <SchdulePage /> },
      { path: 'family', element: <FamilyPage /> },
      { path: 'record', element: <RecordPage /> },
    ],
  },
])
