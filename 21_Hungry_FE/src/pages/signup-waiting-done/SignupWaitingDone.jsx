import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupWaitingDone.css"

function SignupWaitingDone() {
  return (
    <main className="SignupWaitingDonePage">
      <h1 className="SignupWaitingDonePage__title">
        주돌봄자녀가<br />
        요청을 승인했어요
      </h1>
      <BottomButton content="다음" />
    </main>
  )
}

export default SignupWaitingDone