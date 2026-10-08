import './DocEditPage.css'

import { useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { Document, Page, pdfjs } from 'react-pdf'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import CardInfo from '../../components/card-info/CardInfo'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'
import { getAccessToken, messageOf } from '../../api/http'
import { getDocumentDetail, getDocumentFile } from '../../api/docSearchApi'
import {
  createDocumentEditAttempt,
  editableDocumentTypes,
  saveDocumentEditAttempt,
} from '../../api/docEditApi'

const documentCategories = {
  PRESCRIPTION: '처방전',
  DIAGNOSIS: '진단서',
  MEDICINE_BAG: '약 봉투',
  LEGACY_UNCLASSIFIED: '기타 문서',
}
const formatDate = (date) => date.split('-').join('.')

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
  const invalidFile = file.size === 0 || file.size > 10000000

  useEffect(() => {
    if (isPdf || invalidFile) return undefined

    let active = true
    const image = new Image()
    const url = URL.createObjectURL(file)

    image.onload = () => {
      if (!active) return
      setPreview({ status: 'READY', url, error: '' })
      onReady?.(true)
    }
    image.onerror = () => {
      if (!active) return
      setPreview({
        status: 'FAILED',
        url: '',
        error: '이미지를 읽지 못했어요. 다른 파일을 선택해 주세요.',
      })
      onReady?.(false)
    }
    image.src = url

    return () => {
      active = false
      image.onload = null
      image.onerror = null
      URL.revokeObjectURL(url)
    }
  }, [file, isPdf, invalidFile, onReady])

  const handleFailure = (message) => {
    setPreview({ status: 'FAILED', url: '', error: message })
    onReady?.(false)
  }

  const error = invalidFile ? '파일은 내용이 있는 10MB 이하의 파일이어야 해요.' : preview.error

  return (
    <div className='docEdit__preview'>
      <div className='docEdit__previewArea' aria-busy={!error && preview.status === 'LOADING'}>
        {preview.url && <img src={preview.url} alt='첨부한 의료 문서 미리보기' />}
        {isPdf && !invalidFile && (
          <Document
            className='docEdit__pdf'
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
                  onReady?.(true)
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

function StoredDocumentPreview({ source, accessToken }) {
  const [loaded, setLoaded] = useState(null)
  const [retry, setRetry] = useState(0)
  const canLoad = Boolean(source?.contentPath && source.file?.state === 'AVAILABLE')

  useEffect(() => {
    if (!canLoad) return undefined
    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    getDocumentFile(source, accessToken, controller.signal)
      .then((file) => {
        if (isActive()) setLoaded({ retry, file, error: '' })
      })
      .catch((error) => {
        if (isActive()) setLoaded({ retry, file: null, error: error.message || messageOf(error) })
      })

    return () => controller.abort()
  }, [source, accessToken, retry, canLoad])

  const current = loaded?.retry === retry ? loaded : null
  if (current?.file) return <DocumentPreview file={current.file} />

  return (
    <>
      <div className='docEdit__preview'>
        <div className='docEdit__previewArea' aria-busy={canLoad && !current}>
          <p role={current?.error || (source && !canLoad) ? 'alert' : 'status'}>
            {!source
              ? '첨부된 문서가 없어요.'
              : !canLoad
                ? '첨부 문서의 원본을 열람할 수 없어요.'
                : current?.error || '문서를 불러오는 중이에요.'}
          </p>
        </div>
      </div>
      {current?.error && (
        <PopupButton
          content='다시 불러오기'
          color='gray'
          onClick={() => setRetry((value) => value + 1)}
        />
      )}
    </>
  )
}

function DocumentForm({ initialDocument, source, accessToken }) {
  const navigate = useNavigate()
  const formRef = useRef(null)
  const dateRef = useRef(null)
  const hospitalRef = useRef(null)
  const fileRef = useRef(null)
  const savingRef = useRef(false)
  const attemptRef = useRef(null)
  const activeRef = useRef(false)
  const readControllerRef = useRef(null)
  const [document, setDocument] = useState(initialDocument)
  const [registeredOn, setRegisteredOn] = useState(initialDocument.occurredOn || '')
  const [hospitalName, setHospitalName] = useState(initialDocument.hospitalName || '')
  const [replacement, setReplacement] = useState(null)
  const [isPreviewReady, setIsPreviewReady] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [locked, setLocked] = useState(false)
  const [progress, setProgress] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
      readControllerRef.current?.abort()
    }
  }, [])

  const canReplace = Boolean(source && editableDocumentTypes.includes(source.documentType))
  const hasChanges =
    registeredOn !== (document.occurredOn || '') ||
    hospitalName.trim() !== (document.hospitalName || '') ||
    Boolean(replacement)
  const canSave =
    (locked || hasChanges) &&
    registeredOn &&
    hospitalName.trim() &&
    (!replacement || isPreviewReady) &&
    !isSaving

  const handleFile = (event) => {
    if (savingRef.current || locked || !canReplace) return
    const files = Array.from(event.target.files ?? [])
    event.target.value = ''
    if (files.length === 0) return
    if (files.length !== 1) {
      setError('파일은 한 개만 선택해 주세요.')
      return
    }
    const file = files[0]
    const extension = file.name.split('.').pop().toLowerCase()
    const supported = file.type
      ? ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(file.type)
      : ['jpg', 'jpeg', 'png', 'webp', 'pdf'].includes(extension)
    if (!supported) {
      setError('JPG, PNG, WEBP 이미지 또는 PDF 파일을 선택해 주세요.')
      return
    }
    if (file.size === 0 || file.size > 10000000) {
      setError('파일은 내용이 있는 10MB 이하의 파일이어야 해요.')
      return
    }
    setError('')
    setIsPreviewReady(false)
    setReplacement({ file, key: crypto.randomUUID() })
  }

  const handleSave = async (event) => {
    event.preventDefault()
    if (savingRef.current || !canSave) return
    if (getAccessToken() !== accessToken) {
      setError('로그인 상태를 확인해 주세요.')
      return
    }
    hospitalRef.current.setCustomValidity(hospitalName.trim() ? '' : '발급기관을 입력해 주세요.')
    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)
    setError('')
    const controller = new AbortController()
    readControllerRef.current = controller
    const isActive = () => activeRef.current && getAccessToken() === accessToken

    try {
      const previous = attemptRef.current
      const file = replacement?.file || null
      if (
        !previous ||
        previous.values.registeredOn !== registeredOn ||
        previous.values.hospitalName !== hospitalName.trim() ||
        previous.file !== file
      ) {
        attemptRef.current = createDocumentEditAttempt(
          document,
          source,
          { registeredOn, hospitalName, file },
          accessToken,
        )
      }
      setLocked(true)
      await saveDocumentEditAttempt(attemptRef.current, {
        signal: controller.signal,
        onProgress: (message) => {
          if (isActive()) setProgress(message)
        },
      })
      if (isActive()) navigate('/doc', { replace: true })
    } catch (saveError) {
      if (!isActive()) return
      const attempt = attemptRef.current
      const completed = attempt?.uploaded
        ? '새 파일은 업로드됐어요. 기존 파일 정리 단계부터 재시도해요. '
        : attempt?.metadataSaved && Object.keys(attempt.changes).length
          ? '문서 정보 변경은 저장됐어요. '
          : ''
      const reason = attempt?.needsRefresh
        ? '문서 상태가 변경됐어요. 다시 저장하면 최신 상태를 조회하고 남은 단계를 재시도해요.'
        : saveError.message || messageOf(saveError)
      setError(completed + reason)
      if (attempt && !attempt.uploaded && [400, 413, 415, 422].includes(saveError.status)) {
        setDocument(attempt.current)
        setLocked(false)
      }
    } finally {
      savingRef.current = false
      if (isActive()) {
        setIsSaving(false)
        setProgress('')
      }
    }
  }

  return (
    <>
      <form className='docEdit__content' ref={formRef} onSubmit={handleSave}>
        <div className='docEdit__card'>
          <CardInfo
            category={documentCategories[source?.documentType] || '기타 문서'}
            label1='등록일'
            value1={registeredOn ? formatDate(registeredOn) : '--년 --월 --일'}
            label2='발급기관'
            value2={hospitalName.trim() || '-'}
            label3='등록인'
            value3={document.createdBy?.displayName || '-'}
          />
          <fieldset className='docEdit__fileButton' disabled={isSaving || locked || !canReplace}>
            <PopupButton
              content='파일 변경하기'
              color='green'
              onClick={(event) => {
                event.preventDefault()
                fileRef.current.click()
              }}
            />
          </fieldset>
          <input
            ref={fileRef}
            type='file'
            aria-label='변경할 의료 문서 선택'
            accept='image/jpeg,image/png,image/webp,application/pdf,.jpg,.jpeg,.png,.webp,.pdf'
            disabled={isSaving || locked || !canReplace}
            hidden
            onChange={handleFile}
          />
        </div>

        {!canReplace && <p role='status'>이 문서는 등록일과 발급기관만 수정할 수 있어요.</p>}

        <div className='docEdit__notice'>
          <p>문서에 불필요한 주민등록번호나 계좌번호가 보이면 가린 뒤 올려 주세요.</p>
          <p>
            이미지 또는 PDF 파일을 한 개 선택할 수 있어요. 파일은 최대 10MB, PDF는 파일당 최대
            10페이지까지 첨부할 수 있어요.
          </p>
        </div>

        <div className='docEdit__inputs'>
          <div className='docEdit__input'>
            {!registeredOn && (
              <span className='docEdit__inputPlaceholder' aria-hidden='true'>
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
          <div className='docEdit__input'>
            <input
              ref={hospitalRef}
              type='text'
              aria-label='발급기관'
              placeholder='발급기관'
              maxLength={150}
              required
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

        {replacement ? (
          <DocumentPreview
            key={replacement.key}
            file={replacement.file}
            onReady={setIsPreviewReady}
          />
        ) : (
          <StoredDocumentPreview source={source} accessToken={accessToken} />
        )}
        {progress && <p role='status'>{progress}</p>}
        {error && (
          <p className='docEdit__error' role='alert'>
            {error}
          </p>
        )}
      </form>
      <BottomButton
        content={isSaving ? '저장 중...' : '변경사항 저장하기'}
        disabled={!canSave}
        onClick={() => formRef.current.requestSubmit()}
      />
    </>
  )
}

function DocumentEdit({ encounterId, accessToken }) {
  const [loaded, setLoaded] = useState(null)
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    if (!encounterId) return undefined
    const controller = new AbortController()
    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    getDocumentDetail(encounterId, accessToken, controller.signal)
      .then((result) => {
        if (isActive()) setLoaded({ retry, ...result, error: '' })
      })
      .catch((error) => {
        if (isActive())
          setLoaded({ retry, document: null, error: error.message || messageOf(error) })
      })

    return () => controller.abort()
  }, [encounterId, accessToken, retry])

  const current = loaded?.retry === retry ? loaded : null
  if (current?.document) {
    return (
      <DocumentForm
        initialDocument={current.document}
        source={current.source}
        accessToken={accessToken}
      />
    )
  }

  return (
    <div className='docEdit__content'>
      <p
        className={current?.error || !encounterId ? 'docEdit__error' : ''}
        role={current?.error || !encounterId ? 'alert' : 'status'}
      >
        {!encounterId
          ? '수정할 문서를 찾을 수 없어요. 보관함에서 다시 선택해 주세요.'
          : current?.error || '문서 정보를 불러오는 중이에요.'}
      </p>
      {current?.error && (
        <PopupButton
          content='다시 불러오기'
          color='gray'
          onClick={() => setRetry((value) => value + 1)}
        />
      )}
    </div>
  )
}

function DocEditPage() {
  const [searchParams] = useSearchParams()
  const encounterId = searchParams.get('encounterId') || ''
  const accessToken = getAccessToken()

  if (!accessToken) return <Navigate to='/loginselect' replace />

  return (
    <div className='docEdit__page'>
      <BackHeader content='문서 수정' />
      <DocumentEdit
        key={`${encounterId}:${accessToken}`}
        encounterId={encounterId}
        accessToken={accessToken}
      />
    </div>
  )
}

export default DocEditPage
