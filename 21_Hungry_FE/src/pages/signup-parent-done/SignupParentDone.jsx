
import { useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupParentDone.css"

function SignupParentDone() {
  const navigate = useNavigate()

  return (
    <main className="SignupParentPageDone">
      <h1 className="SignupParentPageDone__title">
        모든설정을 완료했어요
      </h1>
      <BottomButton
        content="시작하기"
        onClick={() => navigate('/schedule', {replace:true})}
      />
    </main>
  )
}

export default SignupParentDone
