import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Icon } from '../../components/icon/Icon'
import BottomButton from '../../components/bottom-button/BottomButton'
import './RecordWritePage.css'

function RecordWritePage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const [memo, setMemo] = useState(state?.memo ?? '')
  const [message, setMessage] = useState('')

  function finishRecord() {
    if (!memo.trim()) {
      setMessage('진료 메모를 입력해 주세요.')
      return
    }
  }

  return (
    <main className="recordWritePage">
      <header className="recordWritePage__header">
        <button type="button" className="recordWritePage__back" aria-label="진료 기록으로 돌아가기" onClick={() => navigate('/record')}>
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>직접 기록하기</h1>
      </header>
      <div className="recordWritePage__content">
        <div className="recordWritePage__memo">
          <label htmlFor="visit-memo">진료 메모</label>
          <textarea
            id="visit-memo"
            placeholder="이곳에 진료 내용을 적어 보세요"
            value={memo}
            onChange={(event) => {
              setMemo(event.target.value)
              setMessage('')
            }}
            aria-describedby="memo-sharing memo-status"
          />
        </div>
        <p id="memo-sharing" className="recordWritePage__notice">이 기록은 가족과 공유할 수 있어요</p>
        <p id="memo-status" className="recordWritePage__message" role="status">{message}</p>
      </div>
      <BottomButton content="기록 마치기" onClick={finishRecord} />
    </main>
  )
}

export default RecordWritePage
