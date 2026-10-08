import { useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { getAccessToken } from '../../api/http'
import { getRecordGroup } from '../../api/recordApi'
import { todayInSeoul } from '../../api/recordModel'
import TitleHeader from '../../components/title-header/TitleHeader'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'
import './RecordPage.css'

const recordingChecks = [
  '녹음 시작 전 한 번만 권한을 요청해요',
  '권한을 거부하면 녹음이 시작되지 않아요',
  '설정에서 권한을 다시 허용할 수 있어요',
  '최대 20분·25MB이며 현재 방식은 약 13분에 종료돼요',
  '의료진의 동의를 받았어요',
]

function RecordPage() {
  const navigate = useNavigate()
  const [checked, setChecked] = useState([])
  const [message, setMessage] = useState('')
  const [group, setGroup] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const accessToken = getAccessToken()
  useEffect(() => {
    if (!accessToken) return
    const controller = new AbortController()
    getRecordGroup(accessToken, controller.signal)
      .then((result) => { if (!controller.signal.aborted) { setGroup(result); setMessage('') } })
      .catch((error) => { if (!controller.signal.aborted) setMessage(error.message) })
    return () => controller.abort()
  }, [accessToken, refresh])

  function startRecord(event) {
    event.preventDefault()
    if (!accessToken || !group) {
      setMessage(accessToken ? '가족 연결과 가입 승인 상태를 먼저 확인해 주세요.' : '로그인 후 기록할 수 있어요.')
      return
    }
    if (checked.length !== recordingChecks.length) {
      setMessage('녹음 및 기록 전 확인 사항을 모두 체크해 주세요.')
      return
    }
    navigate('/record/audio', { state: { ...Object.fromEntries(new FormData(event.currentTarget)), groupId: group.id, recipientName: group.recipient?.displayName || '부모 정보 입력 전' } })
  }

  return (
    <main className="recordPage">
      <TitleHeader
        content="진료 기록"
        subcontent={<>기본 정보만 확인하면 AI가 대화를 기록하고<br />이해하기 쉽게 정리해드려요</>}
      />
      <form className="recordPage__form" onSubmit={startRecord}>
        <div className="recordPage__fields">
          <p role="status">{group ? `돌봄 대상: ${group.recipient?.displayName || '부모 정보 입력 전'}` : accessToken ? '돌봄 대상을 확인하고 있어요.' : '로그인이 필요해요.'}</p>
          <input className="recordPage__input" name="hospital" aria-label="병원" maxLength={150} placeholder="병원 (미상인 경우 생략 가능)" />
          <label>진료일<input className="recordPage__input" type="date" name="occurredOn" aria-label="진료일" defaultValue={todayInSeoul()} /></label>
        </div>
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
        <p className="recordPage__notice">
          내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
        </p>
        <div className="recordPage__start">
          <PopupButton content="기록 시작하기" color="green" />
        </div>
        <p className="recordPage__message" role="status">{message}</p>
        {!accessToken && <button type="button" onClick={() => navigate('/loginselect')}>로그인하기</button>}
        {accessToken && !group && message && <button type="button" onClick={() => setRefresh((value) => value + 1)}>가족 연결 다시 확인</button>}
      </form>
    </main>
  )
}

export default RecordPage
