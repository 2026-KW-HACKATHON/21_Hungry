
import BottomButton from '../../components/bottom-button/BottomButton'
import "./SiginSelect.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { useState } from 'react'
import { normalizePhone, validPhone } from '../../api/http'
import { useNavigate } from 'react-router-dom'

function SignupSelect() {
  const navigate = useNavigate()
  const [phone,setPhone] = useState('')

  return (
    <div className="SignupSelectPage">
      <LoginPage_title title={
        <>
          "휴대전화 번호 또는 <br/> 소셜로 가입해 주세요"
        </>
      }/>

      <input
        type="text"
        placeholder="휴대전화 번호"
        className="SignupSelect__input"
        value={phone}
        onChange={e=>setPhone(e.target.value)}
        inputMode="tel"
        autoComplete="tel"
      />

      <p style={{fontSize:13,color:"#777"}}>
        시연용 서비스로 인증번호를 발송하지 않습니다.
      </p>

      <div className="Divider">
        <span className="Divider__line"></span>
        <span className="Divider__text">또는</span>
        <span className="Divider__line"></span>
      </div>

      <div className="SignupSelect__socialLogin">
        <button
          disabled
          title="현재 지원하지 않습니다"
          className="SignupSelect__socialLoginButton--kakao"
        >
          카카오로 로그인
        </button>
        <button
          disabled
          title="현재 지원하지 않습니다"
          className="SignupSelect__socialLoginButton--google"
        >
          Google로 로그인
        </button>
      </div>

      <div className="SignupSelect__footer">
        <BottomButton
          disabled={!validPhone(phone)}
          onClick={() => {
            sessionStorage.setItem('signup_phone',normalizePhone(phone))
            navigate('/signuprole')
          }}
          content="회원가입하기"
        />

        <button
          className="SignupSelect__gotosignup"
          onClick={() => navigate('/loginselect')}
        >
          KnowOne을 이용해 보셨나요?
        </button>
      </div>
    </div>
  )
}

export default SignupSelect
