import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { validPhone, normalizePhone } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import { Icon } from '../../components/icon/Icon'
import './SignupMainuser.css'

function SignupMainuser() {
  const navigate = useNavigate()

  const [phone, setPhone] = useState(sessionStorage.getItem('signup_parent_phone') || '')
  const [purposeConfirmed, setPurposeConfirmed] = useState(false)
  const [privacyAgreed, setPrivacyAgreed] = useState(false)

  const canProceed = validPhone(phone) && purposeConfirmed && privacyAgreed

  const next = () => {
    if (!canProceed) return

    sessionStorage.setItem('signup_role', 'MainUser')
    sessionStorage.setItem('signup_parent_phone', normalizePhone(phone))
    sessionStorage.removeItem('signup_parent_lookup')
    sessionStorage.removeItem('signup_parent_profile')

    navigate('/signupselfinfo')
  }

  return (
    <div className='SignupMainuserPage'>
      <LoginPage_title
        title={
          <>
            부모님의
            <br />
            휴대전화 번호를 입력하세요
          </>
        }
      />

      <input
        type='text'
        placeholder='휴대전화 번호'
        aria-label='부모님 휴대전화 번호'
        className='SignupMainuser__input'
        value={phone}
        onChange={(event) => setPhone(event.target.value)}
        inputMode='tel'
      />

      <div className='SignupMainuser__checkbox'>
        <p className='SignupMainuser__checkbox--Label'>연결 전 함께 확인해요</p>

        <label className='SignupMainuser__checkbox--Item'>
          <input
            type='checkbox'
            className='SignupMainuser__checkbox--Input'
            checked={purposeConfirmed}
            onChange={(event) => setPurposeConfirmed(event.target.checked)}
          />
          <Icon
            className='SignupMainuser__checkbox--Icon'
            aria-hidden='true'
            name={purposeConfirmed ? 'checkbox-checked' : 'checkbox-default'}
            width={26}
            height={26}
          />
          <span className='SignupMainuser__checkbox--Text'>부모님께 연결 목적을 설명했어요</span>
        </label>

        <label className='SignupMainuser__checkbox--Item'>
          <input
            type='checkbox'
            className='SignupMainuser__checkbox--Input'
            checked={privacyAgreed}
            onChange={(event) => setPrivacyAgreed(event.target.checked)}
          />
          <Icon
            className='SignupMainuser__checkbox--Icon'
            aria-hidden='true'
            name={privacyAgreed ? 'checkbox-checked' : 'checkbox-default'}
            width={26}
            height={26}
          />
          <span className='SignupMainuser__checkbox--Text'>필수 개인정보 수집·이용에 동의해요</span>
        </label>
      </div>

      <BottomButton onClick={next} disabled={!canProceed} content='다음' />
    </div>
  )
}

export default SignupMainuser
