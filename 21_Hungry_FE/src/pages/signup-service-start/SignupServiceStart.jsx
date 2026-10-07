import BottomButton from '../../components/bottom-button/BottomButton'
import './SignupServiceStart.css'
import { useNavigate } from 'react-router-dom' 
function SignupServiceStart() {
  const navigate = useNavigate()
  return (
    <main className="SignupServiceStartPage">
      <h1 className="SignupServiceStartPage__title">
        부모님 부양 걱정,<br />KnowOne이 덜어드릴게요
      </h1>
      <p className="SignupServiceStartPage__subtitle">
        부모님을 함께 부양하고<br />건강 변화를 차곡차곡 기록해요
      </p>
      <div className="SignupServiceStartPage__footer">
        <BottomButton onClick={()=>navigate('/')} content="KnowOne 시작하기" />
        <p className="SignupServiceStartPage__bottomsubtitle">
          건강 정보는 동의한 가족에게만 공유돼요
        </p>
      </div>
    </main>
  )
}

export default SignupServiceStart
