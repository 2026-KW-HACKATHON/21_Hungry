import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'
import LoginLayout from '../components/login-layout/LoginLayout'

import TodayPage from '../pages/today/TodayPage'
import LoginPage from '../pages/login/LoginPage'

export const AppRouter = createBrowserRouter([
  {
    path: '/login',
    element: <LoginLayout />,
    children: [{ path: '', element: <LoginPage /> }],
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [{ path: 'today', element: <TodayPage /> }],
  },
])
