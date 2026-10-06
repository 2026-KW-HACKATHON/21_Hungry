import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import "./SignupRole.css"

function SignupRole() {
  return (
    <main className="SignupPage">
      <LoginPage_title 
        title={
          <>
            어떤 분이 이 기기를<br />사용하시나요?
          </>
        }
      />
      <div className="Signup__SelectUserType">
        {/* 주돌봄자녀 박스 */}
        <div className='Signup__UserTypeBox--MainUser'>
          <h1 className='Signup__UserTypeBox__Title--MainUser'>주돌봄자녀</h1>
          <p className='Signup__UserTypeBox__Description--MainUser'>가족 요청을 관리하고 부모님의 건강·생활 기록을 <br /> 확인, 가족과 공유하는 기능이 포함되어 있어요.</p>
        </div>
        {/* 공동봄자녀 박스 */}
        <div className='Signup__UserTypeBox--SubUser' >
          <h1 className='Signup__UserTypeBox__Title--SubUser'>공동돌봄자녀</h1>
          <p className='Signup__UserTypeBox__Description--SubUser'>부모님의 건강·생활 기록을 확인,<br />가족과 공유하는 기능이 포함되어 있어요.</p>
        </div>
        {/* 부모 박스 */}
        <div className='Signup__UserTypeBox--Parent'>
          <h1 className='Signup__UserTypeBox__Title--Parent'>부모</h1>
          <p className='Signup__UserTypeBox__Description--Parent'>자녀에게 안부와 건강기록을 전하고,<br />공유 범위를 정하는 기능이 포함되어 있어요</p>
        </div>
      </div>
      <button className="Signup__NextButton">다음</button>
    </main>
  )
}

export default SignupRole;
