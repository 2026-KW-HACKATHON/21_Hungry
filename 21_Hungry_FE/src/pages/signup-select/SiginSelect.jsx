import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { normalizePhone, validPhone } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import './SiginSelect.css'

function SignupSelect() {
  const navigate = useNavigate()

  const [phone, setPhone] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [codeRequested, setCodeRequested] = useState(false)

  const canProceed = validPhone(phone) && codeRequested && /^[0-9]{6}$/.test(verificationCode)

  const handlePhoneChange = (event) => {
    setPhone(event.target.value)
    setVerificationCode('')
    setCodeRequested(false)
  }

  const handleVerificationRequest = () => {
    if (!validPhone(phone)) return

    setVerificationCode('')
    setCodeRequested(true)
  }

  const handleVerificationCodeChange = (event) => {
    setVerificationCode(event.target.value.replace(/[^0-9]/g, '').slice(0, 6))
  }

  const handleNext = () => {
    if (!canProceed) return

    sessionStorage.setItem('signup_phone', normalizePhone(phone))

    navigate('/signuprole')
  }

  return (
    <div className='SignupSelectPage'>
      <LoginPage_title
        title={
          <>
            휴대전화 번호 또는
            <br />
            소셜로 가입해 주세요
          </>
        }
      />

      <input
        type='text'
        placeholder='휴대전화 번호'
        aria-label='휴대전화 번호'
        className='SignupSelect__input'
        value={phone}
        onChange={handlePhoneChange}
        inputMode='tel'
        autoComplete='tel'
      />

      {codeRequested ? (
        <input
          type='text'
          placeholder='인증번호 6자리'
          aria-label='인증번호 6자리'
          className='SignupSelect__input'
          value={verificationCode}
          onChange={handleVerificationCodeChange}
          inputMode='numeric'
          autoComplete='one-time-code'
          maxLength={6}
        />
      ) : (
        <button
          type='button'
          className='SignupSelect__allowButton'
          onClick={handleVerificationRequest}
          disabled={!validPhone(phone)}
        >
          인증번호 받기
        </button>
      )}

      <div className='Divider'>
        <span className='Divider__line'></span>
        <span className='Divider__text'>또는</span>
        <span className='Divider__line'></span>
      </div>

      <div className='SignupSelect__socialLogin'>
        <button
          type='button'
          disabled
          title='현재 지원하지 않습니다'
          className='SignupSelect__socialLoginButton--kakao'
        >
          카카오로 로그인
        </button>

        <button
          type='button'
          disabled
          title='현재 지원하지 않습니다'
          className='SignupSelect__socialLoginButton--google'
        >
          Google로 로그인
        </button>
      </div>

      <div className='SignupSelect__footer'>
        <BottomButton disabled={!canProceed} onClick={handleNext} content='회원가입하기' />

        <button
          type='button'
          className='SignupSelect__gotosignup'
          onClick={() => navigate('/loginselect')}
        >
          KnowOne을 이용해 보셨나요?
        </button>
      </div>
    </div>
  )
}

export default SignupSelect
