import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Icon } from '../../components/icon/Icon'
import BottomButton from '../../components/bottom-button/BottomButton'
import './RecordAudioPage.css'


const companionLabels = { self: '본인', other: '다른 자녀', none: '동행 없음' }

async function convertToWav(recordedBlob) {
  const audioContext = new AudioContext()

  try {
    // 녹음 파일을 오디오 샘플로 풀기
    const buffer = await recordedBlob.arrayBuffer()
    const decoded = await audioContext.decodeAudioData(buffer)

    // 용량을 줄이기 위해 16kHz 모노로 변환
    const sampleRate = 16000
    const offline = new OfflineAudioContext(
      1,
      Math.ceil(decoded.duration * sampleRate),
      sampleRate
    )

    const source = offline.createBufferSource()
    source.buffer = decoded
    source.connect(offline.destination)
    source.start()

    const rendered = await offline.startRendering()
    const samples = rendered.getChannelData(0)

    // WAV 헤더 44바이트 + 16비트 오디오 데이터
    const wav = new ArrayBuffer(44 + samples.length * 2)
    const view = new DataView(wav)

    function writeText(offset, text) {
      for (let i = 0; i < text.length; i++) {
        view.setUint8(offset + i, text.charCodeAt(i))
      }
    }

    writeText(0, 'RIFF')
    view.setUint32(4, 36 + samples.length * 2, true)
    writeText(8, 'WAVE')
    writeText(12, 'fmt ')
    view.setUint32(16, 16, true)
    view.setUint16(20, 1, true)
    view.setUint16(22, 1, true)
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, sampleRate * 2, true)
    view.setUint16(32, 2, true)
    view.setUint16(34, 16, true)
    writeText(36, 'data')
    view.setUint32(40, samples.length * 2, true)

    for (let i = 0; i < samples.length; i++) {
      const sample = Math.max(-1, Math.min(1, samples[i]))
      view.setInt16(
        44 + i * 2,
        Math.round(sample * (sample < 0 ? 32768 : 32767)),
        true
      )
    }

    return new Blob([wav], { type: 'audio/wav' })
  } finally {
    await audioContext.close()
  }
}

function RecordAudioPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const [status, setStatus] = useState('idle')
  const [seconds, setSeconds] = useState(state?.seconds ?? 0)
  const [audioUrl, setAudioUrl] = useState(() => state?.audioBlob ? URL.createObjectURL(state.audioBlob) : '')
  const [message, setMessage] = useState('')
  const audioBlobRef = useRef(state?.audioBlob ?? null)
  const recorderRef = useRef(null)
  const streamRef = useRef(null)
  const playerRef = useRef(null)
  const mountedRef = useRef(true)
  const requestingRef = useRef(false)
  const uploadingRef = useRef(false)
  const startedRef = useRef(0)
  
  async function uploadRecording(accessToken, groupId, wavBlob) {
  // 1. 진료 기록 생성
  const recordResponse = await fetch(
    `/api/v1/care-groups/${groupId}/encounters`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        recordType: 'VISIT',
        title: '진료 기록',
        hospitalName: state?.hospital || null,
      }),
    }
  )

  if (!recordResponse.ok) {
    throw new Error('진료 기록을 생성하지 못했어요.')
  }

  // 2. 서버가 만든 기록을 encounter에 저장
  const recordResult = await recordResponse.json()
  const encounter = recordResult.data

  // 3. 응답에 들어 있던 버전을 그대로 사용
  const metadata = {
    expectedVersion: encounter.version,
    expectedInputVersion: encounter.inputVersion,
  }

  const formData = new FormData()

  formData.append(
    'metadata',
    new Blob(
      [JSON.stringify(metadata)],
      { type: 'application/json' }
    )
  )

  formData.append('file', wavBlob, 'recording.wav')

  // 4. 방금 만든 기록에 녹음 파일 업로드
  const uploadResponse = await fetch(
    `/api/v1/encounters/${encounter.id}/audio`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: formData,
    }
  )

  if (!uploadResponse.ok) {
    throw new Error('녹음 파일을 업로드하지 못했어요.')
  }

  return {
    encounterId: encounter.id,
    upload: (await uploadResponse.json()).data,
  }
}

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (recorderRef.current?.state === 'recording') recorderRef.current.stop()
      streamRef.current?.getTracks().forEach((track) => track.stop())
    }
  }, [])

  useEffect(() => {
    if (status !== 'recording') return
    const timer = setInterval(() => setSeconds(Math.floor((Date.now() - startedRef.current) / 1000)), 250)
    return () => clearInterval(timer)
  }, [status])

  useEffect(() => () => { if (audioUrl) URL.revokeObjectURL(audioUrl) }, [audioUrl])

  async function startRecording() {
    if (uploadingRef.current || requestingRef.current || recorderRef.current?.state === 'recording') return
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setMessage('이 브라우저에서는 녹음을 지원하지 않아요. HTTPS 또는 localhost에서 열어 주세요.')
      return
    }
    if (audioUrl && !window.confirm('기존 녹음을 지우고 새로 녹음할까요?')) return
    requestingRef.current = true
    setStatus('requesting')
    setMessage('')
    playerRef.current?.pause()
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (!mountedRef.current) { stream.getTracks().forEach((track) => track.stop()); return }
      streamRef.current = stream
      const recorder = new MediaRecorder(stream)
      recorderRef.current = recorder
      const chunks = []
      recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data) }
      recorder.onstop = () => {
        stream.getTracks().forEach((track) => track.stop())
        if (!mountedRef.current) return
        audioBlobRef.current = new Blob(chunks, { type: recorder.mimeType })
        setAudioUrl(URL.createObjectURL(audioBlobRef.current))
        setStatus('stopped')
      }
      recorder.onerror = () => {
        stream.getTracks().forEach((track) => track.stop())
        if (mountedRef.current) { setStatus('idle'); setMessage('녹음 중 문제가 발생했어요. 다시 시도해 주세요.') }
      }
      recorder.start()
      setAudioUrl('')
      startedRef.current = Date.now()
      setSeconds(0)
      setStatus('recording')
    } catch {
      streamRef.current?.getTracks().forEach((track) => track.stop())
      if (mountedRef.current) { setStatus('idle'); setMessage('마이크 권한과 연결 상태를 확인해 주세요.') }
    } finally { requestingRef.current = false }
  }

  function stopRecording() {
    if (recorderRef.current?.state === 'recording') {
      setStatus('stopping')
      recorderRef.current.stop()
    } else {
      playerRef.current?.pause()
      if (playerRef.current) playerRef.current.currentTime = 0
      setStatus('stopped')
    }
  }

  async function playRecording() {
    try {
      await playerRef.current.play()
      setStatus('playing')
    } catch { setMessage('녹음을 재생할 수 없어요. 다시 시도해 주세요.') }
  }

  const time = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value) => String(value).padStart(2, '0')).join(':')
  const busy = ['recording', 'requesting', 'stopping'].includes(status)

  return (
    <main className="recordAudioPage">
      <header className="recordAudioPage__header">
        <button type="button" className="recordAudioPage__back" aria-label="진료 기록으로 돌아가기" onClick={() => navigate('/record')}>
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>진료 녹음하기</h1>
      </header>
      <div className="recordAudioPage__content">
        <div className="recordAudioPage__controls" role="group" aria-label="녹음 제어">
          <button type="button" aria-label="녹음 시작" disabled={busy} onClick={startRecording}><span className="recordAudioPage__recordIcon" /></button>
          <button type="button" aria-label="정지" disabled={!['recording', 'playing'].includes(status)} onClick={stopRecording}><span className="recordAudioPage__stopIcon" /></button>
          <button type="button" aria-label="녹음 재생" disabled={!audioUrl || busy || status === 'playing'} onClick={playRecording}><span className="recordAudioPage__playIcon" /></button>
        </div>
        <div className="recordAudioPage__timer" role="timer" aria-label="녹음 시간">{time}</div>
        <audio ref={playerRef} src={audioUrl || undefined} onEnded={() => setStatus('stopped')} />
        <dl className="recordAudioPage__details">
          <div><dt>병원</dt><dd>{state?.hospital || '-'}</dd></div>
          <div><dt>진료과목</dt><dd>{state?.department || '-'}</dd></div>
          <div><dt>동행한 자녀</dt><dd>{companionLabels[state?.companion] || '-'}</dd></div>
        </dl>
        <section className="recordAudioPage__transcript">
          <h2>녹음 원문</h2>
          <p>이곳에 녹음이 전사돼요</p>
        </section>
        <p className="recordAudioPage__notice">이 기록은 가족과 공유할 수 있어요</p>
        <p className="recordAudioPage__notice">내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.</p>
        <p className="recordAudioPage__message" role="status">{message || (status === 'recording' ? '녹음 중이에요.' : '')}</p>
      </div>
      <BottomButton content="기록 마치기" onClick={async () => {
        if (uploadingRef.current) return
        if (busy) { setMessage('녹음을 정지한 뒤 기록을 마쳐 주세요.'); return }
        if (!audioBlobRef.current) { setMessage('먼저 진료 내용을 녹음해 주세요.'); return }

        const accessToken = sessionStorage.getItem('accessToken')
        const groupId = sessionStorage.getItem('groupId')

        if (!accessToken) {
          setMessage('로그인이 필요해요.')
          return
        }
        if (!groupId) {
          setMessage('가족을 먼저 선택해 주세요.')
          return
        }

        uploadingRef.current = true
        setMessage('녹음 파일을 업로드하고 있어요.')

        try {
          playerRef.current?.pause()
          const wavBlob = await convertToWav(audioBlobRef.current)
          const result = await uploadRecording(accessToken, groupId, wavBlob)

          if (!mountedRef.current) return
          navigate('/record/audio/analysis', {
            state: {
              ...state,
              seconds,
              encounterId: result.encounterId,
            },
          })
        } catch (error) {
          if (mountedRef.current) {
            setMessage(
              error instanceof Error ? error.message : '업로드에 실패했어요.'
            )
          }
        } finally {
          uploadingRef.current = false
        }
      }} />
    </main>
  )
}

export default RecordAudioPage
