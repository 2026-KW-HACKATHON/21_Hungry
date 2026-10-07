import BottomButton from '../../components/bottom-button/BottomButton'
import "./ServiceInfo.css"
function ServiceInfo() {
  return (
    <main className="ServiceInfoPage">
        <h1 className="ServiceInfoPage__title">부모님 부양 걱정,<br />KnowOne이 덜어드릴게요</h1>
        <p className="ServiceInfoPage__SubTitle">부모님을 함께 부양하고<br />건강 변화를 차곡차곡 기록해요</p>
        <BottomButton content="KnowOne 시작하기" />
        <p className="ServiceInfoPage__description">건강 정보는 동의한 가족에게만 공유돼요</p>
    </main>
  )
}

export default ServiceInfo