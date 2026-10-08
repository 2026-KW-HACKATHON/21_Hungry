import './DocAddPage.css'

import { useEffect, useRef, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Document, Page, pdfjs } from 'react-pdf'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import CardInfo from '../../components/card-info/CardInfo'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'
import { getAccessToken, messageOf } from '../../api/http'
import {
  getRecordGroup,
  requestRecordApi,
  createDocumentUploadAttempt,
  uploadDocumentAttempt,
} from '../../api/recordApi'

const documentTypeData = [
  { type: 'PRESCRIPTION', category: '처방전' },
  { type: 'DIAGNOSIS', category: '진단서' },
  { type: 'MEDICINE_BAG', category: '약 봉투' },
]

const formatDate = (date) => date.split('-').join('.')

function validateDocumentFile(file) {
  if (file.size === 0 || file.size > 10000000) {
    return '파일은 내용이 있는 10MB 이하의 파일이어야 해요.'
  }

  const supported = file.type
    ? ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(file.type)
    : /\.(jpe?g|png|webp|pdf)$/i.test(file.name)

  return supported ? '' : 'JPG, PNG, WEBP 이미지 또는 PDF 파일을 선택해 주세요.'
}

const pdfAssetUrl = `https://unpkg.com/pdfjs-dist@${pdfjs.version}/`

pdfjs.GlobalWorkerOptions.workerSrc = `${pdfAssetUrl}legacy/build/pdf.worker.min.mjs`

const pdfOptions = {
  cMapUrl: `${pdfAssetUrl}cmaps/`,
  cMapPacked: true,
  standardFontDataUrl: `${pdfAssetUrl}standard_fonts/`,
  wasmUrl: `${pdfAssetUrl}wasm/`,
  isEvalSupported: false,
  useSystemFonts: true,
}

function openNativePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    event.currentTarget.focus()
  }
}

function DocumentPreview({ file, onReady }) {
  const [preview, setPreview] = useState({ status: 'LOADING', url: '', error: '' })
  const [numPages, setNumPages] = useState(0)
  const isPdf = file.type === 'application/pdf' || (!file.type && /\.pdf$/i.test(file.name))
  const fileError = validateDocumentFile(file)

  useEffect(() => {
    if (isPdf || fileError) return undefined

    let active = true
    const image = new Image()
    const url = URL.createObjectURL(file)

    image.onload = () => {
      if (!active) return
      setPreview({ status: 'READY', url, error: '' })
      onReady(true)
    }
    image.onerror = () => {
      if (!active) return
      setPreview({
        status: 'FAILED',
        url: '',
        error: '이미지를 읽지 못했어요. 다른 파일을 선택해 주세요.',
      })
      onReady(false)
    }
    image.src = url

    return () => {
      active = false
      image.onload = null
      image.onerror = null
      URL.revokeObjectURL(url)
    }
  }, [file, isPdf, fileError, onReady])

  const handleFailure = (message) => {
    setPreview({ status: 'FAILED', url: '', error: message })
    onReady(false)
  }

  const error = fileError || preview.error

  return (
    <div className='docAdd__preview'>
      <div className='docAdd__previewArea' aria-busy={!error && preview.status === 'LOADING'}>
        {preview.url && <img src={preview.url} alt='첨부한 의료 문서 미리보기' />}
        {isPdf && !fileError && (
          <Document
            className='docAdd__pdf'
            file={file}
            options={pdfOptions}
            loading={null}
            error={null}
            noData={null}
            onSourceError={() =>
              handleFailure('PDF를 불러오지 못했어요. 다른 파일을 선택해 주세요.')
            }
            onLoadError={() =>
              handleFailure(
                'PDF를 읽지 못했어요. 인터넷 연결을 확인하거나 다른 파일을 선택해 주세요.',
              )
            }
            onPassword={() =>
              handleFailure('암호가 설정된 PDF는 사용할 수 없어요. 다른 파일을 선택해 주세요.')
            }
            onLoadSuccess={(document) => {
              if (document.numPages > 10) {
                handleFailure('PDF는 파일당 최대 10페이지까지 첨부할 수 있어요.')
                return
              }
              setNumPages(document.numPages)
            }}
          >
            {numPages > 0 && preview.status !== 'FAILED' && (
              <Page
                pageNumber={1}
                width={330}
                renderTextLayer={false}
                renderAnnotationLayer={false}
                loading={null}
                error={null}
                canvasBackground='white'
                aria-label='첨부한 PDF의 첫 페이지'
                onLoadError={() => handleFailure('PDF 첫 페이지를 읽지 못했어요.')}
                onRenderError={() => handleFailure('PDF 미리보기를 만들지 못했어요.')}
                onRenderSuccess={() => {
                  setPreview({ status: 'READY', url: '', error: '' })
                  onReady(true)
                }}
              />
            )}
          </Document>
        )}
        {!error && preview.status === 'LOADING' && <p role='status'>문서를 불러오는 중이에요.</p>}
        {error && <p role='alert'>{error}</p>}
      </div>
    </div>
  )
}

