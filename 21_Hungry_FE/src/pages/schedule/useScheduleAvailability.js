import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { me } from '../../api/authApi'
import { getAccessToken, messageOf } from '../../api/http'
import { getAvailabilityDays } from '../../api/scheduleApi'
import { getMonthRange } from './scheduleUtils'

export function useScheduleAvailability(year, month, includeUser = false) {
  const navigate = useNavigate()
  const [result, setResult] = useState(null)
  const [retry, setRetry] = useState(0)

  const { fromDate, toDateExclusive } = getMonthRange(year, month)
  const key = JSON.stringify([fromDate, toDateExclusive, includeUser, retry])
  const current = result?.key === key ? result : null

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()
    const config = { signal: controller.signal }

    Promise.all([
      getAvailabilityDays(fromDate, toDateExclusive, config),
      includeUser ? me(config) : Promise.resolve(null),
    ])
      .then(([data, user]) => {
        if (!controller.signal.aborted) {
          setResult({
            key,
            items: data.items,
            user,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setResult({
            key,
            items: [],
            user: null,
            error: error.message || messageOf(error),
          })
        }
      })

    return () => controller.abort()
  }, [fromDate, toDateExclusive, includeUser, key, navigate])

  return {
    items: current?.items || [],
    user: current?.user,
    error: current?.error || '',
    isLoading: !current,
    reload: () => setRetry((value) => value + 1),
  }
}
