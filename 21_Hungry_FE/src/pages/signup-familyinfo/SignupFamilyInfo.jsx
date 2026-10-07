import "./SignupFamilyInfo.css"
import BottomButton from "../../components/bottom-button/BottomButton"
import { useNavigate } from 'react-router-dom' 

function SignupFamilyInfo() {
  const navigate = useNavigate()
  return (
    <main className="SignupFamilyInfoPage">
      <h1 className="SignupFamilyInfoPage__title">
        정보가 올바른지 <br />
        확인해 주세요
      </h1>

      <p className="SignupFamilyInfoPage__SubTitle">
        정보가 올바르다면 <br />
        주돌봄자녀에게 가족 요청을 보내요
      </p>
      <div className="SignupFamilyInfoPage__Container ">
        <div className="SignupFamilyInfoPage__inContainer">
            <p className="label">이름</p>
            <p className="name">부모1</p>
        </div>
        <div className="SignupFamilyInfoPage__inContainer">
            <p className="label">나이</p>
            <p className="age">--세</p>
        </div>
        <div className="SignupFamilyInfoPage__inContainer">
            <p className="label">주돌봄자녀</p>
            <p className="mainuser">자녀1</p>
        </div>
        <div className="SignupFamilyInfoPage__inContainer">
             <p className="label">자녀와의 관계</p>
            <p className="relationship">아버지/어머니</p>
        </div>
      </div>
      <BottomButton onClick={() => navigate('/signupwaiting')} content={"주돌봄자녀에게 요청 보내기"}/>
    </main>
  )
}

export default SignupFamilyInfo