
import { api, saveSession, clearSession } from './http'

export const signup = async (payload) =>
  (await api.post('/auth/signup', payload)).data.data

export const login = async (phoneNumber) => {
  const { data } = await api.post('/auth/login', { phoneNumber })
  saveSession(data.data)
  return data.data
}

export const me = async () =>
  (await api.get('/me')).data.data

export const recipientLookup = async (phoneNumber) =>
  (await api.post('/care-groups/recipient-lookup', { phoneNumber })).data.data

export const joinGroup = async (groupId, payload) =>
  (await api.post(`/care-groups/${groupId}/join`, payload, {
    headers: { 'Idempotency-Key': crypto.randomUUID() }
  })).data.data

export const getJoinRequests = async () =>
  (await api.get('/me/join-requests/current')).data.data

export const logout = async () => {
  try {
    await api.post('/auth/logout')
  } finally {
    clearSession()
  }
}

export const navigateForOnboarding = (navigate, user) => {
  if (user.onboardingState === 'WAITING_APPROVAL')
    navigate('/signupwaiting', { replace: true })
  else if (user.onboardingState === 'NO_GROUP' && user.accountRole === 'CHILD')
    navigate('/signupmainuser', { replace: true })
  else if (user.onboardingState === 'PARENT_PROFILE_PENDING')
    navigate('/signupparent', { replace: true })
  else
    navigate('/schedule', { replace: true })
}

// Care-group membership and approval endpoints.
export const getCareGroups = async () =>
  (await api.get('/me/care-groups')).data.data

export const getGroupMembers = async (groupId) =>
  (await api.get(`/care-groups/${encodeURIComponent(groupId)}/members`)).data.data

export const getPendingGroupRequests = async (groupId) =>
  (await api.get(`/care-groups/${encodeURIComponent(groupId)}/join-requests`)).data.data

export const approveGroupRequest = async (groupId, requestId, version) =>
  (await api.post(`/care-groups/${encodeURIComponent(groupId)}/join-requests/${encodeURIComponent(requestId)}/approve`,
    { expectedVersion: version }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data.data

export const rejectGroupRequest = async (groupId, requestId, version) =>
  (await api.post(`/care-groups/${encodeURIComponent(groupId)}/join-requests/${encodeURIComponent(requestId)}/reject`,
    { expectedVersion: version }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data.data

export const cancelJoinRequest = async (requestId, version) =>
  (await api.post(`/join-requests/${encodeURIComponent(requestId)}/cancel`,
    { expectedVersion: version }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data.data
