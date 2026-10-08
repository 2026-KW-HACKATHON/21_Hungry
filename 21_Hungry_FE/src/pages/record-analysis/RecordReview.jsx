import { useEffect, useRef, useState } from 'react'
import { createRecordMutation, requestRecordApi, sendRecordMutation } from '../../api/recordApi'
import { reviewProblem } from '../../api/recordModel'
import { Evidence } from './RecordSources'

const reasonLabels = { MED_CHANGE: '복약 내용 확인', CONFLICT: '자료 간 내용 불일치', MISSING_DATE: '날짜 확인', MISSING_TIME: '시각 확인', DUPLICATE: '중복 가능성', CONDITIONAL: '조건부 계획' }

function Field({ label, value, onChange, type = 'text', required = true, maxLength }) {
  return <label>{label}<input type={type} value={value ?? ''} required={required} maxLength={maxLength} onChange={(event) => onChange(type === 'number' ? (event.target.value === '' ? null : Number(event.target.value)) : event.target.value)} /></label>
}

function RuleFields({ value, onChange, medication = false }) {
  const change = (key, next) => onChange({ ...value, [key]: next })
  return <div className="recordDetail__fields">
    <label>반복<select value={value.recurrence || ''} required onChange={(event) => onChange({ ...value, recurrence: event.target.value, weekdays: [], ...(medication && event.target.value === 'ONCE' ? { lastDate: value.firstDate } : {}) })}>
      <option value="">선택해 주세요</option><option value="ONCE">한 번</option><option value="DAILY">매일</option><option value="WEEKLY">매주</option>
    </select></label>
    <Field label={medication ? '일정 시작일' : '날짜'} type="date" value={medication ? value.firstDate : value.date} onChange={(next) => onChange({ ...value, [medication ? 'firstDate' : 'date']: next, ...(medication && value.recurrence === 'ONCE' ? { lastDate: next } : {}) })} />
    {(medication || value.recurrence !== 'ONCE') && <Field label="반복 종료일" type="date" value={value.lastDate} required={medication} onChange={(next) => change('lastDate', next || null)} />}
    <Field label="시각" type="time" value={medication ? value.localTime : value.time} onChange={(next) => change(medication ? 'localTime' : 'time', next)} />
    <Field label="계획 소요 시간(분)" type="number" value={value.durationMinutes} onChange={(next) => onChange({ ...value, durationMinutes: next, ...(!medication ? { durationSource: 'USER_INPUT' } : {}) })} />
    {value.recurrence === 'WEEKLY' && <div className="recordDetail__weekdays">{['월', '화', '수', '목', '금', '토', '일'].map((day, index) => <label key={day}><input type="checkbox" checked={value.weekdays?.includes(index + 1) || false} onChange={(event) => change('weekdays', event.target.checked ? [...(value.weekdays || []), index + 1].sort() : value.weekdays.filter((item) => item !== index + 1))} />{day}</label>)}</div>}
  </div>
}

