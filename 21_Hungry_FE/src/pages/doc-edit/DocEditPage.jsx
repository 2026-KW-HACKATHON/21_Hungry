import './DocEditPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Document, Page, pdfjs } from 'react-pdf'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import CardInfo from '../../components/card-info/CardInfo'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'
import { formatDate } from '../../mocks/todayAddMock'
import {
  documentTypeData,
  getDocumentRegisteredDate,
  getMedicalDocument,
  updateMedicalDocument,
} from '../../mocks/docMock'
import { getMedicalDocumentFile } from '../../mocks/docFileMock'

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

function StoredDocumentPreview({ encounterId }) {
  const [loaded, setLoaded] = useState({ file: null, status: 'LOADING' })

  useEffect(() => {
    let active = true
    getMedicalDocumentFile(encounterId)
      .then((file) => {
        if (active) setLoaded({ file, status: file instanceof File ? 'READY' : 'MISSING' })
      })
      .catch(() => {
        if (active) setLoaded({ file: null, status: 'FAILED' })
      })
    return () => {
      active = false
    }
  }, [encounterId])

  if (loaded.file instanceof File) return <DocumentPreview file={loaded.file} />
  return (
    <div className='docEdit__preview'>
      <div className='docEdit__previewArea' aria-busy={loaded.status === 'LOADING'}>
        <p role={loaded.status === 'FAILED' ? 'alert' : 'status'}>
          {loaded.status === 'LOADING'
            ? '문서를 불러오는 중이에요.'
            : loaded.status === 'MISSING'
              ? '첨부된 문서가 없어요.'
              : '첨부 문서를 불러오지 못했어요.'}
        </p>
      </div>
    </div>
  )
}

function DocumentEdit({ encounterId }) {
  const navigate = useNavigate()
  const formRef = useRef(null)
  const dateRef = useRef(null)
  const hospitalRef = useRef(null)
  const fileRef = useRef(null)
  const savingRef = useRef(false)
  const [loaded] = useState(() => {
    try {
      const document = encounterId ? getMedicalDocument(encounterId) : null
      if (!document || document.recordType !== 'DOCUMENT') {
        return {
          document: null,
          error: '수정할 문서를 찾을 수 없어요. 보관함에서 다시 선택해 주세요.',
        }
      }
      return { document, error: '' }
    } catch {
      return { document: null, error: '문서를 불러오지 못했어요. 보관함에서 다시 선택해 주세요.' }
    }
  })
  const document = loaded.document
  const [registeredOn, setRegisteredOn] = useState(() =>
    document ? getDocumentRegisteredDate(document) : '',
  )
  const [hospitalName, setHospitalName] = useState(() => document?.hospitalName || '')
  const [replacement, setReplacement] = useState(null)
  const [isPreviewReady, setIsPreviewReady] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState('')

  if (!document)
    return (
      <p className='docEdit__error' role='alert'>
        {loaded.error}
      </p>
    )

  const type = documentTypeData.find((item) => item.type === document.documentType)
  const hasChanges =
    registeredOn !== getDocumentRegisteredDate(document) ||
    hospitalName.trim() !== (document.hospitalName || '') ||
    Boolean(replacement)
  const canSave =
    hasChanges &&
    registeredOn &&
    hospitalName.trim() &&
    (!replacement || isPreviewReady) &&
    !isSaving

  const handleFile = (event) => {
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
    hospitalRef.current.setCustomValidity(hospitalName.trim() ? '' : '발급기관을 입력해 주세요.')
    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)
    setError('')
    try {
      await updateMedicalDocument({
        id: document.id,
        expectedVersion: document.version,
        registeredOn,
        hospitalName,
        file: replacement?.file,
      })
      navigate('/doc')
    } catch (error) {
      setError(error.message || '변경사항을 저장하지 못했어요. 다시 시도해 주세요.')
      savingRef.current = false
      setIsSaving(false)
    }
  }

  return (
    <>
      <form className='docEdit__content' ref={formRef} onSubmit={handleSave}>
        <div className='docEdit__card'>
          <CardInfo
            category={type?.category ?? '기타 문서'}
            label1='등록일'
            value1={registeredOn ? formatDate(registeredOn) : '--년 --월 --일'}
            label2='발급기관'
            value2={hospitalName.trim() || '-'}
            label3='등록인'
            value3={document.createdBy.displayName}
          />
          <fieldset className='docEdit__fileButton' disabled={isSaving}>
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
            disabled={isSaving}
            hidden
            onChange={handleFile}
          />
        </div>

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
              disabled={isSaving}
              data-empty={!registeredOn}
              value={registeredOn}
              onClick={openNativePicker}
              onChange={(event) => setRegisteredOn(event.target.value)}
            />
            {registeredOn && (
              <button
                type='button'
                aria-label='등록일 지우기'
                disabled={isSaving}
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
              disabled={isSaving}
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
                disabled={isSaving}
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
          <StoredDocumentPreview encounterId={document.id} />
        )}
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

function DocEditPage() {
  const [searchParams] = useSearchParams()
  const encounterId = searchParams.get('encounterId') || ''

  return (
    <div className='docEdit__page'>
      <BackHeader content='문서 수정' />
      <DocumentEdit key={encounterId} encounterId={encounterId} />
    </div>
  )
}

export default DocEditPage
