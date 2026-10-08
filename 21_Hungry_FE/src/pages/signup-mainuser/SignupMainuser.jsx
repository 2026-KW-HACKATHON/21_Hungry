import { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'

import axiosInstance from '../../api/axiosInstance'

import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPage_title from '../../components/loginPage-title/loginPage-title'

import './SignupMainuser.css'

function SignupMainuser() {
  const navigate = useNavigate()
  const location = useLocation()

  const phoneNumber = location.state?.phoneNumber

  const [displayName, setDisplayName] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSignup = async () => {
    if (loading) return

    if (!phoneNumber) {
      alert('전화번호를 먼저 입력해주세요.')
      navigate('/signupselect')
      return
    }

    const name = displayName.trim()

    if (name.length < 1 || name.length > 50) {
      alert('이름을 1~50자로 입력해주세요.')
      return
    }

    setLoading(true)

    try {
      // 1. 회원가입 API (A05)
      const signupResponse = await axiosInstance.post(
        '/auth/signup',
        {
          phoneNumber,
          accountRole: 'CHILD',
          displayName: name,
        }
      )

      console.log('회원가입 성공:', signupResponse.data.data)

      // 2. 로그인 API (A06)
      const loginResponse = await axiosInstance.post(
        '/auth/login',
        {
          phoneNumber,
        }
      )

      const accessToken = loginResponse.data.data.accessToken

      sessionStorage.setItem('accessToken', accessToken)

      // 3. 내 정보 조회 API (A03)
      const meResponse = await axiosInstance.get('/me')

      const me = meResponse.data.data

      console.log('사용자 정보:', me)

      // 4. 가입 상태에 따른 이동
      if (me.onboardingState === 'READY') {
        navigate('/home', { replace: true })
        return
      }

      if (me.onboardingState === 'WAITING_APPROVAL') {
        navigate('/signupwaiting', { replace: true })
        return
      }

      if (
        me.onboardingState === 'NO_GROUP' ||
        me.onboardingState === 'PARENT_PROFILE_PENDING'
      ) {
        navigate('/connectparent', {
          replace: true,
          state: {
            phoneNumber,
            selectedRole: 'MainUser',
          },
        })
        return
      }

      alert('가입 상태를 확인할 수 없습니다.')

    } catch (error) {
      console.error('회원가입 오류:', error)

      const errorCode = error.response?.data?.error?.code
      const errorMessage = error.response?.data?.error?.message

      if (errorCode === 'PHONE_ALREADY_REGISTERED') {
        alert('이미 가입된 전화번호입니다. 로그인해주세요.')
        navigate('/loginselect')
      } else {
        alert(errorMessage || '회원가입 중 오류가 발생했습니다.')
      }

    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="SignupPage">
      <LoginPage_title
        title={
          <>
            주돌봄자녀님의<br />
            이름을 입력해주세요
          </>
        }
      />

      <input
        type="text"
        placeholder="이름"
        value={displayName}
        maxLength={50}
        onChange={(e) => setDisplayName(e.target.value)}
      />

      <BottomButton
        content={loading ? '회원가입 중...' : '다음'}
        disabled={loading || !displayName.trim()}
        onClick={handleSignup}
      />
    </main>
  )
}

export default SignupMainuser