function ReviewEditor({ item, busy, onConfirm, onCancel }) {
  const [payload, setPayload] = useState(() => ({ ...structuredClone(item.payload), schemaVersion: 2, ...(item.itemType === 'TASK' && item.payload.kind === 'PICKUP' ? { kind: 'OTHER' } : {}) }))
  const [note, setNote] = useState('')
  const [message, setMessage] = useState('')
  const change = (key, value) => setPayload((previous) => ({ ...previous, [key]: value }))
  const medication = item.itemType === 'MEDICATION'
  return <form onSubmit={(event) => {
    event.preventDefault()
    const resolution = item.reviewReasons?.includes('CONFLICT') ? { mode: 'MANUAL', note: note.trim() } : null
    const problem = reviewProblem(item, payload, resolution)
    setMessage(problem)
    if (!problem) onConfirm([{ itemId: item.id, expectedVersion: item.version, payload, conflictResolution: resolution }])
  }}><fieldset disabled={busy}>
    {medication ? <>
      <Field label="약명" value={payload.name} maxLength={150} onChange={(value) => change('name', value)} />
      <Field label="1회 용량" value={payload.doseText} maxLength={100} onChange={(value) => change('doseText', value)} />
      <Field label="복용 횟수" value={payload.frequencyText} maxLength={150} onChange={(value) => change('frequencyText', value)} />
      <Field label="복용 시작일" type="date" value={payload.startsOn} onChange={(value) => change('startsOn', value)} />
      <Field label="복용 종료일" type="date" value={payload.endsOn} onChange={(value) => change('endsOn', value)} />
      <Field label="복용 안내" value={payload.instructions} required={false} onChange={(value) => change('instructions', value || null)} />
      {(payload.schedulePlans || []).map((plan, index) => <div key={index} className="recordDetail__plan"><h4>복용 일정 {index + 1}</h4><RuleFields medication value={plan} onChange={(next) => change('schedulePlans', payload.schedulePlans.map((entry, at) => at === index ? next : entry))} /><button type="button" onClick={() => change('schedulePlans', payload.schedulePlans.filter((_, at) => at !== index))}>이 시각 제거</button></div>)}
      <button type="button" onClick={() => change('schedulePlans', [...(payload.schedulePlans || []), { recurrence: null, firstDate: payload.startsOn || null, lastDate: payload.endsOn || null, weekdays: [], localTime: null, durationMinutes: null }])}>복용 시각 추가</button>
    </> : <>
      <Field label="일정 이름" value={payload.title} maxLength={150} onChange={(value) => change('title', value)} />
      <label>일정 종류<select value={payload.kind || ''} required onChange={(event) => change('kind', event.target.value)}><option value="">선택</option><option value="HOSPITAL">병원</option><option value="EXAM">검사</option><option value="OTHER">기타</option></select></label>
      <RuleFields value={payload} onChange={setPayload} />
      <Field label="설명" value={payload.description} required={false} onChange={(value) => change('description', value || null)} />
    </>}
    {item.reviewReasons?.includes('CONFLICT') && <Field label="원문 대조 후 수정한 내용" value={note} onChange={setNote} />}
    {message && <p role="alert">{message}</p>}
    <button type="submit">수정한 내용으로 확인</button><button type="button" onClick={onCancel}>취소</button>
  </fieldset></form>
}

