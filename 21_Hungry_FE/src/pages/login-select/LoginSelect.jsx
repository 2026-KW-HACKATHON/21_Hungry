
import BottomButton from '../../components/bottom-button/BottomButton'
import "./LoginSelect.css"
import LoginPage_title from "../../components/loginPage-title/loginPage-title"
import { useState } from 'react'
import { login, me, navigateForOnboarding } from '../../api/authApi'
import { normalizePhone, validPhone, messageOf } from '../../api/http'
import { useNavigate } from 'react-router-dom'

function LoginSelect() {
  const navigate = useNavigate()

  const [phone, setPhone] = useState('')
  const [isVerified, setIsVerified] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // 전화번호 입력
  const handlePhoneChange = (e) => {
    setPhone(e.target.value)
    setIsVerified(false)
    setError('')
  }

  // 인증번호 받기 (시연용)
  const handleVerification = () => {
    if (!validPhone(phone)) return

    setIsVerified(true)
    setError('')
  }

  // 로그인 API 요청
  const handleLogin = async () => {
    if (!validPhone(phone) || !isVerified || busy) return

    setBusy(true)
    setError('')

    try {
      await login(normalizePhone(phone))
      const user = await me()
      navigateForOnboarding(navigate, user)
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="LoginSelectPage">
      <LoginPage_title
        title={
          <>
            가입한 휴대전화 번호로 <br />
            로그인해 주세요
          </>
        }
      />

      <input
        type="text"
        placeholder="휴대전화 번호"
        className="LoginSelect__input"
        value={phone}
        onChange={handlePhoneChange}
        inputMode="tel"
        autoComplete="tel"
      />

      <button
        type="button"
        className="LoginSelect__allowButton"
        onClick={handleVerification}
        disabled={!validPhone(phone) || busy}
      >
        {isVerified ? '인증 완료' : '인증번호 받기'}
      </button>

      <div className="Divider">
        <span className="Divider__line"></span>
        <span className="Divider__text">또는</span>
        <span className="Divider__line"></span>
      </div>

      <div className="LoginSelect__socialLogin">
        <button
          disabled
          className="LoginSelect__socialLoginButton--kakao"
        >
          카카오로 로그인
        </button>

        <button
          disabled
          className="LoginSelect__socialLoginButton--google"
        >
          Google로 로그인
        </button>
      </div>

      <div className="LoginSelect__footer">
        {error && (
          <p role="alert" style={{ color: '#d33' }}>
            {error}
          </p>
        )}

        <BottomButton
          content={busy ? '로그인 중...' : '로그인하기'}
          disabled={!isVerified || busy}
          onClick={handleLogin}
        />

        <button
          className="LoginSelect__gotosignup"
          onClick={() => navigate('/signupselect')}
        >
          KnowOne이 처음이신가요?
        </button>
      </div>
    </div>
  )
}

export default LoginSelect
