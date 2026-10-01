import { RouterProvider } from 'react-router-dom'
import { AppRouter } from './router/AppRouter.jsx'
import styles from './App.module.css'

function App() {
  return (
    <div className={styles.app}>
      <RouterProvider router={AppRouter} />
    </div>
  )
}

export default App
