import './TodayPage.css'

import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BottomButton from '../../components/bottom-button/BottomButton'
import TitleHeader from '../../components/title-header/TitleHeader'
import DayCard from './component/DayCard'
import ReqCard from './component/ReqCard'
import {
  formatDate,
  getTodayDate,
  getTodaySchedules,
  requestData,
  toScheduleCard,
} from '../../mocks/todayAddMock'

function TodayPage() {
  const navigate = useNavigate()
  const [schedules] = useState(() => getTodaySchedules())
  const scheduleData = schedules.map(toScheduleCard)

  return (
    <div className='today__page'>
      <TitleHeader
        content={`부모1 님을 위한\n돌봄 일정 ${requestData.length + scheduleData.length}개가 있어요`}
        subcontent={formatDate(getTodayDate())}
      />
      <div className='today__content'>
        <div className='today__content--cards'>
          {requestData.map((schedule) => (
            <ReqCard key={schedule.id} schedule={schedule} />
          ))}
          {scheduleData.map((schedule) => (
            <DayCard key={schedule.id} schedule={schedule} />
          ))}
        </div>
      </div>
      <BottomButton content='돌봄 일정 추가하기' onClick={() => navigate('/todayadd')} />
    </div>
  )
}

export default TodayPage
