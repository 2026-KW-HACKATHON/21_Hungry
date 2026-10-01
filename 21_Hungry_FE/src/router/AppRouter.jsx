import { createBrowserRouter } from 'react-router-dom'

import MainLayout from '../components/main-layout/MainLayout'

import HomePage from '../pages/home/HomePage'

export const AppRouter = createBrowserRouter([
  { path: '/', element: <MainLayout />, children: [{ path: 'home', element: <HomePage /> }] },
])
