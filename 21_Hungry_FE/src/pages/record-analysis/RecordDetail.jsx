import { useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { getAccessToken } from '../../api/http'
import { createRecordMutation, requestRecordApi, sendRecordMutation } from '../../api/recordApi'
import { todayInSeoul } from '../../api/recordModel'
import { Icon } from '../../components/icon/Icon'
import BottomButton from '../../components/bottom-button/BottomButton'
import { useRecordDetail } from './useRecordDetail'
import RecordReview from './RecordReview'
import RecordSources, { Evidence } from './RecordSources'
import '../record-audio-analysis/RecordAudioAnalysisPage.css'
import './RecordDetail.css'

const sections = [['symptoms', '증상'], ['tests', '검사'], ['medicationMentions', '약 관련 내용'], ['precautions', '주의 사항'], ['followUps', '추후 진료']]

function RecordActions({ detail, accessToken, reload }) {
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const lock = useRef(false)
  const [pending, setPending] = useState(null)
  const [moreTasks, setMoreTasks] = useState({ items: [], nextCursor: null, hasMore: true })
  const [taskDate, setTaskDate] = useState(todayInSeoul())
  async function mutate(attempt, deleting = false) {
    if (lock.current) return
    lock.current = true
    setPending({ attempt, deleting })
    setBusy(true)
    try {
      await sendRecordMutation(attempt, accessToken)
      setPending(null)
      if (deleting) navigate('/doc', { replace: true, state: { message: '기록 삭제가 접수됐어요. 원본 파일은 순차적으로 파기돼요.' } })
      else { setEditing(false); setMessage('변경 내용이 저장됐어요.'); reload() }
    } catch (error) {
      setMessage(error.message)
      if (error.status >= 400 && error.status < 500 && error.apiCode !== 'REQUEST_IN_PROGRESS') {
        setPending(null)
        if (error.status === 409) { setPreview(null); reload() }
      }
    } finally { lock.current = false; setBusy(false) }
  }
  async function deletionPreview() {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    try {
      const result = await requestRecordApi(`/encounters/${encodeURIComponent(detail.id)}/deletion-preview`, accessToken, { method: 'POST', body: { expectedVersion: detail.version } })
      setPreview(result)
    } catch (error) { setMessage(error.message); if (error.status === 409) reload() }
    finally { lock.current = false; setBusy(false) }
  }
  async function loadTasks() {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    try {
      const params = new URLSearchParams({ encounterId: detail.id, date: taskDate, limit: '20' })
      if (moreTasks.nextCursor) params.set('cursor', moreTasks.nextCursor)
      const data = await requestRecordApi(`/care-groups/${encodeURIComponent(detail.groupId)}/tasks?${params}`, accessToken)
      setMoreTasks((previous) => ({ ...data, items: [...previous.items, ...data.items.filter((task) => task.sourceEncounterIds?.includes(detail.id))] }))
    } catch (error) { setMessage(error.message) }
    finally { lock.current = false; setBusy(false) }
  }
  const tasks = [...new Map([...(detail.linkedTasks || []), ...moreTasks.items].map((task) => [task.id, task])).values()]
  const disabled = busy || Boolean(pending)
  return <>
    {(tasks.length > 0 || detail.linkedTasksHasMore) && <section className="recordDetail__panel"><h2>연결된 일정</h2>
      {tasks.map((task) => <article key={task.id}><h3>{task.title}</h3><p>{new Date(task.startsAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · {task.assignee?.displayName || '담당 미지정'} · {({ PENDING: '예정', COMPLETED: '완료', CANCELED: '취소' })[task.executionStatus]}</p></article>)}
      {detail.linkedTasksHasMore && <><label>추가로 조회할 날짜<input type="date" value={taskDate} disabled={disabled} onChange={(event) => { setTaskDate(event.target.value); setMoreTasks({ items: [], nextCursor: null, hasMore: true }) }} /></label>{moreTasks.hasMore && <button disabled={disabled || !taskDate} onClick={loadTasks}>선택 날짜의 연결 일정 더 보기</button>}</>}
    </section>}
    <section className="recordDetail__panel"><h2>기록 관리</h2>
      {(detail.jobs || []).filter((job) => job.inputVersion === detail.inputVersion && job.status === 'FAILED').map((job) => <div key={job.id}><p>{job.error?.message} {job.error?.userAction}</p>{job.canRetry && <button disabled={disabled} onClick={() => mutate(createRecordMutation(`/processing-jobs/${encodeURIComponent(job.id)}/retry`, { expectedInputVersion: detail.inputVersion }))}>{({ TRANSCRIBE: '전사', OCR: '문서 읽기', ANALYZE: '분석' })[job.jobType]} 다시 시도</button>}</div>)}
      {!editing && <button disabled={disabled} onClick={() => setEditing(true)}>기록 정보 수정</button>}
      {editing && <form onSubmit={(event) => {
        event.preventDefault()
        const values = Object.fromEntries(new FormData(event.currentTarget))
        if (!values.title.trim()) { setMessage('기록 이름을 입력해 주세요.'); return }
        mutate(createRecordMutation(`/encounters/${encodeURIComponent(detail.id)}`, { expectedVersion: detail.version, title: values.title.trim(), occurredOn: values.occurredOn || null, hospitalName: values.hospitalName.trim() || null }, 'PATCH'))
      }}><fieldset disabled={disabled}>
        <label>기록 이름<input name="title" defaultValue={detail.title} required maxLength={150} /></label>
        <label>진료일<input name="occurredOn" type="date" defaultValue={detail.occurredOn || ''} /></label>
        <label>병원<input name="hospitalName" defaultValue={detail.hospitalName || ''} maxLength={150} /></label>
        <button type="submit">변경 저장</button><button type="button" onClick={() => setEditing(false)}>취소</button>
      </fieldset></form>}
      <button disabled={disabled} onClick={deletionPreview}>기록 삭제</button>
      {preview && <section role="dialog" aria-modal="false" aria-label="기록 삭제 영향 확인" className="recordDetail__deletion">
        <h3>삭제 영향을 확인해 주세요</h3>
        <p>원본 {preview.removeSourceIds?.length || 0}개, 처방 {preview.removeMedicationIds?.length || 0}개가 제거되고 일정 {preview.cancelOccurrenceIds?.length || 0}개가 취소돼요.</p>
        <p>다른 기록과 공유한 일정 {preview.retainSharedOccurrenceIds?.length || 0}개는 유지돼요.{preview.preserveCompletedHistory ? ' 완료 이력의 최소 정보는 보존돼요.' : ''}</p>
        <p>삭제 접수 후 기록을 열 수 없으며 원본 파일은 순차적으로 파기돼요.</p>
        <button disabled={disabled} onClick={() => mutate(createRecordMutation(`/encounters/${encodeURIComponent(detail.id)}`, { expectedVersion: preview.encounterVersion, previewToken: preview.previewToken }, 'DELETE'), true)}>영향을 확인했어요 · 삭제</button>
        <button disabled={disabled} onClick={() => setPreview(null)}>취소</button>
      </section>}
      {message && <p role="alert">{message}</p>}
      {pending && !busy && <button onClick={() => mutate(pending.attempt, pending.deleting)}>같은 요청 다시 시도</button>}
    </section>
  </>
}

export default function RecordDetail({ analysis = false }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { state } = useLocation()
  const encounterId = params.get('encounterId') || state?.encounterId
  const accessToken = getAccessToken()
  const { detail, message, stopped, reload } = useRecordDetail(encounterId, accessToken)
  const summary = detail?.summary
  return <main className="recordAudioAnalysisPage recordDetail">
    <header className="recordAudioAnalysisPage__header"><button className="recordAudioAnalysisPage__back" aria-label="보관함으로 돌아가기" onClick={() => navigate('/doc')}><Icon name="back-button" width={11} height={19} aria-hidden="true" /></button><h1>{analysis ? '진료 기록 분석' : '기록 열람'}</h1></header>
    <div className="recordAudioAnalysisPage__content">
      <p role="status" className="recordAudioAnalysisPage__message">{message}</p>
      {!accessToken && <button onClick={() => navigate('/loginselect')}>로그인하기</button>}
      {stopped && <button className="recordAudioAnalysisPage__refresh" onClick={reload}>상태 다시 조회</button>}
      {detail && <>
        <dl className="recordAudioAnalysisPage__details">
          <div><dt>기록 이름</dt><dd>{detail.title}</dd></div><div><dt>돌봄 기록</dt><dd>{detail.recordType === 'VISIT' ? '진료 기록' : '문서 기록'}</dd></div>
          <div><dt>병원</dt><dd>{detail.hospitalName || '미상'}</dd></div><div><dt>진료일</dt><dd>{detail.occurredOn || '미상'}</dd></div>
          <div><dt>작성자</dt><dd>{detail.createdBy?.displayName || '부모 정보 입력 전'}</dd></div>
        </dl>
        <section className="recordAudioAnalysisPage__summary"><h2>쉬운 요약</h2>
          {(detail.isSummaryStale || (summary && summary.inputVersion !== detail.inputVersion)) && <p role="status">이전 자료 기준의 분석이에요. 최신 결과는 아직 준비되지 않았어요.</p>}
          <p>{summary?.text || '요약이 아직 준비되지 않았어요.'}</p>
          <Evidence items={summary?.evidence} sources={detail.sources || []} accessToken={accessToken} inputVersion={detail.inputVersion} />
        </section>
        <section className="recordAudioAnalysisPage__highlights"><h2>진료 핵심</h2>
          {sections.map(([key, label]) => (summary?.details?.[key] || []).map((item, index) => <article key={`${key}-${index}`}><h3>{label}</h3><p>{item.text}</p><Evidence items={item.evidence} sources={detail.sources || []} accessToken={accessToken} inputVersion={detail.inputVersion} /></article>))}
          {!summary && <p>분석이 완료되면 여기에 표시돼요.</p>}
        </section>
        <RecordReview key={`${detail.id}:${detail.inputVersion}:${detail.version}:${detail.summary?.revisionId}:${detail.processingState}`} detail={detail} accessToken={accessToken} onChanged={reload} />
        <RecordSources detail={detail} accessToken={accessToken} onSaved={reload} />
        <RecordActions key={`${detail.id}:${detail.version}:${detail.inputVersion}`} detail={detail} accessToken={accessToken} reload={reload} />
      </>}
    </div>
    <BottomButton content="보관함에서 보기" onClick={() => navigate('/doc')} />
  </main>
}

