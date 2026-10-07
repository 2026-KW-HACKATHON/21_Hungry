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
import SignupParent from '../pages/signup-parent/SignupParent'
import SignupParentDone from '../pages/signup-parent-done/SignupParentDone'
import SignupSelfInfo from '../pages/signup-self-info/SignupSelfInfo'
import SignupParentInfo from '../pages/signup-parent-info/SignupParentInfo'
import SignupFamilyInfo from '../pages/signup-familyinfo/SignupFamilyInfo'
import SignupWaiting from '../pages/signup-waiting/SignupWaiting'
import SignupServiceStart from '../pages/signup-service-start/SignupServiceStart'
import SignupWaitingDone from '../pages/signup-waiting-done/SignupWaitingDone'

import TodayPage from '../pages/today/TodayPage'
import DocPage from '../pages/doc/DocPage'
import SchdulePage from '../pages/schedule/SchedulePage'
import FamilyPage from '../pages/family/FamilyPage'
import RecordPage from '../pages/record/RecordPage'

export const AppRouter = createBrowserRouter([
  {
    path: '/signupparentinfo',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupParentInfo /> }],
  },
  {
    path: '/signupservicestart',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupServiceStart /> }],
  },
  {
    path: '/signupwaitingdone',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupWaitingDone /> }],
  },
  {
    path: '/signupwaiting',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupWaiting /> }],
  },
  {
    path: '/login',
    element: <LoginLayout />,
    children: [{ path: '', element: <LoginPage /> }],
  },

  {
    path: '/signupselfinfo',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupSelfInfo /> }],
  },
    {
    path: '/signupfamilyinfo',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupFamilyInfo /> }],
  },
  {
    path: '/signupparentdone',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupParentDone /> }],
  },
  {
    path: '/signupsubuser',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupSubuser /> }],
  },
  {
    path: '/signupparent',
    element: <LoginLayout />,
    children: [{ path: '', element: <SignupParent /> }],
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
