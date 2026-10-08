import { useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { login, me, clearSignupDraft } from '../../api/authApi'
import { normalizePhone, validPhone, messageOf, clearSession } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import './LoginSelect.css'

function LoginSelect() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const submitting = useRef(false)

  const [phone, setPhone] = useState(state?.phoneNumber || '')
  const [verificationCode, setVerificationCode] = useState('')
  const [codeRequested, setCodeRequested] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const canProceed = validPhone(phone) && codeRequested && /^[0-9]{6}$/.test(verificationCode)

  // 전화번호 입력
  const handlePhoneChange = (event) => {
    setPhone(event.target.value)
    setVerificationCode('')
    setCodeRequested(false)
    setError('')
  }

  // 인증번호 받기 (시연용)
  const handleVerificationRequest = () => {
    if (!validPhone(phone) || busy) return

    setVerificationCode('')
    setCodeRequested(true)
    setError('')
  }

  const handleVerificationCodeChange = (event) => {
    setVerificationCode(event.target.value.replace(/[^0-9]/g, '').slice(0, 6))
    setError('')
  }

  // 로그인 API 요청
  const handleLogin = async () => {
    if (!canProceed || submitting.current) return

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      await login(normalizePhone(phone))
      await me()

      clearSignupDraft()

      navigate('/today', { replace: true })
    } catch (e) {
      clearSession()
      setError(e.message || messageOf(e))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <div className='LoginSelectPage'>
      <LoginPage_title
        title={
          <>
            가입한 휴대전화 번호로
            <br />
            로그인해 주세요
          </>
        }
      />

      <input
        type='text'
        placeholder='휴대전화 번호'
        aria-label='휴대전화 번호'
        className='LoginSelect__input'
        value={phone}
        onChange={handlePhoneChange}
        disabled={busy}
        inputMode='tel'
        autoComplete='tel'
      />

      {codeRequested ? (
        <input
          type='text'
          placeholder='인증번호 6자리'
          aria-label='인증번호 6자리'
          className='LoginSelect__input'
          value={verificationCode}
          onChange={handleVerificationCodeChange}
          disabled={busy}
          inputMode='numeric'
          autoComplete='one-time-code'
          maxLength={6}
        />
      ) : (
        <button
          type='button'
          className='LoginSelect__allowButton'
          onClick={handleVerificationRequest}
          disabled={!validPhone(phone) || busy}
        >
          인증번호 받기
        </button>
      )}

      {state?.signupCompleted && <p role='status'>회원가입이 완료됐어요. 로그인해 주세요.</p>}

      <div className='Divider'>
        <span className='Divider__line'></span>
        <span className='Divider__text'>또는</span>
        <span className='Divider__line'></span>
      </div>

      <div className='LoginSelect__socialLogin'>
        <button type='button' disabled className='LoginSelect__socialLoginButton--kakao'>
          카카오로 로그인
        </button>

        <button type='button' disabled className='LoginSelect__socialLoginButton--google'>
          Google로 로그인
        </button>
      </div>

      <div className='LoginSelect__footer'>
        {error && (
          <p role='alert' style={{ color: '#d33' }}>
            {error}
          </p>
        )}

        <BottomButton
          content={busy ? '로그인 중...' : '로그인하기'}
          disabled={!canProceed || busy}
          onClick={handleLogin}
        />

        <button
          type='button'
          disabled={busy}
          className='LoginSelect__gotosignup'
          onClick={() => navigate('/signupselect')}
        >
          KnowOne이 처음이신가요?
        </button>
      </div>
    </div>
  )
}

export default LoginSelect
