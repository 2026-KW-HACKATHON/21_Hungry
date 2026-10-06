import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import TitleHeader from '../../components/title-header/TitleHeader'

function TodayPage() {
  return (
    <div className='today__page'>
      <TitleHeader
        content={
          <p>
            부모1 님을 위한
            <br />
            돌봄 일정 n개가 있어요
          </p>
        }
        subcontent={'--년 --월 --일'}
      />
      <div className='today__content'>
        <PopupButton content='완료된 일정입니다' color='notice' />
      </div>
      <BottomButton content='돌봄 일정 추가하기' />
    </div>
  )
}

export default TodayPage
