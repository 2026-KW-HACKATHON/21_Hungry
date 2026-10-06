import "./SiginSelect.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
 
function SignupSelect() {
  return (
    <div className="SignupSelectPage">
      <LoginPage_title title={
        <>
            "휴대전화 번호 또는 <br/> 소셜로 가입해 주세요"
        </> 
      }/>
      <input type="text" placeholder="휴대전화 번호" className="SignupSelect__input"/>
      <button className="SignupSelect__allowButton">인증번호 받기</button>
      <div className="Divider">
        <span className="Divider__line"></span>
        <span className="Divider__text">또는</span>
        <span className="Divider__line"></span>
      </div>
      <div className="SignupSelect__socialLogin">
        <button className="SignupSelect__socialLoginButton--kakao">
          카카오로 로그인
        </button>
        <button className="SignupSelect__socialLoginButton--google">
          Google로 로그인
        </button>
      </div>
      <button className="SignupSelect__loginButton">회원가입하기</button>
      <button className="SignupSelect__gotosignup">KnowOne을 이용해 보셨나요?</button>
    </div>
  )
}

export default SignupSelect