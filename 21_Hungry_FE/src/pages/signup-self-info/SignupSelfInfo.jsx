import { useState } from 'react'
import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupSelfInfo.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { useNavigate } from 'react-router-dom' 

function SignupSelfInfo() {
  const [name, setName] = useState('')
  const canProceed = name.trim().length > 0
  const navigate = useNavigate()
  return (
    <div className="SignupSelfInfoPage">
      <LoginPage_title
        title={
          <>
            시작하기 위해<br />
            본인의 정보를 입력해주세요
          </>
        }
      />

      <input
        type="text"
        placeholder="이름"
        aria-label="이름"
        value={name}
        onChange={(event) => setName(event.target.value)}
        className="SignupSelfInfo__input"
      />
      <BottomButton onClick={()=>navigate('/signupparentinfo')} content="다음" disabled={!canProceed} />
    </div>
  )
}

export default SignupSelfInfo