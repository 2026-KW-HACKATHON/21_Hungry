import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'
import './RecordAnalysisPage.css'

// 시안용 가족 목록. 가족 조회 API 연결 시 실제 구성원으로 교체합니다.
const previewMembers = [
  { id: 'child2', name: '자녀2', role: '공동돌봄자녀' },
  { id: 'child3', name: '자녀3', role: '공동돌봄자녀' },
]
const companionLabels = { self: '본인', other: '다른 자녀', none: '동행 없음' }

function RecordAnalysisPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const record = state ?? {}
  const [sharedWith, setSharedWith] = useState(record.sharedWith ?? previewMembers.map((member) => member.id))
  const [message, setMessage] = useState('')

  return (
    <main className="recordAnalysisPage">
      <header className="recordAnalysisPage__header">
        <button
          type="button"
          className="recordAnalysisPage__back"
          aria-label="직접 기록하기로 돌아가기"
          onClick={() => navigate('/record/write', { state: { ...record, sharedWith } })}
        >
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>진료 기록 분석</h1>
      </header>
      <div className="recordAnalysisPage__content">
        <dl className="recordAnalysisPage__details">
          <div><dt>병원</dt><dd>{record.hospital || '-'}</dd></div>
          <div><dt>진료 과목</dt><dd>{record.department || '-'}</dd></div>
          <div><dt>동행한 자녀</dt><dd>{companionLabels[record.companion] || '-'}</dd></div>
        </dl>
        <section className="recordAnalysisPage__memo" aria-labelledby="analysis-memo-title">
          <h2 id="analysis-memo-title">진료 메모</h2>
          <p>{record.memo || '-'}</p>
        </section>
        <section className="recordAnalysisPage__sharing" aria-labelledby="analysis-sharing-title">
          <h2 id="analysis-sharing-title">이 문서를 공유할 가족 구성원</h2>
          {previewMembers.map((member) => (
            <label className="recordAnalysisPage__member" key={member.id}>
              <input
                type="checkbox"
                checked={sharedWith.includes(member.id)}
                onChange={(event) => setSharedWith((previous) => event.target.checked
                  ? [...previous, member.id]
                  : previous.filter((id) => id !== member.id))}
              />
              <Icon name={sharedWith.includes(member.id) ? 'checkbox-checked' : 'checkbox-default'} width={26} height={28} aria-hidden="true" />
              <span>{member.name}<span className="recordAnalysisPage__dot">·</span>{member.role}</span>
            </label>
          ))}
        </section>
        <p className="recordAnalysisPage__notice">저장된 기록은 의료 문서 보관함 탭에서 조회할 수 있어요</p>
        <p className="recordAnalysisPage__message" role="status">{message}</p>
      </div>
      <BottomButton content="저장하기" onClick={() => 0} />
    </main>
  )
}

export default RecordAnalysisPage
