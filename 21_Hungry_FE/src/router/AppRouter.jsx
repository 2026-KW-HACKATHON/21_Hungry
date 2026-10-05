import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'
import LoginLayout from '../components/login-layout/LoginLayout'

import HomePage from '../pages/home/HomePage'
import LoginPage from '../pages/login/LoginPage'

export const AppRouter = createBrowserRouter([
  {
    path : '/login', 
    element:<LoginLayout/>,
    children: [
      { path: '', element: <LoginPage /> }
    ]
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { path: 'home', element: <HomePage /> },
    ],
  },
])
