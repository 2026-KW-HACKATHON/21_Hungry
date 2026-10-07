import { useNavigate } from 'react-router-dom'
import { useState } from 'react'
import TitleHeader from '../../components/title-header/TitleHeader'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'
import './RecordPage.css'

const recordingChecks = [
  '녹음 시작 전 한 번만 권한을 요청해요',
  '권한을 거부하면 녹음이 시작되지 않아요',
  '설정에서 권한을 다시 허용할 수 있어요',
  '권장 녹음 상한은 20분임을 확인했어요',
  '의료진의 동의를 받았어요',
]

function RecordPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState('audio')
  const [checked, setChecked] = useState([])
  const [message, setMessage] = useState('')
  const [manualConsent, setManualConsent] = useState(false)

  function selectMode(value) {
    setMode(value)
    setMessage('')
  }

  function startRecord(event) {
    event.preventDefault()
    if (mode === 'audio' && checked.length !== recordingChecks.length) {
      setMessage('녹음 및 기록 전 확인 사항을 모두 체크해 주세요.')
      return
    }
    if (mode === 'text') {
      if (!manualConsent) {
        setMessage('의료진의 동의 여부를 확인해 주세요.')
        return
      }
      navigate('/record/write', { state: Object.fromEntries(new FormData(event.currentTarget)) })
      return
    }
    if (mode === 'audio') {
      navigate('/record/audio', { state: Object.fromEntries(new FormData(event.currentTarget)) })
    }
  }

  return (
    <main className="recordPage">
      <TitleHeader
        content="진료 기록"
        subcontent={<>기본 정보만 확인하면 AI가 대화를 기록하고<br />이해하기 쉽게 정리해드려요</>}
      />
      <form className="recordPage__form" onSubmit={startRecord}>
        <div className="recordPage__fields">
          <input className="recordPage__input" name="hospital" aria-label="병원" placeholder="병원" required />
          <input className="recordPage__input" name="department" aria-label="진료 과목" placeholder="진료 과목" required />
          <div className="recordPage__selectWrap">
            <select className="recordPage__input recordPage__select" name="companion" aria-label="동행한 자녀" defaultValue="">
              <option value="" disabled>동행한 자녀</option>
              <option value="self">본인</option>
              <option value="other">다른 자녀</option>
              <option value="none">동행 없음</option>
            </select>
          </div>
        </div>
        <div className="recordPage__modes" role="group" aria-label="기록 방식">
          <button type="button" aria-pressed={mode === 'audio'} onClick={() => selectMode('audio')}>진료 녹음하기</button>
          <button type="button" aria-pressed={mode === 'text'} onClick={() => selectMode('text')}>직접 기록하기</button>
        </div>
        {mode === 'audio' ? (
          <section className="recordPage__checklist" aria-labelledby="record-check-title">
            <h2 id="record-check-title">녹음 및 기록 전 확인해 주세요</h2>
            {recordingChecks.map((label, index) => (
              <label className="recordPage__check" key={label}>
                <input
                  type="checkbox"
                  checked={checked.includes(index)}
                  onChange={(event) => setChecked((previous) => event.target.checked
                    ? [...previous, index]
                    : previous.filter((item) => item !== index))}
                />
                <Icon name={checked.includes(index) ? 'checkbox-checked' : 'checkbox-default'} width={26} height={28} aria-hidden="true" />
                <span>{label}</span>
              </label>
            ))}
          </section>
        ) : (
          <section className="recordPage__checklist recordPage__checklist--manual" aria-labelledby="manual-check-title">
            <h2 id="manual-check-title">기록 전 확인해 주세요</h2>
            <label className="recordPage__check">
              <input type="checkbox" checked={manualConsent} onChange={(event) => setManualConsent(event.target.checked)} />
              <Icon name={manualConsent ? 'checkbox-checked' : 'checkbox-default'} width={26} height={26} aria-hidden="true" />
              <span>의료진의 동의를 받았어요</span>
            </label>
          </section>
        )}
        {mode === 'audio' && (
          <p className="recordPage__notice">
            내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
          </p>
        )}
        <div className="recordPage__start">
          <PopupButton content="기록 시작하기" color="green" />
        </div>
        <p className="recordPage__message" role="status">{message}</p>
      </form>
    </main>
  )
}

export default RecordPage