function DocumentForm({ file, type, accessToken }) {
  const navigate = useNavigate()
  const formRef = useRef(null)
  const dateRef = useRef(null)
  const hospitalRef = useRef(null)
  const savingRef = useRef(false)
  const attemptRef = useRef(null)
  const activeRef = useRef(false)
  const readControllerRef = useRef(null)
  const contextRetryRef = useRef(false)
  const refreshUploadRef = useRef(false)

  const [context, setContext] = useState({
    status: accessToken ? 'LOADING' : 'SIGNED_OUT',
    group: null,
    user: null,
    error: '',
  })
  const [contextAttempt, setContextAttempt] = useState(0)
  const [locked, setLocked] = useState(false)
  const [createdEncounterId, setCreatedEncounterId] = useState(null)
  const [registeredOn, setRegisteredOn] = useState('')
  const [hospitalName, setHospitalName] = useState('')
  const [isPreviewReady, setIsPreviewReady] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    activeRef.current = true

    return () => {
      activeRef.current = false
      readControllerRef.current?.abort()
    }
  }, [])

  useEffect(() => {
    if (!accessToken) return undefined

    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    Promise.all([
      getRecordGroup(accessToken, controller.signal),
      requestRecordApi('/me', accessToken, { signal: controller.signal }),
    ])
      .then(([group, user]) => {
        if (isActive()) {
          setContext({ status: 'READY', group, user, error: '' })
        }
      })
      .catch((loadError) => {
        if (isActive()) {
          setContext({
            status: 'FAILED',
            group: null,
            user: null,
            error: loadError.message || messageOf(loadError),
          })
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) contextRetryRef.current = false
      })

    return () => controller.abort()
  }, [accessToken, contextAttempt])

  const isActive = () => activeRef.current && getAccessToken() === accessToken

  const handleContextRetry = () => {
    if (contextRetryRef.current || context.status === 'LOADING') return

    contextRetryRef.current = true
    setContext({ status: 'LOADING', group: null, user: null, error: '' })
    setContextAttempt((value) => value + 1)
  }

  const handleSave = async (event) => {
    event.preventDefault()

    if (savingRef.current || !isPreviewReady) return

    if (!accessToken || getAccessToken() !== accessToken || context.status !== 'READY') {
      setError('로그인과 가족 연결 상태를 확인해 주세요.')
      return
    }

    const fileError = validateDocumentFile(file)
    if (fileError) {
      setError(fileError)
      return
    }

    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)
    setError('')

    try {
      if (!attemptRef.current) {
        attemptRef.current = createDocumentUploadAttempt(
          accessToken,
          context.group.id,
          file,
          type.type,
          registeredOn,
          hospitalName,
        )
      }

      setLocked(true)
      const attempt = attemptRef.current

      if (refreshUploadRef.current && attempt.encounter) {
        const controller = new AbortController()
        readControllerRef.current = controller

        const latest = await requestRecordApi(
          `/encounters/${encodeURIComponent(attempt.encounter.id)}`,
          accessToken,
          { signal: controller.signal },
        )

        if (!isActive()) return

        attempt.encounter = latest
        attempt.formData = null
        attempt.uploadKey = crypto.randomUUID()
        refreshUploadRef.current = false
      }

      await uploadDocumentAttempt(attempt)

      if (isActive()) {
        navigate('/doc', {
          replace: true,
        })
      }
    } catch (saveError) {
      if (!isActive()) return

      const versionConflict =
        saveError.apiCode === 'VERSION_CONFLICT' ||
        saveError.response?.data?.error?.code === 'VERSION_CONFLICT'

      if (versionConflict && attemptRef.current?.encounter) {
        refreshUploadRef.current = true
      }

      setCreatedEncounterId(attemptRef.current?.encounter?.id || null)
      setError(
        versionConflict
          ? '기록이 변경됐어요. 다시 저장하면 최신 상태를 조회하고 파일 업로드를 재시도해요.'
          : saveError.message || messageOf(saveError),
      )
    } finally {
      savingRef.current = false

      if (isActive()) setIsSaving(false)
    }
  }

  return (
    <div className='docAdd__page'>
      <BackHeader
        content='문서 저장'
        subcontent={'의료 문서 보관함에서 확인할 수 있어요.\nAI가 역할을 정할 때 참고해요.'}
      />
      <form className='docAdd__content' ref={formRef} onSubmit={handleSave}>
        <div className='docAdd__card'>
          <CardInfo
            category={type.category}
            label1='등록일'
            value1={registeredOn ? formatDate(registeredOn) : '--년 --월 --일'}
            label2='발급기관'
            value2={hospitalName.trim() || '-'}
            label3='등록인'
            value3={context.user?.displayName || '부모 정보 입력 전'}
          />
        </div>
        <div className='docAdd__inputs'>
          <div className='docAdd__input'>
            {!registeredOn && (
              <span className='docAdd__inputPlaceholder' aria-hidden='true'>
                등록일
              </span>
            )}
            <input
              ref={dateRef}
              type='date'
              aria-label='등록일'
              min='0001-01-01'
              max='9999-12-31'
              required
              disabled={isSaving || locked}
              data-empty={!registeredOn}
              value={registeredOn}
              onClick={openNativePicker}
              onChange={(event) => setRegisteredOn(event.target.value)}
            />
            {registeredOn && (
              <button
                type='button'
                aria-label='등록일 지우기'
                disabled={isSaving || locked}
                onClick={() => {
                  setRegisteredOn('')
                  dateRef.current.focus()
                }}
              >
                <Icon name='input-cancel' width={24} height={24} aria-hidden='true' />
              </button>
            )}
          </div>
          <div className='docAdd__input'>
            <input
              ref={hospitalRef}
              type='text'
              aria-label='발급기관'
              placeholder='발급기관'
              maxLength={150}
              disabled={isSaving || locked}
              value={hospitalName}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setHospitalName(event.target.value)
              }}
            />
            {hospitalName && (
              <button
                type='button'
                aria-label='발급기관 지우기'
                disabled={isSaving || locked}
                onClick={() => {
                  setHospitalName('')
                  hospitalRef.current.setCustomValidity('')
                  hospitalRef.current.focus()
                }}
              >
                <Icon name='input-cancel' width={24} height={24} aria-hidden='true' />
              </button>
            )}
          </div>
        </div>
        <DocumentPreview file={file} onReady={setIsPreviewReady} />
        {context.status === 'LOADING' && (
          <p role='status'>로그인과 가족 정보를 불러오는 중이에요.</p>
        )}
        {context.status === 'FAILED' && (
          <>
            <p className='docAdd__error' role='alert'>
              {context.error}
            </p>
            <PopupButton content='다시 조회하기' color='gray' onClick={handleContextRetry} />
          </>
        )}
        {error && (
          <p className='docAdd__error' role='alert'>
            {error}
          </p>
        )}
        {createdEncounterId && error && (
          <button
            type='button'
            disabled={isSaving}
            onClick={() =>
              navigate(`/doc-search?encounterId=${encodeURIComponent(createdEncounterId)}`)
            }
          >
            생성된 기록에서 상태 확인·삭제
          </button>
        )}
        {!accessToken && (
          <button type='button' onClick={() => navigate('/loginselect')}>
            로그인하기
          </button>
        )}
      </form>
      <BottomButton
        content={isSaving ? '저장 중...' : '의료 문서 저장하기'}
        disabled={isSaving || !isPreviewReady || !registeredOn || context.status !== 'READY'}
        onClick={() => formRef.current.requestSubmit()}
      />
    </div>
  )
}

function DocAddPage() {
  const location = useLocation()
  const accessToken = getAccessToken()
  const file = location.state?.file
  const type = documentTypeData.find((item) => item.type === location.state?.documentType)

  if (!(file instanceof File) || !type) return <Navigate to='/doc-select' replace />

  return (
    <DocumentForm
      key={`${location.key}:${accessToken || 'signed-out'}`}
      file={file}
      type={type}
      accessToken={accessToken}
    />
  )
}

export default DocAddPage
