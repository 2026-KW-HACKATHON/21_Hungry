import './SchedulePage.css'

import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'

import TitleHeader from '../../components/title-header/TitleHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'
import { currentUserId, getFamilyMembers } from '../../mocks/familyMock'
import { formatDate, getTodayDate } from '../../mocks/todayAddMock'
import {
  availabilityOptions,
  getCalendarWeeks,
  getVisibleRangeSegments,
  isAvailabilityVisible,
} from '../../mocks/familyScheduleMock'

import { getPersonalAvailability } from '../../mocks/scheduleMock'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

function SchedulePage() {
  const navigate = useNavigate()
  const member = getFamilyMembers().find((item) => item.userId === currentUserId)
  const [initialMonth] = useState(() => {
    const [year, month] = getTodayDate().split('-').map(Number)
    return year * 12 + month - 1
  })
  const [monthOffset, setMonthOffset] = useState(0)
  const [selectedModes, setSelectedModes] = useState([])

  if (!member) return <Navigate to='/family' replace />

  const monthIndex = initialMonth + monthOffset
  const year = Math.floor(monthIndex / 12)
  const month = monthIndex % 12
  const weeks = getCalendarWeeks(year, month)
  const { ranges, days } = getPersonalAvailability(year, month)

  const handleFilter = (mode) => {
    setSelectedModes((previous) =>
      previous.includes(mode) ? previous.filter((item) => item !== mode) : [...previous, mode],
    )
  }

  return (
    <div className='schedule__page'>
      <TitleHeader content={`${member.name}(나) 님의 개인 일정`} subcontent='' />

      <div className='schedule__content'>
        <div className='schedule__month'>
          <div className='schedule__month--title' aria-live='polite'>
            {year}년 {month + 1}월
          </div>
          <div className='schedule__month--buttons'>
            <button
              type='button'
              className='schedule__month--previous'
              aria-label='이전 달'
              disabled={monthOffset === -12}
              onClick={() => setMonthOffset((previous) => Math.max(-12, previous - 1))}
            >
              <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
            </button>
            <button
              type='button'
              className='schedule__month--next'
              aria-label='다음 달'
              disabled={monthOffset === 12}
              onClick={() => setMonthOffset((previous) => Math.min(12, previous + 1))}
            >
              <Icon name='month-next' width={9} height={15} aria-hidden='true' />
            </button>
          </div>
        </div>

        <div className='schedule__filters' role='group' aria-label='돌봄 가능 상태 필터'>
          {availabilityOptions.map((option) => {
            const selected = selectedModes.includes(option.mode)
            return (
              <button
                type='button'
                key={option.mode}
                className={`schedule__filter${selected ? ' schedule__filter--selected' : ''}`}
                aria-pressed={selected}
                onClick={() => handleFilter(option.mode)}
              >
                <span
                  className={`schedule__filter--icon schedule__filter--${option.mode.toLowerCase()}`}
                >
                  <Icon
                    name={option.icon}
                    width={option.mode === 'PARTIAL' ? 32 : 85}
                    height={option.mode === 'PARTIAL' ? 34 : 28}
                    aria-hidden='true'
                  />
                </span>
                <span className='schedule__filter--label'>{option.label}</span>
              </button>
            )
          })}
        </div>

        <div
          className='schedule__calendar'
          role='grid'
          aria-label={`${year}년 ${month + 1}월 돌봄 가능 날짜`}
        >
          <div className='schedule__weekdays' role='row'>
            {weekdays.map((weekday) => (
              <div className='schedule__weekday' role='columnheader' key={weekday}>
                {weekday}
              </div>
            ))}
          </div>

          {weeks.map((week, weekIndex) => (
            <div className='schedule__week' role='row' key={weekIndex}>
              <div className='schedule__ranges' aria-hidden='true'>
                {getVisibleRangeSegments(week, ranges, days, selectedModes).map(
                  (segment, index) => (
                    <div
                      className={`schedule__range schedule__range--${segment.mode.toLowerCase()}`}
                      key={index}
                      style={{
                        '--start-column': segment.startColumn,
                        '--end-column': segment.endColumn,
                      }}
                    >
                      {segment.showStart && <span className='schedule__range--start' />}
                      {segment.showEnd && <span className='schedule__range--end' />}
                    </div>
                  ),
                )}
              </div>

              {week.map((day, column) => {
                if (!day)
                  return (
                    <div className='schedule__day' aria-hidden='true' key={`empty-${column}`} />
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
                    className='schedule__day'
                    role='gridcell'
                    aria-label={`${formatDate(day.date)} ${label}`}
                    key={day.date}
                  >
                    {visible && mode === 'PARTIAL' && (
                      <span className='schedule__day--partial-icon'>
                        <Icon
                          name='availability-partial-day'
                          width={38}
                          height={40}
                          aria-hidden='true'
                        />
                      </span>
                    )}
                    <span
                      className={`schedule__day--number${visible ? ` schedule__day--${mode.toLowerCase()}` : ''}${endpoint ? ' schedule__day--endpoint' : ''}`}
                    >
                      {day.day}
                    </span>
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>

      <BottomButton content='일정 수정하기' onClick={() => navigate('/scheduleedit')} />
    </div>
  )
}

export default SchedulePage
