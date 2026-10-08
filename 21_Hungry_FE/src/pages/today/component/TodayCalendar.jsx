import './TodayCalendar.css'
import { Icon } from '../../../components/icon/Icon'
import { formatDate, getCalendarDays, shiftMonth } from '../todayUtils'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']

function TodayCalendar({ month, selectedDate, today, days, loading, onMonthChange, onSelect }) {
  const summaries = new Map(days.map((day) => [day.date, day]))
  const [year, number] = month.split('-')

  return (
    <div className='todayCalendar' id='today-calendar' aria-busy={loading}>
      <div className='todayCalendar__header'>
        <button
          type='button'
          aria-label='이전 달'
          onClick={() => onMonthChange(shiftMonth(month, -1))}
        >
          <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
        </button>
        <span>{`${year}년 ${Number(number)}월`}</span>
        <button
          type='button'
          aria-label='다음 달'
          onClick={() => onMonthChange(shiftMonth(month, 1))}
        >
          <Icon name='month-next' width={9} height={15} aria-hidden='true' />
        </button>
      </div>

      <div className='todayCalendar__grid'>
        {weekdays.map((weekday) => (
          <span className='todayCalendar__weekday' key={weekday}>
            {weekday}
          </span>
        ))}

        {getCalendarDays(month).map((day) => {
          const summary = summaries.get(day.date)
          const indicator = summary?.indicator
          const selected = day.date === selectedDate
          const isToday = day.date === today
          const label = [
            formatDate(day.date),
            isToday ? '오늘' : '',
            summary ? `전체 일정 ${summary.totalCount}개` : '',
            indicator === 'ATTENTION' ? '미지정 일정 있음' : '',
          ]
            .filter(Boolean)
            .join(', ')

          return (
            <button
              type='button'
              key={day.date}
              className={[
                'todayCalendar__day',
                !day.isCurrentMonth ? 'todayCalendar__day--outside' : '',
                isToday ? 'todayCalendar__day--today' : '',
                selected ? 'todayCalendar__day--selected' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              aria-label={label}
              aria-pressed={selected}
              aria-current={isToday ? 'date' : undefined}
              onClick={() => onSelect(day.date)}
            >
              <span>{day.day}</span>

              {indicator && indicator !== 'NONE' && (
                <span
                  className={`todayCalendar__dot${indicator === 'ATTENTION' ? ' todayCalendar__dot--attention' : ''}`}
                  aria-hidden='true'
                />
              )}
            </button>
          )
        })}
      </div>

      <div className='todayCalendar__legend'>
        <span>
          <i className='todayCalendar__dot' />
          일정 있음
        </span>
        <span>
          <i className='todayCalendar__dot todayCalendar__dot--attention' />
          미지정 일정
        </span>
      </div>

      {loading && (
        <p className='todayCalendar__loading' role='status'>
          캘린더를 불러오고 있어요.
        </p>
      )}
    </div>
  )
}

export default TodayCalendar
