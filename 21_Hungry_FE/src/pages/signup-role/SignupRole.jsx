
import { useState } from 'react'
import { signup, login, me, navigateForOnboarding } from '../../api/authApi'
import { messageOf } from '../../api/http'
import { useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import "./SignupRole.css"

const ROLE_ROUTES = {
  MainUser: '/signupmainuser',
  SubUser: '/signupsubuser',
  Parent: '/signupparent',
}

function SignupRole() {
  const navigate = useNavigate()
  const [selectedRole, setSelectedRole] = useState(null)
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')

  const handleNext = async () => {
    if (!selectedRole || busy) return
    sessionStorage.setItem('signup_role', selectedRole)

    if (selectedRole !== 'Parent') {
      navigate(ROLE_ROUTES[selectedRole])
      return
    }

    setBusy(true)
    setError('')

    try {
      const phoneNumber = sessionStorage.getItem('signup_phone')
      await signup({ phoneNumber, accountRole: 'PARENT' })
      await login(phoneNumber)
      navigateForOnboarding(navigate, await me())
    } catch(e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="SignupPage">
      <LoginPage_title
        title={
          <>
            어떤 분이 이 기기를<br />사용하시나요?
          </>
        }
      />

      <div className="Signup__SelectUserType">
        <button
          type="button"
          className='Signup__UserTypeBox--MainUser'
          aria-pressed={selectedRole === 'MainUser'}
          onClick={() => setSelectedRole('MainUser')}
        >
          <span className='Signup__UserTypeBox__Title--MainUser'>
            주돌봄자녀
          </span>
          <span className='Signup__UserTypeBox__Description--MainUser'>
            가족 요청을 관리하고 부모님의 건강·생활 기록을 <br />
            확인, 가족과 공유하는 기능이 포함되어 있어요.
          </span>
        </button>

        <button
          type="button"
          className='Signup__UserTypeBox--SubUser'
          aria-pressed={selectedRole === 'SubUser'}
          onClick={() => setSelectedRole('SubUser')}
        >
          <span className='Signup__UserTypeBox__Title--SubUser'>
            공동돌봄자녀
          </span>
          <span className='Signup__UserTypeBox__Description--SubUser'>
            부모님의 건강·생활 기록을 확인,<br />
            가족과 공유하는 기능이 포함되어 있어요.
          </span>
        </button>

        <button
          type="button"
          className='Signup__UserTypeBox--Parent'
          aria-pressed={selectedRole === 'Parent'}
          onClick={() => setSelectedRole('Parent')}
        >
          <span className='Signup__UserTypeBox__Title--Parent'>
            부모
          </span>
          <span className='Signup__UserTypeBox__Description--Parent'>
            자녀에게 안부와 건강기록을 전하고,<br />
            공유 범위를 정하는 기능이 포함되어 있어요
          </span>
        </button>
      </div>

      {error && <p role="alert" style={{color:"#d33"}}>{error}</p>}

      <BottomButton
        content={busy ? "처리 중..." : "다음"}
        disabled={!selectedRole || busy}
        onClick={handleNext}
      />
    </main>
  )
}

export default SignupRole;
