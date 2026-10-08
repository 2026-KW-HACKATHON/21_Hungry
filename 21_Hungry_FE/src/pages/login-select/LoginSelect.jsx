import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import { loginByPhone } from '../../api/authApi'
import './LoginSelect.css'

function LoginSelect() {
  const navigate = useNavigate()
  const [phone, setPhone] = useState('')
  const [canLogin, setCanLogin] = useState(false)
  const [message, setMessage] = useState('')
  const [isLoggingIn, setIsLoggingIn] = useState(false)
  const requestRef = useRef(null)
  useEffect(() => () => requestRef.current?.abort(), [])

  async function handleLogin(event) {
    event?.preventDefault()
    if (requestRef.current || !canLogin) return
    const controller = new AbortController()
    requestRef.current = controller
    setIsLoggingIn(true)
    setMessage('')
    try {
      const result = await loginByPhone(phone, controller.signal)
      if (controller.signal.aborted) return
      sessionStorage.setItem('accessToken', result.accessToken)
      sessionStorage.removeItem('groupId')
      if (result.profile.membership?.status === 'ACTIVE') {
        sessionStorage.setItem('groupId', result.profile.membership.groupId)
      }
      navigate(result.destination, { replace: true })
    } catch (error) {
      if (!controller.signal.aborted) {
        setMessage(error instanceof TypeError
          ? '서버에 연결할 수 없어요. 인터넷 연결과 서버 상태를 확인해 주세요.'
          : error.message || '로그인에 실패했어요.')
      }
    } finally {
      if (!controller.signal.aborted) setIsLoggingIn(false)
      if (requestRef.current === controller) requestRef.current = null
    }
  }

  return (
    <form className="LoginSelectPage" onSubmit={handleLogin} aria-busy={isLoggingIn}>
      <LoginPage_title title={<>가입한 휴대전화 번호로 <br />로그인해 주세요</>} />
      <input type="tel" inputMode="tel" autoComplete="tel" aria-label="휴대전화 번호"
        placeholder="휴대전화 번호" className="LoginSelect__input"
        value={phone} disabled={isLoggingIn}
        onChange={(event) => { setPhone(event.target.value); setCanLogin(false); setMessage('') }}
        aria-describedby="login-status" />
      <button type="button" className="LoginSelect__allowButton" disabled={isLoggingIn}
        onClick={() => {
          if (!/^010[0-9]{8}$/.test(phone.replace(/[-\s]/g, ''))) {
            setMessage('010으로 시작하는 휴대전화 번호 11자리를 입력해 주세요.')
            return
          }
          setMessage('')
          setCanLogin(true)
        }}>인증번호 받기</button>
      <p id="login-status" className="LoginSelect__message" role="status">{message}</p>
      <div className="Divider">
        <span className="Divider__line" />
        <span className="Divider__text">또는</span>
        <span className="Divider__line" />
      </div>
      <div className="LoginSelect__socialLogin">
        <button type="button" disabled className="LoginSelect__socialLoginButton--kakao">카카오로 로그인</button>
        <button type="button" disabled className="LoginSelect__socialLoginButton--google">Google로 로그인</button>
      </div>
      <div className="LoginSelect__footer">
        <BottomButton content={isLoggingIn ? '로그인 중...' : '로그인하기'} onClick={handleLogin} disabled={isLoggingIn || !canLogin} />
        <button type="button" className="LoginSelect__gotosignup" disabled={isLoggingIn}
          onClick={() => navigate('/signupselect')}>KnowOne이 처음이신가요?</button>
      </div>
    </form>
  )
}

export default LoginSelect
