import { api, saveSession, clearSession, normalizePhone, getAccessToken } from './http'

export const readSignupAccount = () => {
  try {
    return JSON.parse(sessionStorage.getItem('signup_created_account') || 'null')
  } catch {
    return null
  }
}

export const signup = async (payload) => {
  const { data } = await api.post('/auth/signup', {
    ...payload,
    phoneNumber: normalizePhone(payload.phoneNumber),
  })

  return data.data
}

export const login = async (phoneNumber) => {
  const { data } = await api.post('/auth/login', {
    phoneNumber: normalizePhone(phoneNumber),
  })

  const session = data.data

  if (!session?.accessToken || !session?.expiresAt) {
    throw new Error('로그인 응답에 세션 정보가 없어요.')
  }

  clearSession()
  saveSession(session)

  return session
}

export const me = async (config = {}) => {
  const { data } = await api.get('/me', config)
  return data.data
}

export const ensureSignupSession = async (payload) => {
  const phoneNumber = normalizePhone(payload.phoneNumber)
  let account = readSignupAccount()

  if (account?.phoneNumber === phoneNumber) {
    const roleChanged = account.accountRole !== payload.accountRole
    const nameChanged =
      payload.accountRole === 'CHILD' && account.displayName !== payload.displayName

    if (roleChanged || nameChanged) {
      throw new Error('이미 가입한 계정의 역할과 이름은 변경할 수 없어요.')
    }
  } else {
    const result = await signup(payload)

    account = {
      phoneNumber,
      accountRole: payload.accountRole,
      displayName: payload.displayName ?? null,
      userId: result.user.id,
    }

    sessionStorage.setItem('signup_created_account', JSON.stringify(account))
  }

  if (getAccessToken()) {
    const user = await me()

    if (user.id === account.userId) {
      return user
    }
  }

  await login(phoneNumber)

  return me()
}

export const clearSignupDraft = () => {
  const keys = [
    'signup_phone',
    'signup_role',
    'signup_parent_phone',
    'signup_parent_lookup',
    'signup_parent_profile',
    'signup_self_name',
    'signup_created_account',
  ]

  keys.forEach((key) => sessionStorage.removeItem(key))
}

export const logout = async () => {
  try {
    await api.post('/auth/logout')
  } finally {
    clearSession()
  }
}

export const finishSignup = async (navigate) => {
  const phoneNumber = sessionStorage.getItem('signup_phone') || ''

  try {
    if (getAccessToken()) {
      await logout()
    }
  } catch {
    clearSession()
  }

  clearSignupDraft()

  navigate('/loginselect', {
    replace: true,
    state: {
      signupCompleted: true,
      phoneNumber,
    },
  })
}

export const navigateForOnboarding = (navigate) => {
  navigate('/today', { replace: true })
}

export const recipientLookup = async (phoneNumber) => {
  const { data } = await api.post('/care-groups/recipient-lookup', {
    phoneNumber: normalizePhone(phoneNumber),
  })

  return data.data
}

export const joinGroup = async (groupId, payload, idempotencyKey = crypto.randomUUID()) => {
  const { data } = await api.post(`/care-groups/${encodeURIComponent(groupId)}/join`, payload, {
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
  })

  return data.data
}

export const moveAfterJoin = async (navigate, result) => {
  if (result.nextAction === 'WAITING_APPROVAL') {
    navigate('/signupwaiting', { replace: true })
    return
  }

  if (result.nextAction === 'READY') {
    if (sessionStorage.getItem('signup_role') === 'MainUser') {
      await finishSignup(navigate)
    } else {
      navigate('/signupservicestart', { replace: true })
    }

    return
  }

  throw new Error('가족 연결 결과를 확인할 수 없어요.')
}

export const getCareGroups = async () => {
  const { data } = await api.get('/me/care-groups')
  return data.data
}

export const getGroupMembers = async (groupId) => {
  const { data } = await api.get(`/care-groups/${encodeURIComponent(groupId)}/members`)

  return data.data
}

export const getJoinRequests = async (config = {}) => {
  const { data } = await api.get('/me/join-requests/current', config)
  return data.data
}

export const getPendingGroupRequests = async (groupId) => {
  const { data } = await api.get(`/care-groups/${encodeURIComponent(groupId)}/join-requests`)

  return data.data
}

export const approveGroupRequest = async (
  groupId,
  requestId,
  version,
  idempotencyKey = crypto.randomUUID(),
) => {
  const { data } = await api.post(
    `/care-groups/${encodeURIComponent(groupId)}/join-requests/${encodeURIComponent(requestId)}/decision`,
    {
      expectedVersion: version,
      decision: 'APPROVE',
    },
    {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    },
  )

  return data.data
}

export const rejectGroupRequest = async (
  groupId,
  requestId,
  version,
  idempotencyKey = crypto.randomUUID(),
) => {
  const { data } = await api.post(
    `/care-groups/${encodeURIComponent(groupId)}/join-requests/${encodeURIComponent(requestId)}/decision`,
    {
      expectedVersion: version,
      decision: 'REJECT',
    },
    {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    },
  )

  return data.data
}

export const cancelJoinRequest = async (
  requestId,
  version,
  idempotencyKey = crypto.randomUUID(),
) => {
  const { data } = await api.post(
    `/me/join-requests/${encodeURIComponent(requestId)}/cancel`,
    {
      expectedVersion: version,
    },
    {
      headers: {
        'Idempotency-Key': idempotencyKey,
      },
    },
  )

  return data.data
}
