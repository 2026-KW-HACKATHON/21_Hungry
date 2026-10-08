import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { joinGroup, moveAfterJoin } from '../../api/authApi'
import { messageOf } from '../../api/http'
import BottomButton from '../../components/bottom-button/BottomButton'
import './SignupFamilyInfo.css'

function readStoredJson(key) {
  try {
    return JSON.parse(sessionStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

function SignupFamilyInfo() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const joinAttempt = useRef(null)

  const lookup = readStoredJson('signup_parent_lookup')
  const profile = readStoredJson('signup_parent_profile')

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const relation = profile?.relation || lookup?.parentRelation
  const relationLabel = relation === 'FATHER' ? '아버지' : relation === 'MOTHER' ? '어머니' : '-'

  const send = async () => {
    if (submitting.current) return

    submitting.current = true
    setBusy(true)
    setError('')

    try {
      if (!lookup?.groupId || !lookup?.recipientUserId) {
        throw new Error('부모님 전화번호 조회부터 다시 진행해 주세요.')
      }

      const payload = {
        recipientUserId: lookup.recipientUserId,
      }

      if (!lookup.parentProfileCompleted) {
        if (!profile) {
          throw new Error('부모님 정보를 먼저 입력해 주세요.')
        }

        payload.parentProfile = profile
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
    <main className='SignupFamilyInfoPage'>
      <h1 className='SignupFamilyInfoPage__title'>
        정보가 올바른지
        <br />
        확인해 주세요
      </h1>

      <p className='SignupFamilyInfoPage__SubTitle'>
        정보가 올바르다면
        <br />
        주돌봄자녀에게 가족 요청을 보내요
      </p>

      <div className='SignupFamilyInfoPage__Container'>
        <div className='SignupFamilyInfoPage__inContainer'>
          <p className='label'>이름</p>
          <p className='name'>{profile?.name || lookup?.displayName || '-'}</p>
        </div>

        <div className='SignupFamilyInfoPage__inContainer'>
          <p className='label'>나이</p>
          <p className='age'>{profile?.birthYear ? `${profile.birthYear}년생` : '-'}</p>
        </div>

        <div className='SignupFamilyInfoPage__inContainer'>
          <p className='label'>주돌봄자녀</p>
          <p className='mainuser'>-</p>
        </div>

        <div className='SignupFamilyInfoPage__inContainer'>
          <p className='label'>자녀와의 관계</p>
          <p className='relationship'>{relationLabel}</p>
        </div>
      </div>

      {error && (
        <p role='alert' style={{ color: '#d33' }}>
          {error}
        </p>
      )}

      <BottomButton
        disabled={!lookup?.groupId || busy}
        onClick={send}
        content={busy ? '처리 중...' : '가족 연결하기'}
      />
    </main>
  )
}

export default SignupFamilyInfo
