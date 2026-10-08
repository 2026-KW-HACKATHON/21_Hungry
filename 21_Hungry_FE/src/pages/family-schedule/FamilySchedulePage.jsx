import './FamilySchedulePage.css'

import { useEffect, useState } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import { Icon } from '../../components/icon/Icon'
import PopupButton from '../../components/popup-button/PopupButton'
import { getAccessToken, messageOf } from '../../api/http'
import { getTodayAddContext as getFamilyContext } from '../../api/todayAddApi'
import { getMemberAvailabilityDays } from '../../api/familyScheduleApi'
import { formatDate, getKstDate, getParentName } from '../today/todayUtils'
import {
  availabilityOptions,
  getCalendarWeeks,
  getMonthRange,
  toAvailabilityCalendar,
  getVisibleRangeSegments,
  isAvailabilityVisible,
} from '../schedule/scheduleUtils'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

function FamilySchedule({ userId, accessToken }) {
  const [initialMonth] = useState(() => {
    const [year, month] = getKstDate().split('-').map(Number)
    return year * 12 + month - 1
  })
  const [monthOffset, setMonthOffset] = useState(0)
  const [selectedModes, setSelectedModes] = useState([])

  const [contextResult, setContextResult] = useState(null)
  const [contextRetry, setContextRetry] = useState(0)
  const [availabilityResult, setAvailabilityResult] = useState(null)
  const [availabilityRetry, setAvailabilityRetry] = useState(0)

  const currentContext = contextResult?.key === contextRetry ? contextResult : null
  const context = currentContext?.data
  const group = context?.group
  const member = context?.members.find(
    (item) => item.user.id === userId && item.status === 'ACTIVE',
  )
  const groupId = group?.id
  const memberId = member?.id
  const contextError = currentContext?.error || ''

  const monthIndex = initialMonth + monthOffset
  const year = Math.floor(monthIndex / 12)
  const month = monthIndex % 12
  const weeks = getCalendarWeeks(year, month)
  const { fromDate, toDateExclusive } = getMonthRange(year, month)
  const requestKey = JSON.stringify([
    contextRetry,
    groupId,
    memberId,
    fromDate,
    toDateExclusive,
    availabilityRetry,
  ])
  const currentAvailability = availabilityResult?.key === requestKey ? availabilityResult : null
  const availability = currentAvailability?.data
  const error = contextError || currentAvailability?.error || ''
  const isLoading = !currentContext || Boolean(member && !currentAvailability)
  const { ranges, days } = toAvailabilityCalendar(availability?.items || [])
  const memberName =
    member?.role === 'RECIPIENT' ? getParentName(group) : member?.user.displayName || '-'

  useEffect(() => {
    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    getFamilyContext({
      signal: controller.signal,
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then((data) => {
        if (isActive()) setContextResult({ key: contextRetry, data, error: '' })
      })
      .catch((loadError) => {
        if (isActive()) {
          setContextResult({
            key: contextRetry,
            data: null,
            error: loadError.message || messageOf(loadError),
          })
        }
      })

    return () => controller.abort()
  }, [accessToken, contextRetry])

  useEffect(() => {
    if (!groupId || !memberId) return undefined
    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    getMemberAvailabilityDays(groupId, memberId, fromDate, toDateExclusive, {
      signal: controller.signal,
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then((data) => {
        if (isActive()) setAvailabilityResult({ key: requestKey, data, error: '' })
      })
      .catch((loadError) => {
        if (isActive()) {
          setAvailabilityResult({
            key: requestKey,
            data: null,
            error: loadError.message || messageOf(loadError),
          })
        }
      })

    return () => controller.abort()
  }, [accessToken, groupId, memberId, fromDate, toDateExclusive, requestKey])

  if (context && !member) return <Navigate to='/family' replace />

  const reload = () => {
    if (contextError) setContextRetry((value) => value + 1)
    else setAvailabilityRetry((value) => value + 1)
  }

  const handleFilter = (mode) => {
    setSelectedModes((previous) =>
      previous.includes(mode) ? previous.filter((item) => item !== mode) : [...previous, mode],
    )
  }

  return (
    <div className='familySchedule__page'>
      <BackHeader content={member ? `${memberName} 님의 개인 일정` : '개인 일정'} subcontent='' />

      <div className='familySchedule__content'>
        <div className='familySchedule__month'>
          <div className='familySchedule__month--title' aria-live='polite'>
            {year}년 {month + 1}월
          </div>
          <div className='familySchedule__month--buttons'>
            <button
              type='button'
              className='familySchedule__month--previous'
              aria-label='이전 달'
              disabled={monthOffset === -12}
              onClick={() => setMonthOffset((previous) => Math.max(-12, previous - 1))}
            >
              <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
            </button>
            <button
              type='button'
              className='familySchedule__month--next'
              aria-label='다음 달'
              disabled={monthOffset === 12}
              onClick={() => setMonthOffset((previous) => Math.min(12, previous + 1))}
            >
              <Icon name='month-next' width={9} height={15} aria-hidden='true' />
            </button>
          </div>
        </div>

        {isLoading && (
          <p role='status' style={{ color: '#666666' }}>
            {currentContext ? '일정을 불러오고 있어요.' : '가족 정보를 불러오고 있어요.'}
          </p>
        )}

        {error && (
          <div role='alert'>
            <p style={{ color: '#ff6666', marginBottom: 10 }}>{error}</p>
            <PopupButton
              content='다시 불러오기'
              color='gray'
              disabled={isLoading}
              onClick={reload}
            />
          </div>
        )}

        <div className='familySchedule__filters' role='group' aria-label='돌봄 가능 상태 필터'>
          {availabilityOptions.map((option) => {
            const selected = selectedModes.includes(option.mode)
            return (
              <button
                type='button'
                key={option.mode}
                className={`familySchedule__filter${selected ? ' familySchedule__filter--selected' : ''}`}
                aria-pressed={selected}
                onClick={() => handleFilter(option.mode)}
              >
                <span
                  className={`familySchedule__filter--icon familySchedule__filter--${option.mode.toLowerCase()}`}
                >
                  <Icon
                    name={option.icon}
                    width={option.mode === 'PARTIAL' ? 32 : 85}
                    height={option.mode === 'PARTIAL' ? 34 : 28}
                    aria-hidden='true'
                  />
                </span>
                <span className='familySchedule__filter--label'>{option.label}</span>
              </button>
            )
          })}
        </div>

        {availability && (
          <div
            className='familySchedule__calendar'
            role='grid'
            aria-label={`${year}년 ${month + 1}월 돌봄 가능 날짜`}
          >
            <div className='familySchedule__weekdays' role='row'>
              {weekdays.map((weekday) => (
                <div className='familySchedule__weekday' role='columnheader' key={weekday}>
                  {weekday}
                </div>
              ))}
            </div>

            {weeks.map((week, weekIndex) => (
              <div className='familySchedule__week' role='row' key={weekIndex}>
                <div className='familySchedule__ranges' aria-hidden='true'>
                  {getVisibleRangeSegments(week, ranges, days, selectedModes).map(
                    (segment, index) => (
                      <div
                        className={`familySchedule__range familySchedule__range--${segment.mode.toLowerCase()}`}
                        key={index}
                        style={{
                          '--start-column': segment.startColumn,
                          '--end-column': segment.endColumn,
                        }}
                      >
                        {segment.showStart && <span className='familySchedule__range--start' />}
                        {segment.showEnd && <span className='familySchedule__range--end' />}
                      </div>
                    ),
                  )}
                </div>

                {week.map((day, column) => {
                  if (!day)
                    return (
                      <div
                        className='familySchedule__day'
                        aria-hidden='true'
                        key={`empty-${column}`}
                      />
                    )

                  const mode = days[day.date] ?? null
                  const visible = isAvailabilityVisible(mode, selectedModes)
                  const endpoint =
                    visible &&
                    ranges.some(
                      (range) =>
                        range.mode === mode &&
                        (range.fromDate === day.date || range.toDate === day.date),
                    )
                  const label =
                    availabilityOptions.find((option) => option.mode === mode)?.label ?? '미등록'

                  return (
                    <div
                      className='familySchedule__day'
                      role='gridcell'
                      aria-label={`${formatDate(day.date)} ${label}`}
                      key={day.date}
                    >
                      {visible && mode === 'PARTIAL' && (
                        <span className='familySchedule__day--partial-icon'>
                          <Icon
                            name='availability-partial-day'
                            width={38}
                            height={40}
                            aria-hidden='true'
                          />
                        </span>
                      )}
                      <span
                        className={`familySchedule__day--number${visible ? ` familySchedule__day--${mode.toLowerCase()}` : ''}${endpoint ? ' familySchedule__day--endpoint' : ''}`}
                      >
                        {day.day}
                      </span>
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function FamilySchedulePage() {
  const [searchParams] = useSearchParams()
  const userId = searchParams.get('userId') || ''
  const accessToken = getAccessToken()

  if (!accessToken) return <Navigate to='/loginselect' replace />
  if (!userId) return <Navigate to='/family' replace />

  return (
    <FamilySchedule key={`${userId}:${accessToken}`} userId={userId} accessToken={accessToken} />
  )
}

export default FamilySchedulePage
