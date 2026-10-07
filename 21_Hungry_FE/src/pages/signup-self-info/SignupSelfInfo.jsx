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
      <button className="SignupSelfInfo__nextButton">다음</button>
    </div>
  )
}

export default SignupSelfInfo