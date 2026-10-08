import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { me } from '../../api/authApi'
import { getAccessToken, messageOf } from '../../api/http'
import './SignupParent.css'

function SignupParent() {
  const navigate = useNavigate()
  const [error, setError] = useState('')

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()
    let timer

    const poll = async () => {
      try {
        const user = await me({
          signal: controller.signal,
        })

        if (controller.signal.aborted) return

        if (user.accountRole !== 'PARENT') {
          setError('부모 계정으로 진행해 주세요.')
          return
        }

        if (user.onboardingState === 'READY') {
          navigate('/signupparentdone', { replace: true })
          return
        }

        setError('')
      } catch (e) {
        if (controller.signal.aborted) return

        setError(e.message || messageOf(e))

        if (e.response?.status === 401) return
      }

      if (!controller.signal.aborted) {
        timer = window.setTimeout(poll, 5000)
      }
    }

    poll()

    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [navigate])

  return (
    <main className='SignupParentPage'>
      <h1 className='SignupParentPage__title'>
        자녀의 휴대전화에서
        <br />
        계속 진행해 주세요
      </h1>

      <p className='SignupParentPage__SubTitle'>
        자녀의 휴대전화에서 설정이 완료되면
        <br />
        Knowon을 시작할 수 있어요
      </p>

      {error && (
        <p role='alert' style={{ color: '#d33' }}>
          {error}
        </p>
      )}
    </main>
  )
}

export default SignupParent
