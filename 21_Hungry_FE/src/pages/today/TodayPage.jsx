import './TodayPage.css'

import BottomButton from '../../components/bottom-button/BottomButton'
import TitleHeader from '../../components/title-header/TitleHeader'
import DayCard from './component/DayCard'
import ReqCard from './component/ReqCard'

function TodayPage() {
  const reqData = [
    {
      id: 1,
      category: '기타 돌봄',
      date: '2026년 06월 01일',
      time: '10:00',
      family: '가족1',
      description: '떠넘긴 일정 1',
    },
    {
      id: 2,
      category: '약 복용',
      date: '2026년 06월 02일',
      time: '10:00',
      family: '가족1',
      description: '떠넘긴 일정 1',
    },
  ]
  const scheduleData = [
    {
      id: 1,
      category: '기타 돌봄',
      date: '2024년 06월 01일',
      time: '10:00',
      family: '가족1',
      description: '돌봄 일정 1',
    },
    {
      id: 2,
      category: '약 복용',
      date: '2024년 06월 02일',
      time: '14:00',
      family: '가족2',
      description: '돌봄 일정 2',
    },
    {
      id: 3,
      category: '병원 내원',
      date: '2024년 06월 03일',
      time: '16:00',
      family: '가족3',
      description: '돌봄 일정 3',
    },
    {
      id: 4,
      category: '건강검진',
      date: '2024년 06월 04일',
      time: '12:00',
      family: '가족4',
      description: '돌봄 일정 4',
    },
  ]
  return (
    <div className='today__page'>
      <TitleHeader
        content={`부모1 님을 위한\n돌봄 일정 n개가 있어요`}
        subcontent={'--년 --월 --일'}
      />
      <div className='today__content'>
        <div className='today__content--cards'>
          {reqData.map((schedule) => (
            <ReqCard key={schedule.id} schedule={schedule} />
          ))}
          {scheduleData.map((schedule) => (
            <DayCard key={schedule.id} schedule={schedule} />
          ))}
        </div>
      </div>
      <BottomButton content='돌봄 일정 추가하기' />
    </div>
  )
}

export default TodayPage
