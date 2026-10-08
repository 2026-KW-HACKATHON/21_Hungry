import './FamilySchedulePage.css'

import { useState } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import { Icon } from '../../components/icon/Icon'
import { getFamilyMembers } from '../../mocks/familyMock'
import { formatDate, getTodayDate } from '../../mocks/todayAddMock'
import {
  availabilityOptions,
  getCalendarWeeks,
  getFamilyAvailability,
  getVisibleRangeSegments,
  isAvailabilityVisible,
} from '../../mocks/familyScheduleMock'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

function FamilySchedulePage() {
  const [searchParams] = useSearchParams()
  const member = getFamilyMembers().find((item) => item.userId === searchParams.get('userId'))
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
  const { ranges, days } = getFamilyAvailability(member.userId, year, month)

  const handleFilter = (mode) => {
    setSelectedModes((previous) =>
      previous.includes(mode) ? previous.filter((item) => item !== mode) : [...previous, mode],
    )
  }

  return (
    <div className='familySchedule__page'>
      <BackHeader content={`${member.name} 님의 개인 일정`} subcontent='' />

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
      </div>
    </div>
  )
}

export default FamilySchedulePage
