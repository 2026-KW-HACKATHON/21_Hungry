import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { getAccessToken, messageOf } from '../../api/http'
import {
  categoriesOf,
  documentTypes,
  filterDocuments,
  formatDocumentDate,
  getDocumentsForMonth,
  moveMonth,
} from '../../api/docApi'
import { todayInSeoul } from '../../api/recordModel'

import CardInfo from '../../components/card-info/CardInfo'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import { Icon } from '../../components/icon/Icon'

import './DocPage.css'

function DocumentList({ month, query, type, accessToken }) {
  const navigate = useNavigate()
  const [loaded, setLoaded] = useState({
    items: [],
    status: 'LOADING',
    error: '',
  })
  const [attempt, setAttempt] = useState(0)
  const retryRef = useRef(false)

  useEffect(() => {
    const controller = new AbortController()

    getDocumentsForMonth(month, accessToken, controller.signal)
      .then((items) => {
        if (!controller.signal.aborted) {
          setLoaded({ items, status: 'READY', error: '' })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setLoaded({
            items: [],
            status: 'FAILED',
            error: error.message || messageOf(error),
          })
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) retryRef.current = false
      })

    return () => controller.abort()
  }, [month, accessToken, attempt])

  const handleRetry = () => {
    if (retryRef.current || loaded.status === 'LOADING') return

    retryRef.current = true
    setLoaded({ items: [], status: 'LOADING', error: '' })
    setAttempt((value) => value + 1)
  }

  const documents = filterDocuments(loaded.items, query, type)

  if (loaded.status === 'LOADING') {
    return (
      <p className='doc__status' role='status'>
        기록을 불러오는 중이에요.
      </p>
    )
  }

  if (loaded.status === 'FAILED') {
    return (
      <div className='doc__feedback'>
        <p className='doc__error' role='alert'>
          {loaded.error}
        </p>

        <PopupButton content='목록 다시 조회' color='gray' onClick={handleRetry} />
      </div>
    )
  }

  return (
    <div className='doc__cards'>
      {documents.map((document) => {
        const visit = document.recordType === 'VISIT'
        const categories = categoriesOf(document)
        const notices = [
          document.processingState === 'FAILED' ? '처리 실패' : '',
          document.hasReviewItems || document.processingState === 'NEEDS_REVIEW'
            ? '확인할 내용 있음'
            : '',
          document.isSummaryStale ? '이전 분석 결과' : '',
        ].filter(Boolean)

        return (
          <article className='doc__card' key={document.id} aria-label={document.title}>
            {(categories.length ? categories : ['문서 기록']).map((category) => (
              <CardInfo
                key={category}
                category={category}
                label1='등록일'
                value1={formatDocumentDate(document.occurredOn)}
                label2='발급기관'
                value2={document.hospitalName || '-'}
                label3='등록인'
                value3={document.createdBy?.displayName || '-'}
              />
            ))}

            {notices.length > 0 && <p className='doc__notice'>{notices.join(' · ')}</p>}

            <div className='doc__card--buttons'>
              <div className='doc__card--button'>
                <PopupButton
                  content='문서 열람하기'
                  color='green'
                  onClick={() =>
                    navigate(
                      `${visit ? '/doc-record' : '/doc-search'}?encounterId=${encodeURIComponent(document.id)}`,
                    )
                  }
                />
              </div>

              {!visit && (
                <div className='doc__card--button'>
                  <PopupButton
                    content='수정하기'
                    color='gray'
                    onClick={() =>
                      navigate(`/doc-edit?encounterId=${encodeURIComponent(document.id)}`)
                    }
                  />
                </div>
              )}
            </div>
          </article>
        )
      })}

      {documents.length === 0 && <p className='doc__empty'>조건에 맞는 기록이 없어요.</p>}
    </div>
  )
}

export default function DocPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const accessToken = getAccessToken()
  const searchRef = useRef(null)

  const [query, setQuery] = useState('')
  const [type, setType] = useState('')
  const [month, setMonth] = useState(() => todayInSeoul().slice(0, 7))

  const [year, number] = month.split('-').map(Number)

  return (
    <div className='doc__page'>
      <header className='doc__header'>
        <h1 className='doc__header--title'>의료 문서 보관함</h1>

        <div className='doc__controls'>
          <div className='doc__search'>
            <Icon name='doc-search' width={21} height={21} aria-hidden='true' />

            <input
              ref={searchRef}
              type='search'
              aria-label='문서 이름 또는 병원 검색'
              placeholder='문서 이름 또는 병원 검색'
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />

            {query && (
              <button
                type='button'
                aria-label='검색어 지우기'
                onClick={() => {
                  setQuery('')
                  searchRef.current.focus()
                }}
              >
                <Icon name='input-cancel' width={24} height={24} aria-hidden='true' />
              </button>
            )}
          </div>

          <div className='doc__filters' role='group' aria-label='문서 종류 필터'>
            {documentTypes.map(({ value, label }) => (
              <button
                type='button'
                key={value}
                className={`doc__filter${value === 'VISIT' ? ' doc__filter--visit' : ''}${value === 'LEGACY_UNCLASSIFIED' ? ' doc__filter--other' : ''}${type === value ? ' doc__filter--selected' : ''}`}
                aria-pressed={type === value}
                onClick={() => setType((previous) => (previous === value ? '' : value))}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className='doc__content'>
        <div className='doc__month'>
          <h2 className='doc__month--title'>
            {year}년 {number}월
          </h2>

          <div className='doc__month--buttons'>
            <button
              type='button'
              className='doc__month--previous'
              aria-label='이전 달'
              disabled={month === '0001-01'}
              onClick={() => setMonth((previous) => moveMonth(previous, -1))}
            >
              <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
            </button>

            <button
              type='button'
              className='doc__month--next'
              aria-label='다음 달'
              disabled={month === '9999-12'}
              onClick={() => setMonth((previous) => moveMonth(previous, 1))}
            >
              <Icon name='month-next' width={9} height={15} aria-hidden='true' />
            </button>
          </div>
        </div>

        {state?.message && (
          <p className='doc__status' role='status'>
            {state.message}
          </p>
        )}

        {accessToken ? (
          <DocumentList
            key={`${accessToken}:${month}`}
            month={month}
            query={query}
            type={type}
            accessToken={accessToken}
          />
        ) : (
          <div className='doc__feedback'>
            <p className='doc__status'>로그인 후 가족의 기록을 볼 수 있어요.</p>

            <PopupButton
              content='로그인하기'
              color='gray'
              onClick={() => navigate('/loginselect')}
            />
          </div>
        )}
      </div>

      <BottomButton content='의료 문서 업로드하기' onClick={() => navigate('/doc-select')} />
    </div>
  )
}
