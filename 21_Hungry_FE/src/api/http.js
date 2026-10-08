
import axios from 'axios'

export const api = axios.create({
  baseURL: `${(import.meta.env.VITE_API_BASE_URL || 'https://api.gaebalmani.shop').replace(/\/+$/, '')}/api/v1`,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('knowone_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export const messageOf = (error) =>
  error.response?.data?.error?.message ||
  '요청에 실패했습니다. 잠시 후 다시 시도해 주세요.'

export const normalizePhone = (phone) =>
  String(phone || '').replace(/[\s-]/g, '')

export const validPhone = (phone) =>
  /^010[0-9]{8}$/.test(normalizePhone(phone))

export const saveSession = (data) => {
  sessionStorage.setItem('knowone_access_token', data.accessToken)
  sessionStorage.setItem('knowone_expires_at', data.expiresAt)
}

export const clearSession = () => {
  sessionStorage.removeItem('knowone_access_token')
  sessionStorage.removeItem('knowone_expires_at')
}
