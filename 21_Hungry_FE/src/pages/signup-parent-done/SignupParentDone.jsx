import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupParentDone.css"

function SignupParentDone() {
  return (
    <main className="SignupParentPageDone">
      <h1 className="SignupParentPageDone__title">
        모든설정을 완료했어요
      </h1>
      <BottomButton content="다음" />
    </main>
  )
}

export default SignupParentDone