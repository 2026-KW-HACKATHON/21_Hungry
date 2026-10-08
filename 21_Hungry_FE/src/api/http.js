import axios from 'axios'

const TOKEN_KEY = 'knowone_access_token'
const EXPIRES_AT_KEY = 'knowone_expires_at'

const baseURL = (import.meta.env?.VITE_API_BASE_URL || 'https://api.gaebalmani.shop').trim().replace(/\/+$/, '')

if (!baseURL) {
  throw new Error('VITE_API_BASE_URL 환경변수를 설정해 주세요.')
}

export const getAccessToken = () => sessionStorage.getItem(TOKEN_KEY)

export const saveSession = (data) => {
  sessionStorage.setItem(TOKEN_KEY, data.accessToken)
  sessionStorage.setItem(EXPIRES_AT_KEY, data.expiresAt)
}

export const clearSession = () => {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(EXPIRES_AT_KEY)
  sessionStorage.removeItem('groupId')
}

export const messageOf = (error) => {
  const serverMessage = error?.response?.data?.error?.message

  if (serverMessage) return serverMessage

  if (axios.isCancel(error)) {
    return '요청이 취소됐어요.'
  }

  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return '서버 응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.'
  }

  if (error?.code === 'ERR_NETWORK') {
    return '서버에 연결할 수 없어요. 인터넷 연결과 서버 상태를 확인해 주세요.'
  }

  const messages = {
    400: '입력한 내용을 확인해 주세요.',
    401: '로그인이 필요하거나 만료됐어요. 다시 로그인해 주세요.',
    403: '접근 권한이 없어요. 가족 연결과 가입 승인 상태를 확인해 주세요.',
    404: '요청한 정보를 찾을 수 없어요.',
    409: '다른 가족이 변경한 내용이 있어요. 새로고침 후 다시 확인해 주세요.',
    413: '파일이 서버의 용량 제한을 넘었어요.',
    415: '서버에서 지원하지 않는 파일 형식이에요.',
    422: '입력 내용이나 파일을 확인해 주세요.',
    429: '요청이 많아요. 잠시 후 다시 시도해 주세요.',
    500: '서버에서 오류가 발생했어요. 잠시 후 다시 시도해 주세요.',
    502: '서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요.',
    503: '서버를 이용할 수 없어요. 잠시 후 다시 시도해 주세요.',
  }

  return (
    messages[error?.response?.status ?? error?.status] ||
    '요청에 실패했어요. 잠시 후 다시 시도해 주세요.'
  )
}

export const api = axios.create({
  baseURL: `${baseURL}/api/v1`,
  timeout: 30000,
})

api.interceptors.request.use((config) => {
  const isAuthRequest = ['/auth/signup', '/auth/login'].includes(config.url)

  if (isAuthRequest) {
    config.headers.delete('Authorization')
  } else if (!config.headers.has('Authorization')) {
    const token = getAccessToken()

    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
    }
  }

  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    config.headers.delete('Content-Type')
  }

  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isCancel(error)) {
      return Promise.reject(error)
    }

    error.status = error.response?.status
    error.apiCode = error.response?.data?.error?.code
    error.details = error.response?.data?.error?.details ?? {}
    error.requestId = error.response?.data?.error?.requestId
    error.message = messageOf(error)

    const requestToken = error.config?.headers?.get('Authorization')
    const currentToken = getAccessToken()

    if (error.status === 401 && currentToken && requestToken === `Bearer ${currentToken}`) {
      clearSession()

      if (window.location.pathname !== '/loginselect') {
        window.location.replace('/loginselect')
      }
    }

    return Promise.reject(error)
  },
)

export const normalizePhone = (phone) => String(phone || '').replace(/[\s-]/g, '')

export const validPhone = (phone) => /^010[0-9]{8}$/.test(normalizePhone(phone))
