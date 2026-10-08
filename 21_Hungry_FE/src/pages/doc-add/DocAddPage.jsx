import './DocAddPage.css'

import { useEffect, useRef, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Document, Page, pdfjs } from 'react-pdf'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import CardInfo from '../../components/card-info/CardInfo'
import { Icon } from '../../components/icon/Icon'
import { currentUserId } from '../../mocks/familyMock'
import { familyData, formatDate } from '../../mocks/todayAddMock'
import { documentTypeData, saveMedicalDocument } from '../../mocks/docMock'

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
  }, [file, isPdf, invalidFile, onReady])

  const handleFailure = (message) => {
    setPreview({ status: 'FAILED', url: '', error: message })
    onReady(false)
  }

  const error = invalidFile ? '파일은 내용이 있는 10MB 이하의 파일이어야 해요.' : preview.error

  return (
    <div className='docAdd__preview'>
      <div className='docAdd__previewArea' aria-busy={!error && preview.status === 'LOADING'}>
        {preview.url && <img src={preview.url} alt='첨부한 의료 문서 미리보기' />}
        {isPdf && !invalidFile && (
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

function DocAddPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const formRef = useRef(null)
  const dateRef = useRef(null)
  const hospitalRef = useRef(null)
  const savingRef = useRef(false)
  const [registeredOn, setRegisteredOn] = useState('')
  const [hospitalName, setHospitalName] = useState('')
  const [isPreviewReady, setIsPreviewReady] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState('')

  const file = state?.file
  const type = documentTypeData.find(
    (item) => item.type === state?.documentType && item.type !== 'VISIT',
  )
  const member = familyData.find((family) => family.userId === currentUserId)

  if (!(file instanceof File) || !type) return <Navigate to='/doc-select' replace />

  const handleSave = async (event) => {
    event.preventDefault()
    if (savingRef.current || !isPreviewReady) return
    hospitalRef.current.setCustomValidity(hospitalName.trim() ? '' : '발급기관을 입력해 주세요.')
    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)
    setError('')
    try {
      await saveMedicalDocument({
        documentType: type.type,
        registeredOn,
        hospitalName,
        file,
        source: state.source,
      })
      navigate('/doc')
    } catch {
      setError('문서를 저장하지 못했어요. 다시 시도해 주세요.')
      savingRef.current = false
      setIsSaving(false)
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
            value3={member.name}
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
          <div className='docAdd__input'>
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
        <DocumentPreview
          key={`${file.name}-${file.size}-${file.lastModified}`}
          file={file}
          onReady={setIsPreviewReady}
        />
        {error && (
          <p className='docAdd__error' role='alert'>
            {error}
          </p>
        )}
      </form>
      <BottomButton
        content={isSaving ? '저장 중...' : '의료 문서 저장하기'}
        disabled={isSaving || !isPreviewReady || !registeredOn || !hospitalName.trim()}
        onClick={() => formRef.current.requestSubmit()}
      />
    </div>
  )
}

export default DocAddPage
