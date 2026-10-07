import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'
import './RecordAudioAnalysisPage.css'

// 시안용 가족 목록. 가족 조회 API 연결 시 실제 구성원으로 교체합니다.
const previewMembers = [
  { id: 'child2', name: '자녀2', role: '공동돌봄자녀' },
  { id: 'child3', name: '자녀3', role: '공동돌봄자녀' },
]
const companionLabels = { self: '본인', other: '다른 자녀', none: '동행 없음' }

function RecordAudioAnalysisPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const record = state ?? {}
  const seconds = Math.max(0, Math.floor(Number(record.seconds) || 0))
  const duration = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value) => String(value).padStart(2, '0')).join(':')
  const [sharedWith, setSharedWith] = useState(record.sharedWith ?? previewMembers.map((member) => member.id))
  const [message, setMessage] = useState('')

  return (
    <main className="recordAudioAnalysisPage">
      <header className="recordAudioAnalysisPage__header">
        <button
          type="button"
          className="recordAudioAnalysisPage__back"
          aria-label="진료 녹음하기로 돌아가기"
          onClick={() => navigate('/record/audio', { state: { ...record, sharedWith } })}
        >
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>진료 기록 분석</h1>
      </header>
      <div className="recordAudioAnalysisPage__content">
        <dl className="recordAudioAnalysisPage__details">
          <div><dt>병원</dt><dd>{record.hospital || '-'}</dd></div>
          <div><dt>진료 과목</dt><dd>{record.department || '-'}</dd></div>
          <div><dt>동행한 자녀</dt><dd>{companionLabels[record.companion] || '-'}</dd></div>
        </dl>
        <div className="recordAudioAnalysisPage__duration" aria-label="녹음 시간">{duration}</div>
        <section className="recordAudioAnalysisPage__summary" aria-labelledby="audio-summary-title">
          <h2 id="audio-summary-title">쉬운 요약</h2>
          <p>{record.summary || '-'}</p>
        </section>
        <section className="recordAudioAnalysisPage__highlights" aria-labelledby="audio-highlights-title">
          <h2 id="audio-highlights-title">진료 핵심</h2>
          <ol>
            {Array.from({ length: 6 }, (_, index) => (
              <li key={index}><span aria-hidden="true">{index + 1}</span><p>{record.highlights?.[index] || '-'}</p></li>
            ))}
          </ol>
        </section>
        <section className="recordAudioAnalysisPage__memo" aria-labelledby="audio-transcript-title">
          <h2 id="audio-transcript-title">녹음 원문</h2>
          <p>{record.transcript || '-'}</p>
        </section>
        <p className="recordAudioAnalysisPage__notice recordAudioAnalysisPage__legal">
          내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
        </p>
        <section className="recordAudioAnalysisPage__sharing" aria-labelledby="analysis-sharing-title">
          <h2 id="analysis-sharing-title">이 문서를 공유할 가족 구성원</h2>
          {previewMembers.map((member) => (
            <label className="recordAudioAnalysisPage__member" key={member.id}>
              <input
                type="checkbox"
                checked={sharedWith.includes(member.id)}
                onChange={(event) => setSharedWith((previous) => event.target.checked
                  ? [...previous, member.id]
                  : previous.filter((id) => id !== member.id))}
              />
              <Icon name={sharedWith.includes(member.id) ? 'checkbox-checked' : 'checkbox-default'} width={26} height={28} aria-hidden="true" />
              <span>{member.name}<span className="recordAudioAnalysisPage__dot">·</span>{member.role}</span>
            </label>
          ))}
        </section>
        <p className="recordAudioAnalysisPage__notice">저장된 기록은 의료 문서 보관함 탭에서 조회할 수 있어요</p>
        <p className="recordAudioAnalysisPage__message" role="status">{message}</p>
      </div>
      <BottomButton content="저장하기" onClick={() => setMessage('기록 저장 기능은 준비 중이에요.')} />
    </main>
  )
}

export default RecordAudioAnalysisPage
