import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupServiceStart.css"

function SignupServiceStart() {
  return (
    <main className="SignupServiceStartPage">
      <h1 className="SignupServiceStartPage__title">
        부모님 부양 걱정,<br />KnowOne이 덜어드릴게요
      </h1>
      <p className="SignupServiceStartPage__subtitle">
        자녀의 휴대전화에서 설정이 완료되면<br />Knowon을 시작할 수 있어요
      </p>
      <BottomButton content="다음" />
      <p className="SignupServiceStartPage__bottomsubtitle">건강 정보는 동의한 가족에게만 공유돼요</p>
    </main>
  )
}

export default SignupServiceStart