import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { joinGroup, moveAfterJoin } from '../../api/authApi'
import { messageOf } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPageTitle from '../../components/loginPage-title/loginPage-title'
import './SignupParentInfo.css'

function SignupParentInfo() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const joinAttempt = useRef(null)

  const [relationship, setRelationship] = useState('father')
  const [name, setName] = useState('')
  const [age, setAge] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const currentYear = Number(
    new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
    }).format(new Date()),
  )

  const isComplete =
    name.trim().length >= 1 &&
    name.trim().length <= 50 &&
    /^\d{4}$/.test(age) &&
    Number(age) >= 1900 &&
    Number(age) <= currentYear

  const next = async () => {
    if (!isComplete || submitting.current) return

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      const lookup = JSON.parse(sessionStorage.getItem('signup_parent_lookup') || 'null')

      if (!lookup?.groupId || !lookup?.recipientUserId) {
        throw new Error('부모님 전화번호 조회부터 다시 진행해 주세요.')
      }

      const parentProfile = {
        relation: relationship === 'father' ? 'FATHER' : 'MOTHER',
        name: name.trim(),
        birthYear: Number(age),
      }

      sessionStorage.setItem('signup_parent_profile', JSON.stringify(parentProfile))

      if (sessionStorage.getItem('signup_role') === 'SubUser') {
        navigate('/signupfamilyinfo')
        return
      }

      const payload = {
        recipientUserId: lookup.recipientUserId,
        parentProfile,
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
    <main className='SignupParentInfoPage'>
      <LoginPageTitle
        title={
          <>
            부모님의 정보를
            <br />
            입력해 주세요
          </>
        }
      />

      <fieldset className='SignupParentInfo__relationship' disabled={busy}>
        <legend className='SignupParentInfo__label'>부모님과의 관계</legend>

        {[
          { value: 'father', label: '아버지' },
          { value: 'mother', label: '어머니' },
        ].map((option) => (
          <label className='SignupParentInfo__option' key={option.value}>
            <input
              type='radio'
              name='parentRelationship'
              value={option.value}
              checked={relationship === option.value}
              onChange={(event) => setRelationship(event.target.value)}
            />
            <span>{option.label}</span>
          </label>
        ))}
      </fieldset>

      <input
        className='SignupParentInfo__input'
        type='text'
        aria-label='부모님 이름'
        placeholder='이름'
        value={name}
        disabled={busy}
        maxLength={50}
        onChange={(event) => setName(event.target.value)}
      />

      <input
        className='SignupParentInfo__input'
        type='text'
        inputMode='numeric'
        aria-label='부모님 출생연도'
        placeholder='출생연도 (예: 1960)'
        value={age}
        disabled={busy}
        maxLength={4}
        onChange={(event) => setAge(event.target.value.replace(/[^0-9]/g, '').slice(0, 4))}
      />

      {error && (
        <p role='alert' style={{ color: '#d33' }}>
          {error}
        </p>
      )}

      <div className='SignupParentInfo__footer' data-complete={isComplete}>
        <BottomButton
          disabled={!isComplete || busy}
          onClick={next}
          content={busy ? '처리 중...' : '다음'}
        />
      </div>
    </main>
  )
}

export default SignupParentInfo
