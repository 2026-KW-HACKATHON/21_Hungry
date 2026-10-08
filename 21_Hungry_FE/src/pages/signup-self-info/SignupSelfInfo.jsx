import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ensureSignupSession,
  readSignupAccount,
  recipientLookup,
  joinGroup,
  moveAfterJoin,
} from '../../api/authApi'
import { validPhone, messageOf } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import './SignupSelfInfo.css'

function SignupSelfInfo() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const joinAttempt = useRef(null)

  const account = readSignupAccount()
  const phoneNumber = sessionStorage.getItem('signup_phone')
  const hasCreatedAccount = account?.phoneNumber === phoneNumber && account.accountRole === 'CHILD'

  const [name, setName] = useState(
    hasCreatedAccount ? account.displayName : sessionStorage.getItem('signup_self_name') || '',
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const canProceed = name.trim().length >= 1 && name.trim().length <= 50

  const next = async () => {
    if (!canProceed || submitting.current) return

    const parentPhone = sessionStorage.getItem('signup_parent_phone')
    const selectedRole = sessionStorage.getItem('signup_role')

    if (
      !validPhone(phoneNumber) ||
      !validPhone(parentPhone) ||
      !['MainUser', 'SubUser'].includes(selectedRole)
    ) {
      setError('전화번호와 가입 역할을 다시 확인해 주세요.')
      return
    }

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      sessionStorage.setItem('signup_self_name', name.trim())

      await ensureSignupSession({
        phoneNumber,
        accountRole: 'CHILD',
        displayName: name.trim(),
      })

      const lookup = await recipientLookup(parentPhone)

      sessionStorage.setItem('signup_parent_lookup', JSON.stringify(lookup))

      if (!lookup.parentProfileCompleted) {
        navigate('/signupparentinfo')
        return
      }

      if (selectedRole === 'SubUser') {
        navigate('/signupfamilyinfo')
        return
      }

      const payload = {
        recipientUserId: lookup.recipientUserId,
      }
      const operation = JSON.stringify({
        groupId: lookup.groupId,
        payload,
      })

      if (joinAttempt.current?.operation !== operation) {
        joinAttempt.current = {
          operation,
          key: crypto.randomUUID(),
        }
      }

      const result = await joinGroup(lookup.groupId, payload, joinAttempt.current.key)

      await moveAfterJoin(navigate, result)
    } catch (e) {
      setError(e.message || messageOf(e))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <div className='SignupSelfInfoPage'>
      <LoginPage_title
        title={
          <>
            시작하기 위해
            <br />
            본인의 정보를 입력해주세요
          </>
        }
      />

      <input
        type='text'
        placeholder='이름'
        aria-label='이름'
        autoComplete='name'
        className='SignupSelfInfo__input'
        value={name}
        disabled={busy || hasCreatedAccount}
        maxLength={50}
        onChange={(event) => {
          setName(event.target.value)
          setError('')
        }}
      />

      {error && (
        <p role='alert' style={{ color: '#d33' }}>
          {error}
        </p>
      )}

      <BottomButton
        onClick={next}
        content={busy ? '처리 중...' : '다음'}
        disabled={!canProceed || busy}
      />
    </div>
  )
}

export default SignupSelfInfo
