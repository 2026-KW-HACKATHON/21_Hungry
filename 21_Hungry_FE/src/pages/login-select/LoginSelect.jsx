import BottomButton from '../../components/bottom-button/BottomButton'
import "./LoginSelect.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { useNavigate } from 'react-router-dom' 

function LoginSelect() {
  const navigate = useNavigate()
  return (
    <div className="LoginSelectPage">
      <LoginPage_title title={
        <>
            가입한 휴대전화 번호로 <br/> 로그인해 주세요
        </> 
      }/>
      <input type="text" placeholder="휴대전화 번호" className="LoginSelect__input"/>
      <button className="LoginSelect__allowButton">인증번호 받기</button>
      <div className="Divider">
        <span className="Divider__line"></span>
        <span className="Divider__text">또는</span>
        <span className="Divider__line"></span>
      </div>
      <div className="LoginSelect__socialLogin">
        <button className="LoginSelect__socialLoginButton--kakao">
          카카오로 로그인
        </button>
        <button className="LoginSelect__socialLoginButton--google">
          Google로 로그인
        </button>
      </div>
      <div className="LoginSelect__footer">
        <BottomButton content="로그인하기" />
        <button className="LoginSelect__gotosignup" onClick={() => navigate('/signupselect')}>KnowOne이 처음이신가요?</button>
      </div>
    </div>
  )
}

export default LoginSelect