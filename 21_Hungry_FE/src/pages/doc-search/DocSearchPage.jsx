import './DocSearchPage.css'

import { useEffect, useState } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { Document, Page, pdfjs } from 'react-pdf'

import BackHeader from '../../components/back-header/BackHeader'
import CardInfo from '../../components/card-info/CardInfo'
import PopupButton from '../../components/popup-button/PopupButton'
import { getAccessToken, messageOf } from '../../api/http'
import { getDocumentDetail, getDocumentFile } from '../../api/docSearchApi'
import { formatDate, getKstDate } from '../today/todayUtils'

const documentCategories = {
  PRESCRIPTION: '처방전',
  DIAGNOSIS: '진단서',
  MEDICINE_BAG: '약 봉투',
  OTHER: '기타 문서',
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

function DocumentDetail({ encounterId, accessToken, onRetry }) {
  const [loaded, setLoaded] = useState(null)
  const [preview, setPreview] = useState({
    file: null,
    url: '',
    status: 'LOADING',
    error: '',
  })
  const [numPages, setNumPages] = useState(0)

  const document = loaded?.document
  const source = loaded?.source

  const isPdf =
    preview.file?.type === 'application/pdf' ||
    (preview.file && !preview.file.type && /\.pdf$/i.test(preview.file.name))

  useEffect(() => {
    const controller = new AbortController()
    let objectUrl = ''

    const isActive = () => !controller.signal.aborted && getAccessToken() === accessToken

    async function load() {
      try {
        if (!encounterId) {
          throw new Error('문서를 찾을 수 없어요. 보관함에서 다시 선택해 주세요.')
        }

        const detail = await getDocumentDetail(encounterId, accessToken, controller.signal)

        if (!isActive()) return

        setLoaded({
          ...detail,
          error: '',
        })

        if (!detail.source) {
          setPreview({
            file: null,
            url: '',
            status: 'MISSING',
            error: '',
          })
          return
        }

        if (!detail.source.contentPath || detail.source.file?.state !== 'AVAILABLE') {
          setPreview({
            file: null,
            url: '',
            status: 'FAILED',
            error: '첨부 문서의 원본을 열람할 수 없어요.',
          })
          return
        }

        try {
          const file = await getDocumentFile(detail.source, accessToken, controller.signal)

          if (!isActive()) return

          const isPdfFile =
            file.type === 'application/pdf' || (!file.type && /\.pdf$/i.test(file.name))

          if (!isPdfFile) {
            objectUrl = URL.createObjectURL(file)
          }

          setPreview({
            file,
            url: objectUrl,
            status: 'LOADING',
            error: '',
          })
        } catch (error) {
          if (!isActive()) return

          setPreview({
            file: null,
            url: '',
            status: 'FAILED',
            error: error.message || messageOf(error),
          })
        }
      } catch (error) {
        if (!isActive()) return

        setLoaded({
          document: null,
          source: null,
          error: error.message || messageOf(error),
        })
      }
    }

    load()

    return () => {
      controller.abort()

      if (objectUrl) {
        URL.revokeObjectURL(objectUrl)
      }
    }
  }, [encounterId, accessToken])

  const handleFailure = (message) => {
    setPreview((previous) => ({
      ...previous,
      status: 'FAILED',
      error: message,
    }))
  }

  if (!loaded) {
    return <p role='status'>문서 정보를 불러오는 중이에요.</p>
  }

  if (!document) {
    return (
      <>
        <p className='docSearch__error' role='alert'>
          {loaded.error}
        </p>

        <PopupButton content='다시 불러오기' color='gray' onClick={onRetry} />
      </>
    )
  }

  return (
    <>
      <div className='docSearch__card'>
        <CardInfo
          category={documentCategories[source?.documentType] || '기타 문서'}
          label1='등록일'
          value1={document.createdAt ? formatDate(getKstDate(document.createdAt)) : '-'}
          label2='발급기관'
          value2={document.hospitalName || '-'}
          label3='등록인'
          value3={document.createdBy?.displayName || '-'}
        />
      </div>

      <div className='docSearch__preview'>
        <div className='docSearch__previewArea' aria-busy={preview.status === 'LOADING'}>
          {preview.url && preview.status !== 'FAILED' && (
            <img
              src={preview.url}
              alt='첨부한 의료 문서 미리보기'
              onLoad={() =>
                setPreview((previous) => ({
                  ...previous,
                  status: 'READY',
                }))
              }
              onError={() => handleFailure('이미지를 읽지 못했어요.')}
            />
          )}

          {isPdf && preview.status !== 'FAILED' && (
            <Document
              className='docSearch__pdf'
              file={preview.file}
              options={pdfOptions}
              loading={null}
              error={null}
              noData={null}
              onSourceError={() => handleFailure('PDF를 불러오지 못했어요.')}
              onLoadError={() => handleFailure('PDF를 읽지 못했어요. 인터넷 연결을 확인해 주세요.')}
              onPassword={() => handleFailure('암호가 설정된 PDF는 미리보기를 표시할 수 없어요.')}
              onLoadSuccess={(pdf) => setNumPages(pdf.numPages)}
            >
              {numPages > 0 && (
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
                  onRenderSuccess={() =>
                    setPreview((previous) => ({
                      ...previous,
                      status: 'READY',
                    }))
                  }
                />
              )}
            </Document>
          )}

          {preview.status === 'LOADING' && <p role='status'>문서를 불러오는 중이에요.</p>}

          {preview.status === 'MISSING' && <p role='status'>첨부된 문서가 없어요.</p>}

          {preview.status === 'FAILED' && <p role='alert'>{preview.error}</p>}
        </div>
      </div>

      {preview.status === 'FAILED' && (
        <PopupButton content='다시 불러오기' color='gray' onClick={onRetry} />
      )}
    </>
  )
}

function DocSearchPage() {
  const [searchParams] = useSearchParams()
  const [retry, setRetry] = useState(0)

  const encounterId = searchParams.get('encounterId') || ''
  const accessToken = getAccessToken()

  if (!accessToken) {
    return <Navigate to='/loginselect' replace />
  }

  return (
    <div className='docSearch__page'>
      <BackHeader content='문서 열람' />

      <div className='docSearch__content'>
        <DocumentDetail
          key={JSON.stringify([encounterId, accessToken, retry])}
          encounterId={encounterId}
          accessToken={accessToken}
          onRetry={() => setRetry((value) => value + 1)}
        />
      </div>
    </div>
  )
}

export default DocSearchPage
