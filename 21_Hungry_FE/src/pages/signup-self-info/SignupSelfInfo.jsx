import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupSelfInfo.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";

function SignupSelfInfo() {
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
        className="SignupSelfInfo__input"
      />
      <BottomButton content="다음" />
    </div>
  )
}

export default SignupSelfInfo