import { useEffect, useState } from 'react'
import { requestRecordApi } from '../../api/recordApi'
import { recordProgress } from '../../api/recordModel'

export function useRecordDetail(encounterId, accessToken) {
  const [result, setResult] = useState(null)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    if (!encounterId || !accessToken) return
    const controller = new AbortController()
    let timer
    const started = Date.now()
    async function poll() {
      try {
        const detail = await requestRecordApi(`/encounters/${encodeURIComponent(encounterId)}`, accessToken, { signal: controller.signal })
        if (controller.signal.aborted) return
        const progress = recordProgress(detail)
        const stopped = progress.done || Date.now() - started >= 600000
        setResult({ encounterId, accessToken, refresh, detail, stopped, message: stopped && !progress.done ? '처리가 오래 걸리고 있어요. 서버 처리는 계속되며 상태를 다시 조회할 수 있어요.' : progress.message })
        if (!stopped) timer = setTimeout(poll, Date.now() - started > 60000 ? 5000 : 2000)
      } catch (error) {
        if (!controller.signal.aborted) setResult({ encounterId, accessToken, refresh, detail: null, stopped: true, message: error.message })
      }
    }
    timer = setTimeout(poll, 0)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [encounterId, accessToken, refresh])
  const current = result?.encounterId === encounterId && result?.accessToken === accessToken && result?.refresh === refresh ? result : null
  return {
    detail: current?.detail,
    stopped: current?.stopped,
    message: !accessToken ? '로그인이 필요해요.' : !encounterId ? '기록을 선택해 주세요.' : current?.message || '기록을 불러오고 있어요.',
    reload: () => setRefresh((value) => value + 1),
  }
}
