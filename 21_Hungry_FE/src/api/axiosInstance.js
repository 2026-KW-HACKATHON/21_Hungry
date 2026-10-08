import axios from 'axios'

const axiosInstance = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE_URL}/api/v1`,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

axiosInstance.interceptors.request.use(
  (config) => {
    const token = sessionStorage.getItem('accessToken')

    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    return config
  },
  (error) => Promise.reject(error)
)

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      console.warn('인증이 필요하거나 세션이 만료되었습니다.')
    }

    return Promise.reject(error)
  }
)

export default axiosInstance