import './ScheduleEditPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'

import { formatDate, getKstDate } from '../today/todayUtils'
import { messageOf } from '../../api/http'
import { getAvailabilityDays, previewAvailability, saveAvailability } from '../../api/scheduleApi'
import { useScheduleAvailability } from '../schedule/useScheduleAvailability'
import {
  availabilityOptions,
  getCalendarWeeks,
  getVisibleRangeSegments,
  toAvailabilityCalendar,
  shiftAvailabilityDate,
  buildAvailabilityRequest,
} from '../schedule/scheduleUtils'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

function formatSelectedDate(fromDate, toDate) {
  const [year, month, day] = fromDate.split('-').map(Number)
  const start = `${year}년 ${month}월 ${day}일`
  if (fromDate === toDate) return start

  const [endYear, endMonth, endDay] = toDate.split('-').map(Number)

  return `${start} ~ ${year === endYear ? '' : `${endYear}년 `}${endMonth}월 ${endDay}일`
}

function openNativePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    event.currentTarget.focus()
  }
}

function ScheduleEditPage() {
  const navigate = useNavigate()

  const [monthIndex, setMonthIndex] = useState(() => {
    const [year, month] = getKstDate().split('-').map(Number)
    return year * 12 + month - 1
  })
  const [selectedMode, setSelectedMode] = useState(null)
  const [selection, setSelection] = useState(null)
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')
  const [error, setError] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  const savingRef = useRef(false)
  const mounted = useRef(false)
  const pendingCommit = useRef(null)
  const previewKeys = useRef(new Map())

  useEffect(() => {
    mounted.current = true

    return () => {
      mounted.current = false
    }
  }, [])

  const year = Math.floor(monthIndex / 12)
  const month = monthIndex % 12
  const weeks = getCalendarWeeks(year, month)

  const { items, error: loadError, isLoading, reload } = useScheduleAvailability(year, month)

  const saved = toAvailabilityCalendar(items)

  const ranges =
    selectedMode === null
      ? saved.ranges
      : selection && selectedMode !== 'PARTIAL'
        ? [
            {
              mode: selectedMode,
              fromDate: selection.fromDate,
              toDate: selection.toDate,
            },
          ]
        : []

  const days =
    selectedMode === null
      ? saved.days
      : Object.fromEntries(
          weeks
            .flat()
            .filter(Boolean)
            .map((day) => [
              day.date,
              selection && selection.fromDate <= day.date && day.date <= selection.toDate
                ? selectedMode
                : null,
            ]),
        )

  const option = availabilityOptions.find((item) => item.mode === selectedMode)

  const clearSelection = () => {
    setSelection(null)
    setStartTime('')
    setEndTime('')
    setError('')
  }

  const handleMode = (mode) => {
    setSelectedMode((previous) => (previous === mode ? null : mode))
    clearSelection()
  }

  const handleDate = (date) => {
    setError('')

    if (selectedMode === 'PARTIAL') {
      setSelection({
        fromDate: date,
        toDate: date,
        awaitingEnd: false,
      })
      setStartTime('')
      setEndTime('')
    } else if (!selection || !selection.awaitingEnd) {
      setSelection({
        fromDate: date,
        toDate: date,
        awaitingEnd: true,
      })
    } else {
      setSelection({
        fromDate: date < selection.fromDate ? date : selection.fromDate,
        toDate: date > selection.fromDate ? date : selection.fromDate,
        awaitingEnd: false,
      })
    }
  }

  const handleSave = async () => {
    if (savingRef.current || isLoading || loadError) return

    if (!selection || !selectedMode) {
      setError('돌봄 가능 상태와 날짜를 선택해 주세요.')
      return
    }

    if (selectedMode === 'PARTIAL' && (!startTime || !endTime)) {
      setError('시작 시각과 종료 시각을 입력해 주세요.')
      return
    }

    const draft = {
      mode: selectedMode,
      fromDate: selection.fromDate,
      toDate: selection.toDate,
      startTime,
      endTime,
    }
    const signature = JSON.stringify(draft)

    savingRef.current = true
    setError('')
    setIsSaving(true)

    try {
      let commit = pendingCommit.current

      if (commit?.signature !== signature) {
        const latest = await getAvailabilityDays(
          draft.fromDate,
          shiftAvailabilityDate(draft.toDate, 1),
        )

        if (!mounted.current) return

        const request = buildAvailabilityRequest(draft, latest.items)
        const requestSignature = JSON.stringify(request)

        if (!previewKeys.current.has(requestSignature)) {
          previewKeys.current.set(requestSignature, crypto.randomUUID())
        }

        const preview = await previewAvailability(
          request,
          previewKeys.current.get(requestSignature),
        )

        if (!mounted.current) return

        if (preview.releasedOccurrenceIds.length > 0) {
          const retained =
            preview.retainedPastOccurrenceCount > 0
              ? '\n지난 돌봄 일정과 진행 중인 일정은 유지돼요.'
              : ''

          if (
            !window.confirm(
              `저장하면 앞으로 담당한 돌봄 일정 ${preview.releasedOccurrenceIds.length}개의 담당이 해제돼요.${retained}\n저장할까요?`,
            )
          ) {
            return
          }
        }

        commit = {
          signature,
          payload: {
            ...request,
            previewToken: preview.previewToken,
          },
          key: crypto.randomUUID(),
        }

        pendingCommit.current = commit
      }

      await saveAvailability(commit.payload, commit.key)
      pendingCommit.current = null

      if (mounted.current) {
        navigate('/schedule', { replace: true })
      }
    } catch (error) {
      const status = error.status ?? error.response?.status

      if (
        status &&
        status < 500 &&
        ![408, 429].includes(status) &&
        error.apiCode !== 'REQUEST_IN_PROGRESS'
      ) {
        pendingCommit.current = null
        previewKeys.current.clear()
      }

      if (mounted.current) {
        setError(error.message || messageOf(error))

        if (status === 409) {
          reload()
        }
      }
    } finally {
      savingRef.current = false

      if (mounted.current) {
        setIsSaving(false)
      }
    }
  }

  return (
    <div className='scheduleEdit__page'>
      <BackHeader content='일정 수정' subcontent='' />

      <div className='scheduleEdit__content'>
        <div className='scheduleEdit__month'>
          <div className='scheduleEdit__month--title' aria-live='polite'>
            {year}년 {month + 1}월
          </div>

          <div className='scheduleEdit__month--buttons'>
            <button
              type='button'
              className='scheduleEdit__month--previous'
              aria-label='이전 달'
              disabled={isSaving || isLoading}
              onClick={() => setMonthIndex((previous) => previous - 1)}
            >
              <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
            </button>

            <button
              type='button'
              className='scheduleEdit__month--next'
              aria-label='다음 달'
              disabled={isSaving || isLoading}
              onClick={() => setMonthIndex((previous) => previous + 1)}
            >
              <Icon name='month-next' width={9} height={15} aria-hidden='true' />
            </button>
          </div>
        </div>

        {isLoading && (
          <p role='status' style={{ color: '#666666' }}>
            일정을 불러오고 있어요.
          </p>
        )}

        {loadError && (
          <div role='alert'>
            <p className='scheduleEdit__error' style={{ marginBottom: 10 }}>
              {loadError}
            </p>

            <PopupButton
              content='다시 불러오기'
              color='gray'
              disabled={isSaving || isLoading}
              onClick={reload}
            />
          </div>
        )}

        <div
          className={`scheduleEdit__calendar${selectedMode === 'PARTIAL' ? ' scheduleEdit__calendar--partial' : ''}`}
          role='grid'
          aria-label={`${year}년 ${month + 1}월 돌봄 가능 날짜`}
        >
          <div className='scheduleEdit__weekdays' role='row'>
            {weekdays.map((weekday) => (
              <div className='scheduleEdit__weekday' role='columnheader' key={weekday}>
                {weekday}
              </div>
            ))}
          </div>

          {weeks.map((week, weekIndex) => (
            <div className='scheduleEdit__week' role='row' key={weekIndex}>
              <div className='scheduleEdit__ranges' aria-hidden='true'>
                {getVisibleRangeSegments(week, ranges, days, []).map((segment, index) => (
                  <div
                    className={`scheduleEdit__range scheduleEdit__range--${segment.mode.toLowerCase()}`}
                    key={index}
                    style={{
                      '--start-column': segment.startColumn,
                      '--end-column': segment.endColumn,
                    }}
                  >
                    {segment.showStart && <span className='scheduleEdit__range--start' />}
                    {segment.showEnd && <span className='scheduleEdit__range--end' />}
                  </div>
                ))}
              </div>

              {week.map((day, column) => {
                if (!day) {
                  return (
                    <div className='scheduleEdit__day' aria-hidden='true' key={`empty-${column}`} />
                  )
                }

                const mode = days[day.date] ?? null
                const endpoint = ranges.some(
                  (range) =>
                    range.mode === mode &&
                    (range.fromDate === day.date || range.toDate === day.date),
                )
                const label =
                  availabilityOptions.find((item) => item.mode === mode)?.label ?? '미등록'

                return (
                  <div
                    className='scheduleEdit__day'
                    role='gridcell'
                    aria-label={`${formatDate(day.date)} ${label}`}
                    key={day.date}
                  >
                    {mode === 'PARTIAL' && (
                      <span className='scheduleEdit__day--partial-icon'>
                        <Icon
                          name='availability-partial-day'
                          width={38}
                          height={40}
                          aria-hidden='true'
                        />
                      </span>
                    )}

                    {selectedMode === null ? (
                      <span
                        className={`scheduleEdit__day--number${mode ? ` scheduleEdit__day--${mode.toLowerCase()}` : ''}${endpoint ? ' scheduleEdit__day--endpoint' : ''}`}
                      >
                        {day.day}
                      </span>
                    ) : (
                      <button
                        type='button'
                        className={`scheduleEdit__day--number${mode ? ` scheduleEdit__day--${mode.toLowerCase()}` : ''}${endpoint ? ' scheduleEdit__day--endpoint' : ''}`}
                        aria-label={formatDate(day.date)}
                        aria-pressed={mode !== null}
                        disabled={isSaving || isLoading}
                        onClick={() => handleDate(day.date)}
                      >
                        {day.day}
                      </button>
                    )}
                  </div>
                )
              })}
            </div>
          ))}
        </div>

        <div className='scheduleEdit__filters' role='group' aria-label='추가할 돌봄 가능 상태'>
          {availabilityOptions.map((item) => (
            <button
              type='button'
              key={item.mode}
              className={`scheduleEdit__filter${selectedMode === item.mode ? ' scheduleEdit__filter--selected' : ''}`}
              aria-pressed={selectedMode === item.mode}
              disabled={isSaving || isLoading}
              onClick={() => handleMode(item.mode)}
            >
              <span
                className={`scheduleEdit__filter--icon scheduleEdit__filter--${item.mode.toLowerCase()}`}
              >
                <Icon
                  name={item.icon}
                  width={item.mode === 'PARTIAL' ? 32 : 85}
                  height={item.mode === 'PARTIAL' ? 34 : 28}
                  aria-hidden='true'
                />
              </span>

              <span className='scheduleEdit__filter--label'>{item.label}</span>
            </button>
          ))}
        </div>

        {option && (
          <div className='scheduleEdit__panel' aria-busy={isSaving}>
            <p className='scheduleEdit__panel--title'>돌봄 {option.label}</p>

            {selection && (
              <div className='scheduleEdit__draft'>
                <div className='scheduleEdit__draft--fields'>
                  <div className='scheduleEdit__date'>
                    <p>{formatSelectedDate(selection.fromDate, selection.toDate)}</p>
                  </div>

                  {selectedMode === 'PARTIAL' && (
                    <div className='scheduleEdit__times'>
                      <div className='scheduleEdit__timeField' data-value={startTime || '00:00'}>
                        <input
                          type='time'
                          step={60}
                          onClick={openNativePicker}
                          aria-label='돌봄 가능한 시작 시각'
                          placeholder='00:00'
                          autoComplete='off'
                          value={startTime}
                          disabled={isSaving || isLoading}
                          onChange={(event) => {
                            setStartTime(event.target.value)
                            setError('')
                          }}
                        />
                      </div>

                      <span>부터</span>

                      <div className='scheduleEdit__timeField' data-value={endTime || '00:00'}>
                        <input
                          type='time'
                          step={60}
                          onClick={openNativePicker}
                          aria-label='돌봄 가능한 종료 시각'
                          placeholder='00:00'
                          autoComplete='off'
                          value={endTime}
                          disabled={isSaving || isLoading}
                          onChange={(event) => {
                            setEndTime(event.target.value)
                            setError('')
                          }}
                        />
                      </div>
                    </div>
                  )}
                </div>

                <button
                  type='button'
                  className='scheduleEdit__remove'
                  aria-label='선택한 날짜 지우기'
                  disabled={isSaving || isLoading}
                  onClick={clearSelection}
                >
                  <Icon name='availability-remove' width={50} height={50} aria-hidden='true' />
                </button>
              </div>
            )}

            <div className='scheduleEdit__save'>
              <PopupButton
                content={isSaving ? '저장 중...' : `돌봄 ${option.label}한 날 추가하기`}
                color='blue'
                disabled={isSaving || isLoading || Boolean(loadError) || !selection}
                onClick={handleSave}
              />
            </div>
          </div>
        )}

        {error && (
          <p className='scheduleEdit__error' role='alert'>
            {error}
          </p>
        )}

        {selectedMode === 'PARTIAL' && (
          <div className='scheduleEdit__help'>
            <p>시간은 24시간제로 작성해주세요</p>
            <p>돌봄 일부 가능은 입력한 시작 시각부터 종료 시각까지 돌봄이 가능하다는 의미에요</p>
          </div>
        )}
      </div>
    </div>
  )
}

export default ScheduleEditPage