export default function RecordReview({ detail, accessToken, onChanged }) {
  const [result, setResult] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const [editing, setEditing] = useState(null)
  const [later, setLater] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const lock = useRef(false)
  const [pendingAttempt, setPendingAttempt] = useState(null)
  useEffect(() => {
    const controller = new AbortController()
    requestRecordApi(`/encounters/${encodeURIComponent(detail.id)}/review-items?reviewState=ALL`, accessToken, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setResult({ data }) })
      .catch((error) => { if (!controller.signal.aborted) setResult({ error: error.message }) })
    return () => controller.abort()
  }, [detail.id, detail.inputVersion, accessToken, refresh])
  const data = result?.data
  const items = (data?.items ?? []).filter((item) => ['NEEDS_REVIEW', 'READY'].includes(item.reviewState))
  const isCurrent = data?.isAnalysisCurrent && data.inputVersion === detail.inputVersion && !detail.isSummaryStale
  const medications = items.filter((item) => item.itemType === 'MEDICATION')
  const batchReady = medications.length > 0 && medications.every((item) => !reviewProblem(item))

  async function mutate(next) {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setPendingAttempt(next)
    try {
      await sendRecordMutation(next, accessToken)
      setPendingAttempt(null)
      setMessage('확인 내용이 가족 전체에 반영됐어요.')
      setEditing(null)
      onChanged()
    } catch (error) {
      setMessage(error.message)
      if (error.status >= 400 && error.status < 500 && !['REQUEST_IN_PROGRESS'].includes(error.apiCode)) {
        setPendingAttempt(null)
        if (error.status === 409) { setResult(null); setEditing(null); setRefresh((value) => value + 1); onChanged() }
      }
    } finally { lock.current = false; setBusy(false) }
  }
  function confirm(itemsToConfirm) {
    if (!isCurrent || pendingAttempt) return
    mutate(createRecordMutation(`/encounters/${encodeURIComponent(detail.id)}/review-items/confirm`, { expectedInputVersion: data.inputVersion, revisionId: data.revisionId, items: itemsToConfirm }))
  }
  if (result?.error) return <section className="recordDetail__panel"><p role="alert">확인할 내용을 불러오지 못했어요. {result.error}</p><button onClick={() => setRefresh((value) => value + 1)}>다시 조회</button></section>
  if (!data) return <p role="status">확인할 내용을 조회하고 있어요.</p>
  if (!items.length) return null
  return <section className="recordDetail__panel">
    <h2>확인할 내용 ({items.length})</h2>
    <p>확인한 약·일정이 가족 전체에 반영돼요. 사용자 확인은 의료진 검증을 뜻하지 않아요.</p>
    {!isCurrent && <p role="status">최신 분석이 완료된 뒤 확인할 수 있어요.</p>}
    {later ? <button onClick={() => setLater(false)}>확인할 내용 다시 열기</button> : <>
      {items.map((item) => <article key={item.id} className="recordDetail__reviewItem">
        <h3>{item.payload.name || item.payload.title || '내용 확인 필요'}</h3>
        <p>{item.itemType === 'MEDICATION' ? `${item.payload.doseText || '용량 미상'} · ${item.payload.frequencyText || '횟수 미상'} · ${item.payload.startsOn || '시작일 미상'} ~ ${item.payload.endsOn || '종료일 미상'}` : `${item.payload.date || '날짜 미상'} ${item.payload.time || '시각 미상'} · ${item.payload.durationMinutes ?? '?'}분`}</p>
        {item.payload.schedulePlans?.map((plan, index) => <p key={index}>복용 시각: {plan.localTime || '미상'} · {({ ONCE: '한 번', DAILY: '매일', WEEKLY: '매주' })[plan.recurrence] || '반복 미상'} {plan.recurrence === 'WEEKLY' ? (plan.weekdays || []).map((day) => ['월', '화', '수', '목', '금', '토', '일'][day - 1]).join(', ') : ''} · {plan.firstDate || '?'} ~ {plan.lastDate || '?'}</p>)}
        {item.payload.instructions && <p>{item.payload.instructions}</p>}
        {item.payload.supersedesMedicationId && <p>기존 처방을 복용 시작일부터 변경해요. 원문과 적용일을 확인해 주세요.</p>}
        <p>{(item.reviewReasons || []).map((reason) => reasonLabels[reason] || '내용 확인 필요').join(' · ')}</p>
        <Evidence items={item.evidence} sources={detail.sources || []} accessToken={accessToken} inputVersion={detail.inputVersion} />
        {editing === item.id ? <ReviewEditor item={item} busy={busy || Boolean(pendingAttempt)} onConfirm={confirm} onCancel={() => setEditing(null)} /> : <>
          {item.itemType !== 'MEDICATION' && <button disabled={!isCurrent || busy || Boolean(pendingAttempt) || Boolean(reviewProblem(item))} onClick={() => confirm([{ itemId: item.id, expectedVersion: item.version }])}>맞아요</button>}
          <button disabled={!isCurrent || busy || Boolean(pendingAttempt)} onClick={() => setEditing(item.id)}>수정</button>
          <button disabled={!isCurrent || busy || Boolean(pendingAttempt)} onClick={() => {
            if (window.confirm('이 항목을 일정·복약에 반영하지 않고 제외할까요?')) mutate(createRecordMutation(`/encounters/${encodeURIComponent(detail.id)}/review-items/${encodeURIComponent(item.id)}/dismiss`, { expectedVersion: item.version }))
          }}>항목 제외</button>
          {reviewProblem(item) && <p>{reviewProblem(item)}</p>}
        </>}
      </article>)}
      {medications.length > 0 && <button disabled={!isCurrent || !batchReady || busy || Boolean(pendingAttempt) || Boolean(editing)} onClick={() => confirm(medications.map((item) => ({ itemId: item.id, expectedVersion: item.version })))}>복약 내용 모두 맞아요</button>}
      <button disabled={busy || Boolean(pendingAttempt)} onClick={() => setLater(true)}>나중에</button>
    </>}
    {message && <p role="status">{message}</p>}
    {pendingAttempt && !busy && <button onClick={() => mutate(pendingAttempt)}>같은 요청 다시 시도</button>}
  </section>
}

