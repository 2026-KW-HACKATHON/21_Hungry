import BottomButton from '../../components/bottom-button/BottomButton'

function TodayPage() {
  return (
    <div className='today__page'>
      <div className='today__title'>
        <div className='today__title--title'>
          부모1 님을 위한
          <br />
          돌봄 일정 -개가 있어요
        </div>
        <div className='today__title--date'>--년 --월 --일</div>
      </div>
      <div className='today__content'></div>
      <BottomButton content='돌봄 일정 추가하기' />
    </div>
  )
}

export default TodayPage
