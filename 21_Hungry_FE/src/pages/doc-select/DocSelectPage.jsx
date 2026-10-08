import './DocSelectPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import CardIcon from '../../components/card-icon/CardIcon'
import { Icon } from '../../components/icon/Icon'

const documentTypes = [
  { type: 'PRESCRIPTION', label: '처방전' },
  { type: 'DIAGNOSIS', label: '진단서' },
  { type: 'MEDICINE_BAG', label: '약 봉투' },
]

const imageTypes = ['image/jpeg', 'image/png', 'image/webp']
const documentAccept = '.jpg,.jpeg,.png,.webp,.pdf,image/jpeg,image/png,image/webp,application/pdf'
const cameraUnavailableMessage =
  '카메라를 사용할 수 없어요. HTTPS 또는 localhost에서 접속해 주세요.'

function CameraScreen({ onClose, onCapture }) {
  const videoRef = useRef(null)
  const canvasRef = useRef(null)
  const sessionRef = useRef(0)
  const captureRef = useRef(false)
  const [isReady, setIsReady] = useState(false)
  const [isCapturing, setIsCapturing] = useState(false)
  const [error, setError] = useState(() =>
    navigator.mediaDevices?.getUserMedia ? '' : cameraUnavailableMessage,
  )
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const session = ++sessionRef.current
    const video = videoRef.current
    let stream = null
    captureRef.current = false

    const openCamera = async () => {
      if (!navigator.mediaDevices?.getUserMedia) return

      try {
        const cameraStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
          audio: false,
        })
        if (session !== sessionRef.current) {
          cameraStream.getTracks().forEach((track) => track.stop())
          return
        }

        stream = cameraStream
        video.srcObject = stream
        await video.play()
      } catch (cameraError) {
        if (session !== sessionRef.current) return
        stream?.getTracks().forEach((track) => track.stop())
        video.srcObject = null
        setIsReady(false)
        if (cameraError.name === 'NotAllowedError') {
          setError('카메라 권한을 허용한 뒤 다시 시도해 주세요.')
        } else if (cameraError.name === 'NotFoundError') {
          setError('사용 가능한 카메라를 찾지 못했어요.')
        } else if (cameraError.name === 'NotReadableError') {
          setError('카메라를 사용할 수 없어요. 다른 앱에서 사용 중인지 확인해 주세요.')
        } else {
          setError('카메라 화면을 불러오지 못했어요. 다시 시도해 주세요.')
        }
      }
    }

    openCamera()

    return () => {
      sessionRef.current += 1
      stream?.getTracks().forEach((track) => track.stop())
      if (video) video.srcObject = null
    }
  }, [attempt])

  const handleRetry = () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setError(cameraUnavailableMessage)
      return
    }
    setIsReady(false)
    setIsCapturing(false)
    setError('')
    captureRef.current = false
    setAttempt((value) => value + 1)
  }

  const handleCapture = () => {
    const video = videoRef.current
    const canvas = canvasRef.current
    if (!video || !canvas || !isReady || captureRef.current) return
    if (!video.videoWidth || !video.videoHeight) {
      setError('촬영할 화면을 불러오지 못했어요.')
      return
    }

    const session = sessionRef.current
    captureRef.current = true
    setIsCapturing(true)
    setError('')

    const handleFailure = (message) => {
      if (session !== sessionRef.current) return
      captureRef.current = false
      setIsCapturing(false)
      setError(message)
    }

    try {
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      const context = canvas.getContext('2d')
      if (!context) {
        handleFailure('사진을 처리하지 못했어요.')
        return
      }

      context.drawImage(video, 0, 0, canvas.width, canvas.height)
      canvas.toBlob(
        (blob) => {
          if (session !== sessionRef.current) return
          if (!blob) {
            handleFailure('사진 촬영에 실패했어요. 다시 촬영해 주세요.')
            return
          }

          const file = new File([blob], `document-photo-${Date.now()}.jpg`, {
            type: 'image/jpeg',
          })
          const message = onCapture(file)
          if (message) handleFailure(message)
        },
        'image/jpeg',
        0.9,
      )
    } catch {
      handleFailure('사진을 처리하지 못했어요. 다시 촬영해 주세요.')
    }
  }

  return (
    <div className='docSelect__page'>
      <div className='docSelect__cameraToolbar'>
        <button type='button' onClick={onClose} aria-label='문서 선택으로 돌아가기'>
          <Icon name='back-button' width={11} height={19} aria-hidden='true' />
        </button>
        <h1>문서 촬영</h1>
      </div>
      <div className='docSelect__cameraContent'>
        <p className='docSelect__cameraDescription'>문서 전체가 보이도록 선명하게 촬영해 주세요.</p>
        <div className='docSelect__cameraPreview'>
          <video
            ref={videoRef}
            autoPlay
            muted
            playsInline
            aria-label='카메라 미리보기'
            onCanPlay={() => {
              const video = videoRef.current
              if (video?.srcObject?.active && video.videoWidth && video.videoHeight) {
                setIsReady(true)
              }
            }}
          />
          {!isReady && <p>{error ? '카메라 연결을 확인해 주세요.' : '카메라 연결 중...'}</p>}
          <canvas ref={canvasRef} hidden aria-hidden='true' />
        </div>
        {error && (
          <p className='docSelect__error' role='alert'>
            {error}
          </p>
        )}
        <div className='docSelect__cameraActions'>
          {error && !isReady && (
            <button type='button' className='docSelect__cameraRetry' onClick={handleRetry}>
              다시 연결하기
            </button>
          )}
          <button
            type='button'
            className='docSelect__cameraCapture'
            disabled={!isReady || isCapturing}
            onClick={handleCapture}
          >
            {isCapturing ? '사진 처리 중...' : '촬영하기'}
          </button>
        </div>
      </div>
    </div>
  )
}

