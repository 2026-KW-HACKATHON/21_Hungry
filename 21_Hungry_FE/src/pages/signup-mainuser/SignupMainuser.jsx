
import { useState } from 'react'
import BottomButton from '../../components/bottom-button/BottomButton'
import "./SignupMainuser.css";
import LoginPage_title from "../../components/loginPage-title/loginPage-title";
import { Icon } from "../../components/icon/Icon";
import { validPhone, normalizePhone, messageOf } from '../../api/http'
import { useNavigate } from 'react-router-dom'

function SignupMainuser() {
  const [purposeConfirmed, setPurposeConfirmed] = useState(false)
  const [privacyAgreed, setPrivacyAgreed] = useState(false)
  const canProceed = purposeConfirmed && privacyAgreed
  const navigate = useNavigate()
  const [phone,setPhone] = useState('')
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')

  const next = async () => {
    setBusy(true)
    setError('')
    try {
      // 부모 조회는 자녀 계정 가입·로그인 직후 실행합니다.
      sessionStorage.setItem('signup_parent_phone', normalizePhone(phone))
      navigate('/signupselfinfo')
    } catch(e) {
      setError(messageOf(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="SignupMainuserPage">
      <LoginPage_title title={
        <>
          부모님의 <br/>휴대전화 번호를 입력하세요
        </>
      }/>

      <input
        type="text"
        placeholder="휴대전화 번호"
        className="SignupMainuser__input"
        value={phone}
        onChange={e=>setPhone(e.target.value)}
      />

      <p style={{fontSize:13,color:"#777"}}>
        부모님 전화번호는 가입 후 조회합니다.
      </p>

      <div className="SignupMainuser__checkbox">
        <p className="SignupMainuser__checkbox--Label">
          연결 전 함께 확인해요
        </p>

        <label className="SignupMainuser__checkbox--Item">
          <input
            type="checkbox"
            className="SignupMainuser__checkbox--Input"
            checked={purposeConfirmed}
            onChange={(event) => setPurposeConfirmed(event.target.checked)}
          />
          <Icon
            className="SignupMainuser__checkbox--Icon"
            aria-hidden="true"
            name={purposeConfirmed ? 'checkbox-checked' : 'checkbox-default'}
            width={26}
            height={26}
          />
          <span className="SignupMainuser__checkbox--Text">
            부모님께 연결 목적을 설명했어요
          </span>
        </label>

        <label className="SignupMainuser__checkbox--Item">
          <input
            type="checkbox"
            className="SignupMainuser__checkbox--Input"
            checked={privacyAgreed}
            onChange={(event) => setPrivacyAgreed(event.target.checked)}
          />
          <Icon
            className="SignupMainuser__checkbox--Icon"
            aria-hidden="true"
            name={privacyAgreed ? 'checkbox-checked' : 'checkbox-default'}
            width={26}
            height={26}
          />
          <span className="SignupMainuser__checkbox--Text">
            필수 개인정보 수집·이용에 동의해요
          </span>
        </label>
      </div>

      {error && <p role="alert" style={{color:"#d33"}}>{error}</p>}

      <BottomButton
        onClick={next}
        disabled={!canProceed || !validPhone(phone) || busy}
        content="다음"
      />
    </div>
  )
}

export default SignupMainuser
