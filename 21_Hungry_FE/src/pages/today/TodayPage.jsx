import './TodayPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { getAccessToken, messageOf } from '../../api/http'
import {
  acceptHandoff,
  completeTask,
  declineHandoff,
  getDateTasks,
  getTaskCalendar,
  getTodayContext,
} from '../../api/todayApi'

import BottomButton from '../../components/bottom-button/BottomButton'
import TitleHeader from '../../components/title-header/TitleHeader'
import { Icon } from '../../components/icon/Icon'

import DayCard from './component/DayCard'
import ReqCard from './component/ReqCard'
import TodayCalendar from './component/TodayCalendar'

import {
  formatDate,
  getKstDate,
  getParentName,
  getVisibleTasks,
  toScheduleCard,
} from './todayUtils'

const errorMessage = (error) => error.message || messageOf(error)

function TodayPage() {
  const navigate = useNavigate()

  const [today] = useState(getKstDate)
  const [selectedDate, setSelectedDate] = useState(today)
  const [displayMonth, setDisplayMonth] = useState(today.slice(0, 7))
  const [calendarOpen, setCalendarOpen] = useState(false)
  const [mineOnly, setMineOnly] = useState(false)

  const [contextResult, setContextResult] = useState(null)
  const [contextRetry, setContextRetry] = useState(0)
  const [allResult, setAllResult] = useState(null)
  const [mineResult, setMineResult] = useState(null)
  const [calendarResult, setCalendarResult] = useState(null)

  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState(null)
  const [revision, setRevision] = useState(0)

  const actionLock = useRef(false)
  const actionKeys = useRef(new Map())
  const mounted = useRef(false)

  const currentContext = contextResult?.key === contextRetry ? contextResult : null
  const context = currentContext?.data
  const contextError = currentContext?.error || ''

  const group = context?.group
  const groupId = group?.id
  const selectedMonth = selectedDate.slice(0, 7)

  const allKey = JSON.stringify([contextRetry, groupId, selectedDate, revision])

  const mineKey = JSON.stringify([contextRetry, groupId, selectedDate, mineOnly, revision])

  const calendarKey = JSON.stringify([contextRetry, groupId, selectedMonth, displayMonth, revision])

  useEffect(() => {
    mounted.current = true

    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()

    getTodayContext({ signal: controller.signal })
      .then((result) => {
        if (!controller.signal.aborted) {
          setContextResult({
            key: contextRetry,
            data: result,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setContextResult({
            key: contextRetry,
            data: null,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [navigate, contextRetry])

  useEffect(() => {
    if (!groupId) return

    const controller = new AbortController()

    getDateTasks(groupId, selectedDate, false, {
      signal: controller.signal,
    })
      .then((items) => {
        if (!controller.signal.aborted) {
          setAllResult({
            key: allKey,
            data: items,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setAllResult({
            key: allKey,
            data: null,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [groupId, selectedDate, allKey])

  useEffect(() => {
    if (!groupId || !mineOnly) return

    const controller = new AbortController()

    getDateTasks(groupId, selectedDate, true, {
      signal: controller.signal,
    })
      .then((items) => {
        if (!controller.signal.aborted) {
          setMineResult({
            key: mineKey,
            data: items,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setMineResult({
            key: mineKey,
            data: null,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [groupId, selectedDate, mineOnly, mineKey])

  useEffect(() => {
    if (!groupId) return

    const controller = new AbortController()
    const months = [...new Set([selectedMonth, displayMonth])]

    Promise.all(
      months.map((month) =>
        getTaskCalendar(groupId, month, {
          signal: controller.signal,
        }),
      ),
    )
      .then((results) => {
        if (!controller.signal.aborted) {
          setCalendarResult({
            key: calendarKey,
            data: Object.fromEntries(results.map((result) => [result.month, result.days])),
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setCalendarResult({
            key: calendarKey,
            data: null,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [groupId, selectedMonth, displayMonth, calendarKey])

  const refresh = () => {
    setRevision((value) => value + 1)
  }

  const selectDate = (date) => {
    if (date !== selectedDate) {
      setSelectedDate(date)
      setAllResult(null)
      setMineResult(null)
    }

    if (date.slice(0, 7) !== displayMonth) {
      setCalendarResult(null)
    }

    setDisplayMonth(date.slice(0, 7))
    setCalendarOpen(false)
    setActionError('')
  }

  const changeMonth = (month) => {
    if (month === displayMonth) return

    setDisplayMonth(month)
    setCalendarResult(null)
  }

  const changeFilter = (value) => {
    if (value === mineOnly) return

    setMineOnly(value)
    setMineResult(null)
  }

  const runAction = async (action, task) => {
    if (actionLock.current || !context?.user) return

    const signature = [
      action,
      task.id,
      task.version,
      task.openHandoff?.id,
      task.openHandoff?.version,
    ].join(':')

    let key = actionKeys.current.get(signature)

    if (!key) {
      key = crypto.randomUUID()
      actionKeys.current.set(signature, key)
    }

    actionLock.current = true
    setBusy({ id: task.id, action })
    setActionError('')

    try {
      if (action === 'complete') {
        await completeTask(task, context.user.id, key)
      }

      if (action === 'accept') {
        await acceptHandoff(task, key)
      }

      if (action === 'decline') {
        await declineHandoff(task, key)
      }

      actionKeys.current.delete(signature)

      if (mounted.current) {
        refresh()
      }
    } catch (error) {
      const status = error.status ?? error.response?.status

      if (status && status < 500 && error.apiCode !== 'REQUEST_IN_PROGRESS') {
        actionKeys.current.delete(signature)
      }

      if (mounted.current) {
        setActionError(errorMessage(error))
        refresh()
      }
    } finally {
      actionLock.current = false

      if (mounted.current) {
        setBusy(null)
      }
    }
  }

  const currentAll = allResult?.key === allKey ? allResult : null
  const currentMine = mineResult?.key === mineKey ? mineResult : null
  const currentCalendar = calendarResult?.key === calendarKey ? calendarResult : null

  const allTasks = group ? currentAll?.data : null

  const filteredTasks = mineOnly ? (group ? currentMine?.data : null) : allTasks

  const calendars = currentCalendar?.data || {}
  const calendarError = currentCalendar?.error || ''
  const calendarLoading = Boolean(groupId && !currentCalendar)

  const { requests, schedules } = getVisibleTasks(allTasks || [], filteredTasks || [])

  const count = group
    ? calendars[selectedMonth]?.find((day) => day.date === selectedDate)?.totalCount
    : undefined

  const parentLabel = group ? `${getParentName(group)} 님` : '부모님'

  const listError = currentAll?.error || (mineOnly ? currentMine?.error : '') || ''

  const listLoading = Boolean(groupId && !listError && (!allTasks || !filteredTasks))

  return (
    <div className='today__page'>
      <TitleHeader
        content={`${parentLabel}을 위한\n돌봄 일정 ${count ?? '-'}개가 있어요`}
        subcontent={formatDate(selectedDate)}
      />

      <div className='today__content'>
        <div className='today__content--cards'>
          <button
            type='button'
            className='today__date-toggle'
            aria-expanded={calendarOpen}
            aria-controls='today-calendar'
            onClick={() => setCalendarOpen((value) => !value)}
          >
            <span>{formatDate(selectedDate)}</span>

            <Icon
              name='month-next'
              width={9}
              height={15}
              className={`today__calendar-arrow${calendarOpen ? ' today__calendar-arrow--open' : ''}`}
              aria-hidden='true'
            />
          </button>

          {calendarOpen && (
            <TodayCalendar
              month={displayMonth}
              selectedDate={selectedDate}
              today={today}
              days={calendars[displayMonth] || []}
              loading={calendarLoading}
              onMonthChange={changeMonth}
              onSelect={selectDate}
            />
          )}

          <div className='today__filters' role='group' aria-label='일정 필터'>
            {[
              { value: true, label: '내 일정만 보기' },
              { value: false, label: '모든 일정 보기' },
            ].map((filter) => (
              <button
                type='button'
                key={filter.label}
                className={`today__filter${mineOnly === filter.value ? ' today__filter--selected' : ''}`}
                aria-pressed={mineOnly === filter.value}
                onClick={() => changeFilter(filter.value)}
              >
                {filter.label}
              </button>
            ))}
          </div>

          {contextError && (
            <div className='today__message' role='alert'>
              <p>{contextError}</p>

              <button type='button' onClick={() => setContextRetry((value) => value + 1)}>
                다시 시도하기
              </button>
            </div>
          )}

          {!context && !contextError && (
            <p className='today__message' role='status'>
              가족 정보를 불러오고 있어요.
            </p>
          )}

          {context && !group && (
            <p className='today__message'>가족 연결을 완료하면 돌봄 일정을 확인할 수 있어요.</p>
          )}

          {calendarError && (
            <div className='today__message' role='alert'>
              <p>{calendarError}</p>

              <button type='button' onClick={refresh}>
                캘린더 다시 불러오기
              </button>
            </div>
          )}

          {actionError && (
            <p className='today__message today__message--error' role='alert'>
              {actionError}
            </p>
          )}

          {requests.map((task) => (
            <ReqCard
              key={task.id}
              schedule={toScheduleCard(task, group)}
              task={task}
              busy={busy}
              onAccept={() => runAction('accept', task)}
              onDecline={() => runAction('decline', task)}
            />
          ))}

          {listError && (
            <div className='today__message' role='alert'>
              <p>{listError}</p>

              <button type='button' onClick={refresh}>
                일정 다시 불러오기
              </button>
            </div>
          )}

          {listLoading && (
            <p className='today__message' role='status'>
              일정을 불러오고 있어요.
            </p>
          )}

          {!listError &&
            schedules.map((task) => (
              <DayCard
                key={task.id}
                schedule={toScheduleCard(task, group)}
                task={task}
                userId={context.user.id}
                recipientId={group.recipient.id}
                busy={busy}
                onComplete={() => runAction('complete', task)}
              />
            ))}

          {group &&
            !listLoading &&
            !listError &&
            requests.length === 0 &&
            schedules.length === 0 && (
              <p className='today__message'>
                {mineOnly
                  ? '선택한 날짜에 내가 맡은 미완료 일정이 없어요.'
                  : '선택한 날짜에 미완료 일정이 없어요.'}
              </p>
            )}
        </div>
      </div>

      <BottomButton
        content='돌봄 일정 추가하기'
        disabled={!group || Boolean(busy)}
        onClick={() => navigate('/todayadd')}
      />
    </div>
  )
}

export default TodayPage
