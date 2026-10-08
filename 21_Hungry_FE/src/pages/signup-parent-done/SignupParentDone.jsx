import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { finishSignup } from '../../api/authApi'
import BottomButton from '../../components/bottom-button/BottomButton'
import './SignupParentDone.css'

function SignupParentDone() {
  const navigate = useNavigate()
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)

  const next = async () => {
    if (submitting.current) return

    submitting.current = true
    setBusy(true)

    try {
      await finishSignup(navigate)
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <main className='SignupParentPageDone'>
      <h1 className='SignupParentPageDone__title'>모든설정을 완료했어요</h1>

      <BottomButton content={busy ? '처리 중...' : '시작하기'} disabled={busy} onClick={next} />
    </main>
  )
}

export default SignupParentDone
