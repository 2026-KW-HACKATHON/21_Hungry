import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Icon } from '../../components/icon/Icon'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import { requestRecordApi, createAudioUploadAttempt, uploadAudioAttempt } from '../../api/recordApi'
import './RecordAudioPage.css'


const companionLabels = { self: '본인', other: '다른 자녀', none: '동행 없음' }

async function convertToWav(recordedBlob) {
  const audioContext = new AudioContext()

  try {
    // 녹음 파일을 오디오 샘플로 풀기
    const buffer = await recordedBlob.arrayBuffer()
    const decoded = await audioContext.decodeAudioData(buffer)

    // 용량을 줄이기 위해 16kHz 모노로 변환
    if (decoded.duration <= 0 || decoded.duration > 1200) {
      throw new Error('녹음 길이는 0초 초과, 20분 이하여야 해요.')
    }
    const sampleRate = 16000
    if (44 + Math.ceil(decoded.duration * sampleRate) * 2 > 25_000_000) {
      throw new Error('WAV 변환 후 파일이 25MB를 넘어요. 더 짧게 녹음해 주세요.')
    }
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
  const [isUploading, setIsUploading] = useState(false)
  const audioBlobRef = useRef(state?.audioBlob ?? null)
  const recorderRef = useRef(null)
  const streamRef = useRef(null)
  const playerRef = useRef(null)
  const mountedRef = useRef(true)
  const requestingRef = useRef(false)
  const uploadingRef = useRef(false)
  const startedRef = useRef(0)
  const uploadAttemptRef = useRef(null)
  const confirmationRef = useRef(null)
  const finishAfterStopRef = useRef(false)
  
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (recorderRef.current?.state === 'recording') recorderRef.current.stop()
      streamRef.current?.getTracks().forEach((track) => track.stop())
      audioBlobRef.current = null
      uploadAttemptRef.current = null
    }
  }, [])

  useEffect(() => {
    if (status !== 'recording') return
    const timer = setInterval(() => {
      const elapsed = Math.floor((Date.now() - startedRef.current) / 1000)
      setSeconds(elapsed)
      if (elapsed >= 1200 && recorderRef.current?.state === 'recording') {
        setStatus('stopping')
        recorderRef.current.stop()
        setMessage('20분이 되어 녹음을 종료했어요. 파일 용량도 확인한 뒤 업로드해요.')
      }
    }, 250)
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
      recorder.onstop = async () => {
        stream.getTracks().forEach((track) => track.stop())
        if (!mountedRef.current) return
        setStatus('stopping')
        const blob = new Blob(chunks, { type: recorder.mimeType })
        if (!blob.size) {
          setStatus('idle')
          setMessage('녹음 데이터가 없어요. 마이크 연결을 확인해 주세요.')
          return
        }
        const context = new AudioContext()
        try {
          const decoded = await context.decodeAudioData(await blob.arrayBuffer())
          let peak = 0
          for (let channel = 0; channel < decoded.numberOfChannels; channel++) {
            for (const sample of decoded.getChannelData(channel)) peak = Math.max(peak, Math.abs(sample))
          }
          if (!mountedRef.current) return
          setMessage(peak < 0.001
            ? '녹음된 소리가 없거나 매우 작아요. 마이크 음소거와 입력 장치를 확인해 주세요.'
            : '소리가 담긴 녹음 파일을 만들었어요. 재생해서 확인해 주세요.')
        } catch {
          if (mountedRef.current) setMessage('녹음 파일을 해석하지 못했어요. 다시 녹음해 주세요.')
        } finally {
          await context.close().catch(() => {})
          if (mountedRef.current) {
            audioBlobRef.current = blob
            setAudioUrl(URL.createObjectURL(blob))
            setStatus('stopped')
            if (finishAfterStopRef.current) {
              finishAfterStopRef.current = false
              setMessage('')
              confirmationRef.current?.showModal()
            }
          }
        }
      }
      recorder.onerror = () => {
        stream.getTracks().forEach((track) => track.stop())
        if (mountedRef.current) { setStatus('idle'); setMessage('녹음 중 문제가 발생했어요. 다시 시도해 주세요.') }
      }
      recorder.start(1000)
      audioBlobRef.current = null
      uploadAttemptRef.current = null
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
    if (uploadingRef.current) return
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
    if (uploadingRef.current) return
    try {
      const player = playerRef.current
      if (!player || !audioUrl) return
      player.muted = false
      player.volume = 1
      player.currentTime = 0
      await player.play()
      setStatus('playing')
    } catch { setMessage('녹음을 재생할 수 없어요. 다시 시도해 주세요.') }
  }

  function finishRecord() {
    if (uploadingRef.current || requestingRef.current || status === 'stopping') return
    if (recorderRef.current?.state === 'recording') {
      finishAfterStopRef.current = true
      stopRecording()
      return
    }
    if (!audioBlobRef.current) {
      setMessage('먼저 진료 내용을 녹음해 주세요.')
      return
    }
    playerRef.current?.pause()
    setMessage('')
    confirmationRef.current?.showModal()
  }

  function discardRecording() {
    if (uploadingRef.current) return
    playerRef.current?.pause()
    audioBlobRef.current = null
    uploadAttemptRef.current = null
    finishAfterStopRef.current = false
    setAudioUrl('')
    setSeconds(0)
    setStatus('idle')
    setMessage('녹음을 삭제했어요. 다시 녹음해 주세요.')
    confirmationRef.current?.close()
  }

  async function confirmRecording() {
        if (uploadingRef.current) return
        if (busy) { setMessage('녹음을 정지한 뒤 기록을 마쳐 주세요.'); return }
        if (!audioBlobRef.current) { setMessage('먼저 진료 내용을 녹음해 주세요.'); return }

        const accessToken = sessionStorage.getItem('accessToken')

        if (!accessToken) {
          setMessage('로그인이 필요해요.')
          return
        }

        uploadingRef.current = true
        setIsUploading(true)
        setMessage('녹음 파일을 업로드하고 있어요.')

        try {
          playerRef.current?.pause()
          const groups = await requestRecordApi('/me/care-groups', accessToken)
          if (!mountedRef.current) return
          if (groups.items?.length !== 1) {
            throw new Error('연결된 가족이 없어요. 가족 연결과 가입 승인을 확인해 주세요.')
          }
          const groupId = groups.items[0].id
          sessionStorage.setItem('groupId', groupId)
          let attempt = uploadAttemptRef.current
          if (attempt && (attempt.accessToken !== accessToken || attempt.groupId !== groupId)) {
            throw new Error('로그인 계정이나 가족이 변경됐어요. 현재 녹음을 삭제하고 다시 녹음해 주세요.')
          }
          if (!attempt) {
            const wavBlob = await convertToWav(audioBlobRef.current)
            if (!mountedRef.current) return
            attempt = createAudioUploadAttempt(accessToken, groupId, wavBlob, state?.hospital)
            uploadAttemptRef.current = attempt
          }
          const result = await uploadAudioAttempt(attempt)

          if (!mountedRef.current) return
          navigate('/record/audio/analysis', {
            state: {
              hospital: state?.hospital,
              department: state?.department,
              companion: state?.companion,
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
          if (mountedRef.current) setIsUploading(false)
        }
  }

  const time = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value) => String(value).padStart(2, '0')).join(':')
  const busy = isUploading || ['recording', 'requesting', 'stopping'].includes(status)

  return (
    <main className="recordAudioPage">
      <header className="recordAudioPage__header">
        <button type="button" className="recordAudioPage__back" aria-label="진료 기록으로 돌아가기" disabled={isUploading} onClick={() => {
          if ((audioBlobRef.current || recorderRef.current?.state === 'recording') && !window.confirm('화면을 나가면 업로드하지 않은 녹음이 사라져요. 나갈까요?')) return
          navigate('/record')
        }}>
          <Icon name="back-button" width={11} height={19} aria-hidden="true" />
        </button>
        <h1>진료 녹음하기</h1>
      </header>
      <div className="recordAudioPage__content">
        <div className="recordAudioPage__controls" role="group" aria-label="녹음 제어">
          <button type="button" aria-label="녹음 시작" disabled={busy} onClick={startRecording}><span className="recordAudioPage__recordIcon" /></button>
          <button type="button" aria-label="정지" disabled={isUploading || !['recording', 'playing'].includes(status)} onClick={stopRecording}><span className="recordAudioPage__stopIcon" /></button>
          <button type="button" aria-label="녹음 재생" disabled={!audioUrl || busy || status === 'playing'} onClick={playRecording}><span className="recordAudioPage__playIcon" /></button>
        </div>
        <div className="recordAudioPage__timer" role="timer" aria-label="녹음 시간">{time}</div>
        <audio
          ref={playerRef}
          src={audioUrl || undefined}
          controls={Boolean(audioUrl)}
          style={{ width: '100%' }}
          onPlay={() => setStatus('playing')}
          onPause={() => setStatus((previous) => previous === 'playing' ? 'stopped' : previous)}
          onEnded={() => setStatus('stopped')}
          onError={() => setMessage('녹음 파일을 재생할 수 없어요. 다시 녹음해 주세요.')}
        />
        <dl className="recordAudioPage__details">
          <div><dt>병원</dt><dd>{state?.hospital || '-'}</dd></div>
          <div><dt>진료과목</dt><dd>{state?.department || '-'}</dd></div>
          <div><dt>동행한 자녀</dt><dd>{companionLabels[state?.companion] || '-'}</dd></div>
        </dl>
        <section className="recordAudioPage__transcript">
          <h2>녹음 원문</h2>
          <p>녹음 사용을 선택하면 업로드 후 원문과 요약을 확인할 수 있어요.</p>
        </section>
        <p className="recordAudioPage__notice">이 기록은 가족과 공유할 수 있어요</p>
        <p className="recordAudioPage__notice">내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래 녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.</p>
        <p className="recordAudioPage__message" role="status">{message || (status === 'recording' ? '녹음 중이에요.' : '')}</p>
      </div>
      <BottomButton content="기록 마치기" onClick={finishRecord} />
      <dialog
        ref={confirmationRef}
        className="recordAudioPage__confirmation"
        aria-labelledby="record-confirmation-title"
        onCancel={(event) => { if (uploadingRef.current) event.preventDefault() }}
      >
        <h2 id="record-confirmation-title">이 녹음을 사용할까요?</h2>
        <section className="recordAudioPage__confirmationTranscript">
          <h3>녹음 원문</h3>
          <p>녹음을 사용하면 원문이 전사돼요</p>
        </section>
        <fieldset className="recordAudioPage__confirmationActions" disabled={isUploading}>
          <PopupButton content="아니요" color="red" onClick={discardRecording} />
          <PopupButton content={isUploading ? '업로드 중...' : '예'} color="blue" onClick={confirmRecording} />
        </fieldset>
        {message && <p className="recordAudioPage__confirmationMessage" role="status">{message}</p>}
      </dialog>
    </main>
  )
}

export default RecordAudioPage
