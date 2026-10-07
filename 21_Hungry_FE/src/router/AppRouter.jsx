import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'
import LoginLayout from '../components/login-layout/LoginLayout'

import LoginSelect from '../pages/login-select/LoginSelect'
import LoginPage from '../pages/login/LoginPage'
import SignupRole from '../pages/signup-role/SignupRole'
import ServiceInfo from '../pages/service_Info/ServiceInfo'
import SignupSelect from '../pages/signup-select/SiginSelect'
import SignupMainuser from '../pages/signup-mainuser/SignupMainuser'
import SignupSubuser from '../pages/signup-subuser/SignupSubuser'

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
    path: '/signupsubuser',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupSubuser /> }],
  },
  {
    path: '/loginselect',
    element: <LoginLayout />,
    children: [{ path: '', element: <LoginSelect /> }],
  },
  {
    path: '/signupselect',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupSelect /> }],
  },
  {
    path: '/serviceinfo',
    element: "",
    children: [{ path: '', element: <ServiceInfo /> }],
  },
  {
    path: '/signupmainuser',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupMainuser /> }],
  },
  {
    path : '/signuprole', 
    element:<LoginLayout/>,
    children: [
      { path: '', element: <SignupRole /> }
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
