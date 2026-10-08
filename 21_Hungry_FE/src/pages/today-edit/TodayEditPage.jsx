import './TodayEditPage.css'

import { useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import CardInfo from '../../components/card-info/CardInfo'
import { Icon } from '../../components/icon/Icon'
import HandoffPopup from './component/HandoffPopup'

import { getAccessToken, messageOf } from '../../api/http'
import { assignCreatedTask, getTodayAddContext } from '../../api/todayAddApi'
import { completeTask } from '../../api/todayApi'
import {
  buildTimePatch,
  getTodayEditTask,
  releaseTodayEditTask,
  updateTodayEditTask,
} from '../../api/todayEditApi'
import { getKstDate, getParentName, toScheduleCard } from '../today/todayUtils'

const errorMessage = (error) => error.message || messageOf(error)
const isValidTime = (value) => /^([01]\d|2[0-3]):[0-5]\d$/.test(value)

function openNativePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    event.currentTarget.focus()
  }
}

function ScheduleInfo({ card }) {
  return (
    <CardInfo
      category={card.category}
      label1='날짜'
      value1={card.date}
      label2='시간'
      value2={card.time}
      label3='담당 가족'
      value3={card.family}
    />
  )
}

function TodayEditForm({ initialTask, context, onReload }) {
  const navigate = useNavigate()
  const { group, user, members } = context

  const [task, setTask] = useState(initialTask)
  const [date, setDate] = useState(() => getKstDate(initialTask.startsAt))
  const [time, setTime] = useState(() => toScheduleCard(initialTask, group).time)
  const [selectedFamilyId, setSelectedFamilyId] = useState(null)
  const [action, setAction] = useState('')
  const [handoffCard, setHandoffCard] = useState(null)
  const [actionError, setActionError] = useState('')

  const formRef = useRef(null)
  const dateRef = useRef(null)
  const timeRef = useRef(null)
  const busyRef = useRef(false)
  const requestKeys = useRef(new Map())
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true

    return () => {
      mounted.current = false
    }
  }, [])

  const card = toScheduleCard(task, group)
  const editable = task.executionStatus === 'PENDING'
  const locked = Boolean(action) || !editable || Boolean(handoffCard)
  const selectedId = selectedFamilyId ?? task.assignee?.id

  const timeChanged = date !== getKstDate(task.startsAt) || time !== card.time

  const familyChanged = selectedFamilyId !== null && selectedFamilyId !== task.assignee?.id

  const isMine = task.assignee?.id === user.id
  const canRelease = editable && isMine && task.canRelease

  const relation = {
    MOTHER: '어머니',
    FATHER: '아버지',
  }[group.parentProfile?.relation]

  const keyFor = (operation, currentTask, payload) => {
    const signature = JSON.stringify([operation, currentTask.id, currentTask.version, payload])

    if (!requestKeys.current.has(signature)) {
      requestKeys.current.set(signature, crypto.randomUUID())
    }

    return requestKeys.current.get(signature)
  }

  const handleSave = async (event) => {
    event.preventDefault()
    if (busyRef.current || locked) return

    dateRef.current.setCustomValidity(date ? '' : '날짜를 선택해 주세요.')
    timeRef.current.setCustomValidity(isValidTime(time) ? '' : '시간을 선택해 주세요.')

    if (!formRef.current.reportValidity()) return

    busyRef.current = true
    setAction('save')
    setActionError('')

    let current = task
    let timeSaved = false

    try {
      if (timeChanged) {
        const patch = buildTimePatch(current, date, time)

        current = await updateTodayEditTask(current, patch, keyFor('edit', current, patch))

        timeSaved = true

        if (!mounted.current) return
        setTask(current)
      }

      if (selectedFamilyId !== null && selectedFamilyId !== current.assignee?.id) {
        const result = await assignCreatedTask(
          current,
          selectedFamilyId,
          keyFor('assign', current, selectedFamilyId),
        )

        current = result.occurrence

        if (!mounted.current) return
        setTask(current)
      }

      if (mounted.current) {
        navigate('/today', { replace: true })
      }
    } catch (error) {
      if (mounted.current) {
        setActionError(
          `${timeSaved ? '날짜·시간은 저장됐지만 담당 가족 변경에 실패했어요. ' : ''}${errorMessage(error)}`,
        )
      }
    } finally {
      busyRef.current = false

      if (mounted.current) {
        setAction('')
      }
    }
  }

  const handleTaskAction = async (operation) => {
    if (busyRef.current || locked) return

    if (timeChanged || familyChanged) {
      window.alert('변경사항을 먼저 저장한 뒤 수정 화면에서 다시 진행해 주세요.')
      return
    }

    if (operation === 'handoff' && !canRelease) return
    if (operation === 'complete' && !isMine) return

    busyRef.current = true
    setAction(operation)
    setActionError('')

    try {
      if (operation === 'handoff') {
        const result = await releaseTodayEditTask(task, keyFor('handoff', task, null))

        if (!mounted.current) return

        setTask(result.occurrence)

        setHandoffCard(
          toScheduleCard(
            {
              ...result.occurrence,
              assignee: result.handoff.previousAssignee || task.assignee,
            },
            group,
          ),
        )
      } else {
        await completeTask(task, user.id, keyFor('complete', task, user.id))

        if (mounted.current) {
          navigate('/today', { replace: true })
        }
      }
    } catch (error) {
      if (mounted.current) {
        setActionError(errorMessage(error))
      }
    } finally {
      busyRef.current = false

      if (mounted.current) {
        setAction('')
      }
    }
  }

  return (
    <>
      <form ref={formRef} className='todayEdit__content' onSubmit={handleSave}>
        <div className='todayEdit__card--container'>
          <ScheduleInfo card={card} />

          {editable && isMine && (
            <PopupButton
              content={action === 'complete' ? '처리 중...' : '완료로 표시하기'}
              color='gray'
              disabled={locked}
              onClick={() => handleTaskAction('complete')}
            />
          )}
        </div>

        <div className='todayEdit__description'>
          <div className='todayEdit__description--title'>상세 설명</div>

          <div className='todayEdit__description--description'>{card.description || '-'}</div>
        </div>

        <div className='todayEdit__notice'>
          상세 설명에는 장소와 특정 행동을 입력해주는 것이 좋아요
        </div>

        <div className='todayEdit__inputs'>
          <div className='todayEdit__input'>
            <input
              ref={dateRef}
              className='todayEdit__input--input'
              type='date'
              min='0001-01-01'
              max='9999-12-31'
              aria-label='날짜'
              required
              disabled={locked}
              value={date}
              onClick={openNativePicker}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setDate(event.target.value)
              }}
            />

            <button
              type='button'
              aria-label='날짜 지우기'
              disabled={locked || !date}
              onClick={() => {
                setDate('')
                dateRef.current.setCustomValidity('')
                dateRef.current.focus()
              }}
            >
              <Icon name='input-cancel' width={24} height={24} />
            </button>
          </div>

          <div className='todayEdit__input'>
            <input
              ref={timeRef}
              className='todayEdit__input--input'
              type='time'
              step={60}
              aria-label='시간'
              required
              disabled={locked}
              value={time}
              onClick={openNativePicker}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setTime(event.target.value)
              }}
            />

            <button
              type='button'
              aria-label='시간 지우기'
              disabled={locked || !time}
              onClick={() => {
                setTime('')
                timeRef.current.setCustomValidity('')
                timeRef.current.focus()
              }}
            >
              <Icon name='input-cancel' width={24} height={24} />
            </button>
          </div>
        </div>

        <div className='todayEdit__notice'>시간은 24시간제로 입력해 주세요</div>

        <div className='todayEdit__family'>
          <div className='todayEdit__family--title'>담당 가족</div>

          <div className='todayEdit__family--items' role='group' aria-label='담당 가족 선택'>
            {members.map((member) => {
              const family = member.user
              const isParent = family.id === group.recipient.id

              const name = isParent ? getParentName(group) : family.displayName

              const category = isParent ? relation : family.id === user.id ? '나' : null

              const checked = selectedId === family.id
              const icon = checked
                ? family.id === task.assignee?.id
                  ? 'check-confirm'
                  : 'check-suggest'
                : 'check-none'

              return (
                <button
                  type='button'
                  className='todayEdit__family--item'
                  key={family.id}
                  aria-pressed={checked}
                  disabled={locked}
                  onClick={() => setSelectedFamilyId(family.id)}
                >
                  <span className='todayEdit__family--family'>
                    {name || '-'}
                    {category && `(${category})`}
                  </span>

                  <Icon name={icon} width={50} height={50} />
                </button>
              )
            })}
          </div>

          {editable && isMine && (
            <div className='todayEdit__button'>
              <PopupButton
                content={action === 'handoff' ? '인계 중...' : '다른 가족들에게 인계하기'}
                color='green'
                disabled={locked || !canRelease}
                title={
                  !canRelease
                    ? '현재 담당자인 본인만 기한 전의 일정을 인계할 수 있어요.'
                    : undefined
                }
                onClick={() => handleTaskAction('handoff')}
              />
            </div>
          )}
        </div>

        <div className='todayEdit__notice'>담당 가족을 선택한 뒤 변경사항을 저장해 주세요</div>

        {!editable && (
          <p className='todayEdit__status'>완료되거나 취소된 일정은 수정할 수 없어요.</p>
        )}

        {actionError && (
          <div className='todayEdit__status' role='alert'>
            <p>{actionError}</p>

            <button type='button' disabled={locked} onClick={onReload}>
              최신 일정 다시 불러오기
            </button>
          </div>
        )}
      </form>

      <BottomButton
        content={action === 'save' ? '저장 중...' : '변경사항 저장하기'}
        disabled={locked}
        onClick={() => formRef.current.requestSubmit()}
      />

      {handoffCard && (
        <HandoffPopup card={handoffCard} onConfirm={() => navigate('/today', { replace: true })} />
      )}
    </>
  )
}

function TodayEditPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const taskId = searchParams.get('id')

  const [result, setResult] = useState(null)
  const [retry, setRetry] = useState(0)

  const loadKey = JSON.stringify([taskId, retry])
  const loaded = result?.key === loadKey ? result : null

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    if (!taskId) return

    const controller = new AbortController()
    const config = { signal: controller.signal }

    Promise.all([getTodayAddContext(config), getTodayEditTask(taskId, config)])
      .then(([context, task]) => {
        if (!context.group || context.group.id !== task.groupId) {
          throw new Error('이 일정을 수정할 가족 연결 정보를 확인할 수 없어요.')
        }

        if (!controller.signal.aborted) {
          setResult({
            key: loadKey,
            context,
            task,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setResult({
            key: loadKey,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [taskId, loadKey, navigate])

  if (!taskId) {
    return <Navigate to='/today' replace />
  }

  return (
    <div className='todayEdit__page'>
      <BackHeader
        content='돌봄 일정 수정'
        subcontent={'돌봄 일정의 시간을 수정하고\n담당자를 변경할 수 있어요'}
      />

      {!loaded ? (
        <div className='todayEdit__content'>
          <p className='todayEdit__status' role='status'>
            일정을 불러오고 있어요.
          </p>
        </div>
      ) : loaded.error ? (
        <div className='todayEdit__content todayEdit__status' role='alert'>
          <p>{loaded.error}</p>

          <button type='button' onClick={() => setRetry((value) => value + 1)}>
            다시 시도하기
          </button>
        </div>
      ) : (
        <TodayEditForm
          key={loadKey}
          initialTask={loaded.task}
          context={loaded.context}
          onReload={() => setRetry((value) => value + 1)}
        />
      )}
    </div>
  )
}

export default TodayEditPage
