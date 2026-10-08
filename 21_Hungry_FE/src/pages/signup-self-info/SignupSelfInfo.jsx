
import { useState } from 'react'
import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupSelfInfo.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { signup, login, recipientLookup } from '../../api/authApi'
import { messageOf } from '../../api/http'
import { useNavigate } from 'react-router-dom'

function SignupSelfInfo() {
  const [name, setName] = useState('')
  const canProceed = name.trim().length > 0
  const navigate = useNavigate()
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')

  const next = async () => {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      const phoneNumber = sessionStorage.getItem('signup_phone')
      await signup({
        phoneNumber,
        accountRole:'CHILD',
        displayName:name.trim()
      })
      await login(phoneNumber)

      const result = await recipientLookup(
        sessionStorage.getItem('signup_parent_phone')
      )

      sessionStorage.setItem('signup_parent_lookup', JSON.stringify(result))

      navigate(
        result.parentProfileCompleted
          ? '/signupfamilyinfo'
          : '/signupparentinfo'
      )
    } catch(e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

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

      {error && <p role="alert" style={{color:"#d33"}}>{error}</p>}

      <BottomButton
        onClick={next}
        content={busy ? "가입 중..." : "다음"}
        disabled={!canProceed || busy}
      />
    </div>
  )
}

export default SignupSelfInfo