function DocSelectPage() {
  const navigate = useNavigate()
  const fileInput = useRef(null)
  const [selectedType, setSelectedType] = useState('')
  const [error, setError] = useState('')
  const [isCameraOpen, setIsCameraOpen] = useState(false)

  const handleType = (type) => {
    setSelectedType(type)
    setError('')
  }

  const handleOpenCamera = () => {
    setError('')
    setIsCameraOpen(true)
  }

  const handleSelectedFile = (file, source) => {
    if (!selectedType) return '문서 종류를 먼저 선택해 주세요.'
    if (file.size === 0) return '내용이 없는 파일은 선택할 수 없어요.'
    if (file.size > 10000000) return '파일은 10MB 이하로 선택해 주세요.'

    const extension = file.name.split('.').pop().toLowerCase()
    const isImage = file.type
      ? imageTypes.includes(file.type)
      : ['jpg', 'jpeg', 'png', 'webp'].includes(extension)
    const isPdf = file.type ? file.type === 'application/pdf' : extension === 'pdf'
    if (!isImage && (source === 'CAMERA' || !isPdf)) {
      return source === 'CAMERA'
        ? 'JPG, PNG, WEBP 이미지 파일을 선택해 주세요.'
        : 'JPG, PNG, WEBP 이미지 또는 PDF 파일을 선택해 주세요.'
    }

    navigate('/doc-add', {
      state: { documentType: selectedType, file, source },
    })
    return ''
  }

  const handleFile = (event) => {
    const files = Array.from(event.target.files ?? [])
    event.target.value = ''
    setError('')
    if (!selectedType || files.length === 0) return
    if (files.length !== 1) {
      setError('파일은 한 개만 선택해 주세요.')
      return
    }
    setError(handleSelectedFile(files[0], 'FILE'))
  }

  if (isCameraOpen) {
    return (
      <CameraScreen
        onClose={() => setIsCameraOpen(false)}
        onCapture={(file) => handleSelectedFile(file, 'CAMERA')}
      />
    )
  }

  return (
    <div className='docSelect__page'>
      <BackHeader
        content='보관할 문서 선택'
        subcontent={'문서 종류를 선택한 뒤,\n선명하게 촬영하거나 파일을 불러와 주세요'}
      />

      <div className='docSelect__content'>
        <div className='docSelect__types' role='group' aria-label='문서 종류 선택'>
          <div className='docSelect__types--primary'>
            {documentTypes.map((type) => (
              <button
                type='button'
                className={`docSelect__type${selectedType === type.type ? ' docSelect__type--selected' : ''}`}
                key={type.type}
                aria-pressed={selectedType === type.type}
                onClick={() => handleType(type.type)}
              >
                <CardIcon name={type.label} content={type.label} />
              </button>
            ))}
          </div>
        </div>

        <div className='docSelect__import'>
          <p className='docSelect__import--title'>문서 가져오기</p>
          <div className='docSelect__import--buttons'>
            <button
              type='button'
              className='docSelect__import--button'
              disabled={!selectedType}
              onClick={handleOpenCamera}
            >
              <Icon name='doc-camera' width={28} height={28} aria-hidden='true' />
              <span>카메라로 촬영</span>
            </button>
            <button
              type='button'
              className='docSelect__import--button'
              disabled={!selectedType}
              onClick={() => fileInput.current?.click()}
            >
              <Icon name='doc-file' width={28} height={28} aria-hidden='true' />
              <span>파일에서 선택</span>
            </button>
          </div>
          <input
            ref={fileInput}
            type='file'
            accept={documentAccept}
            disabled={!selectedType}
            hidden
            onChange={handleFile}
          />
        </div>

        <div className='docSelect__notice'>
          <p>문서에 불필요한 주민등록번호나 계좌번호가 보이면 가린 뒤 올려 주세요.</p>
          <p>
            이미지 또는 PDF 파일을 한 개 선택할 수 있어요. 파일은 최대 10MB, PDF는 파일당 최대
            10페이지까지 첨부할 수 있어요.
          </p>
        </div>
        {error && (
          <p className='docSelect__error' role='alert'>
            {error}
          </p>
        )}
      </div>
    </div>
  )
}

export default DocSelectPage
