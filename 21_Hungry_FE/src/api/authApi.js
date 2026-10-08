import { apiBaseUrl } from './recordApi.js'

export function loginDestination(profile) {
  switch (profile.onboardingState) {
    case 'READY': return '/today'
    case 'NO_GROUP': return '/signupsubuser'
    case 'WAITING_APPROVAL': return '/signupwaiting'
    case 'PARENT_PROFILE_PENDING':
      return profile.accountRole === 'PARENT' ? '/signupservicestart' : '/signupparentinfo'
    default: throw new Error('가입 상태를 확인할 수 없어요. 다시 시도해 주세요.')
  }
}

export async function loginByPhone(rawPhone, signal) {
  const phoneNumber = rawPhone.replace(/[-\s]/g, '')
  if (!/^010[0-9]{8}$/.test(phoneNumber)) {
    throw new Error('010으로 시작하는 휴대전화 번호 11자리를 입력해 주세요.')
  }
  async function request(path, options) {
    const response = await fetch(`${apiBaseUrl}${path}`, { ...options, signal, cache: 'no-store' })
    const responseText = await response.text()
    let body
    try { body = JSON.parse(responseText) } catch { body = null }
    if (response.status === 403 && responseText.includes('Invalid CORS request')) {
      throw new Error('서버가 현재 웹 주소의 요청을 허용하지 않아요. 백엔드 CORS 설정을 확인해 주세요.')
    }
    if (!response.ok) {
      const messages = {
        401: '가입된 계정이 없거나 사용할 수 없는 계정이에요. 회원가입 여부를 확인해 주세요.',
        403: '현재 서버에서 전화번호 로그인을 허용하지 않아요.',
        429: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
      }
      throw new Error(messages[response.status] || body?.error?.message || '로그인에 실패했어요. 잠시 후 다시 시도해 주세요.')
    }
    if (!body?.data) throw new Error('로그인 서버 응답을 확인할 수 없어요.')
    return body.data
  }
  const session = await request('/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phoneNumber }),
  })
  if (!session.accessToken) throw new Error('로그인 토큰을 받지 못했어요.')
  const profile = await request('/me', { headers: { Authorization: `Bearer ${session.accessToken}` } })
  return { accessToken: session.accessToken, profile, destination: loginDestination(profile) }
}
