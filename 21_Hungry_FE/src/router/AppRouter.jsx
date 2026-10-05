import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'

import HomePage from '../pages/home/HomePage'
import LoginPage from '../pages/login/LoginPage'

export const AppRouter = createBrowserRouter([
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { path: 'home', element: <HomePage /> },
      { path: 'login', element: <LoginPage /> },
    ],
  },
])
