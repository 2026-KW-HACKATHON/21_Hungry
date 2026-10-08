
import "./SignupFamilyInfo.css"
import { useState } from 'react'
import { joinGroup } from '../../api/authApi'
import { messageOf } from '../../api/http'
import BottomButton from "../../components/bottom-button/BottomButton"
import { useNavigate } from 'react-router-dom'

function SignupFamilyInfo() {
  const navigate = useNavigate()
  const lookup = JSON.parse(sessionStorage.getItem('signup_parent_lookup') || '{}')
  const profile = JSON.parse(sessionStorage.getItem('signup_parent_profile') || 'null')
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')

  const send = async () => {
    setBusy(true)
    setError('')
    try {
      const payload = {
        recipientUserId:lookup.recipientUserId
      }
      if (!lookup.parentProfileCompleted && profile) {
        payload.parentProfile = profile
      }

      const result = await joinGroup(lookup.groupId, payload)
      navigate(
        result.nextAction === 'WAITING_APPROVAL'
          ? '/signupwaiting'
          : '/signupservicestart'
      )
    } catch(e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

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
          <p className="name">
            {profile?.name || lookup.displayName || "부모 정보 입력 전"}
          </p>
        </div>

        <div className="SignupFamilyInfoPage__inContainer">
          <p className="label">나이</p>
          <p className="age">
            {profile?.birthYear
              ? `${profile.birthYear}년생`
              : "등록된 정보"}
          </p>
        </div>

        <div className="SignupFamilyInfoPage__inContainer">
          <p className="label">주돌봄자녀</p>
          <p className="mainuser">연결 후 자동 지정</p>
        </div>

        <div className="SignupFamilyInfoPage__inContainer">
          <p className="label">자녀와의 관계</p>
          <p className="relationship">
            {profile?.relation === "FATHER"
              ? "아버지"
              : profile?.relation === "MOTHER"
                ? "어머니"
                : lookup.parentRelation || "미입력"}
          </p>
        </div>
      </div>

      {error && <p role="alert" style={{color:"#d33"}}>{error}</p>}

      <BottomButton
        disabled={busy}
        onClick={send}
        content={busy ? "처리 중..." : "가족 연결하기"}
      />
    </main>
  )
}

export default SignupFamilyInfo
