import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ensureSignupSession } from '../../api/authApi'
import { validPhone, messageOf } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'
import './SignupRole.css'

const ROLE_ROUTES = {
  MainUser: '/signupmainuser',
  SubUser: '/signupsubuser',
}

function SignupRole() {
  const navigate = useNavigate()
  const submitting = useRef(false)

  const [selectedRole, setSelectedRole] = useState(sessionStorage.getItem('signup_role') || null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleNext = async () => {
    if (!selectedRole || submitting.current) return

    const phoneNumber = sessionStorage.getItem('signup_phone')

    if (!validPhone(phoneNumber)) {
      setError('가입할 휴대전화 번호를 다시 입력해 주세요.')
      return
    }

    sessionStorage.setItem('signup_role', selectedRole)

    if (selectedRole !== 'Parent') {
      navigate(ROLE_ROUTES[selectedRole])
      return
    }

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      await ensureSignupSession({
        phoneNumber,
        accountRole: 'PARENT',
      })

      navigate('/signupparent')
    } catch (e) {
      setError(e.message || messageOf(e))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <main className='SignupPage'>
      <LoginPage_title
        title={
          <>
            어떤 분이 이 기기를
            <br />
            사용하시나요?
          </>
        }
      />

      <div className='Signup__SelectUserType'>
        <button
          type='button'
          className='Signup__UserTypeBox--MainUser'
          aria-pressed={selectedRole === 'MainUser'}
          disabled={busy}
          onClick={() => {
            setSelectedRole('MainUser')
            setError('')
          }}
        >
          <span className='Signup__UserTypeBox__Title--MainUser'>주돌봄자녀</span>
          <span className='Signup__UserTypeBox__Description--MainUser'>
            가족 요청을 관리하고 부모님의 건강·생활 기록을
            <br />
            확인, 가족과 공유하는 기능이 포함되어 있어요.
          </span>
        </button>

        <button
          type='button'
          className='Signup__UserTypeBox--SubUser'
          aria-pressed={selectedRole === 'SubUser'}
          disabled={busy}
          onClick={() => {
            setSelectedRole('SubUser')
            setError('')
          }}
        >
          <span className='Signup__UserTypeBox__Title--SubUser'>공동돌봄자녀</span>
          <span className='Signup__UserTypeBox__Description--SubUser'>
            부모님의 건강·생활 기록을 확인,
            <br />
            가족과 공유하는 기능이 포함되어 있어요.
          </span>
        </button>

        <button
          type='button'
          className='Signup__UserTypeBox--Parent'
          aria-pressed={selectedRole === 'Parent'}
          disabled={busy}
          onClick={() => {
            setSelectedRole('Parent')
            setError('')
          }}
        >
          <span className='Signup__UserTypeBox__Title--Parent'>부모</span>
          <span className='Signup__UserTypeBox__Description--Parent'>
            자녀에게 안부와 건강기록을 전하고,
            <br />
            공유 범위를 정하는 기능이 포함되어 있어요
          </span>
        </button>
      </div>

      {error && (
        <p role='alert' style={{ color: '#d33' }}>
          {error}
        </p>
      )}

      <BottomButton
        content={busy ? '처리 중...' : '다음'}
        disabled={!selectedRole || busy}
        onClick={handleNext}
      />
    </main>
  )
}

export default SignupRole
