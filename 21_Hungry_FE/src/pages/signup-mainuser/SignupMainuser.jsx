import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupMainuser.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { Icon } from "../../components/icon/Icon";

function SignupMainuser() {
  return (
    <div className="SignupMainuserPage">
      <LoginPage_title title={
        <>
            부모님의 <br/>휴대전화 번호를 입력하세요
        </> 
      }/>
      <input type="text" placeholder="휴대전화 번호" className="SignupMainuser__input"/>
      <button className="SignupMainuser__allowButton">인증번호 받기</button>
      <div className="SignupMainuser__checkbox">
        <p className="SignupMainuser__checkbox--Label">연결 전 함께 확인해요</p>
        <div className="SignupMainuser__checkbox--Item">
          <Icon className="SignupMainuser__checkbox--Icon" name='checkbox-default' width={26} height={26} />
          <p className="SignupMainuser__checkbox--Text">부모님께 연결 목적을 설명했어요</p>
        </div>
        <div className="SignupMainuser__checkbox--Item">
          <Icon className="SignupMainuser__checkbox--Icon" name='checkbox-default' width={26} height={26} />
          <p className="SignupMainuser__checkbox--Text">필수 개인정보 수집·이용에 동의해요</p>
        </div>
      </div>
      <BottomButton content="다음" />
    </div>
  )
}

export default SignupMainuser