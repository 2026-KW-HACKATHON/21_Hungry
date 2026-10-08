import './DocPage.css'

import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import CardInfo from '../../components/card-info/CardInfo'
import { Icon } from '../../components/icon/Icon'
import { formatDate, getTodayDate } from '../../mocks/todayAddMock'
import {
  documentTypeData,
  filterMedicalDocuments,
  getDocumentReadPath,
  getDocumentRegisteredDate,
  getMedicalDocuments,
} from '../../mocks/docMock'

function DocPage() {
  const navigate = useNavigate()
  const [initialMonth] = useState(() => {
    const [year, month] = getTodayDate().split('-').map(Number)
    return year * 12 + month - 1
  })
  const [monthOffset, setMonthOffset] = useState(0)
  const [query, setQuery] = useState('')
  const [selectedTypes, setSelectedTypes] = useState([])
  const [error, setError] = useState('')
  const [loaded] = useState(() => {
    try {
      return { documents: getMedicalDocuments(), error: '' }
    } catch (error) {
      return { documents: [], error: error.message }
    }
  })

  const monthIndex = initialMonth + monthOffset
  const year = Math.floor(monthIndex / 12)
  const month = monthIndex % 12
  const documents = filterMedicalDocuments(loaded.documents, { year, month, query, selectedTypes })

  const handleFilter = (type) => {
    setSelectedTypes((previous) =>
      previous.includes(type) ? previous.filter((item) => item !== type) : [...previous, type],
    )
  }

  const handleRead = (document) => {
    setError('')
    try {
      const path = getDocumentReadPath(document)
      navigate(`${path}?encounterId=${encodeURIComponent(document.id)}`)
    } catch (error) {
      setError(error.message)
    }
  }

  return (
    <div className='doc__page'>
      <header className='doc__header'>
        <p className='doc__header--title'>의료 문서 보관함</p>

        <div className='doc__controls'>
          <div className='doc__search'>
            <Icon name='doc-search' width={21} height={21} aria-hidden='true' />
            <input
              type='text'
              aria-label='문서 이름 또는 병원 검색'
              placeholder='문서 이름 또는 병원 검색'
              autoComplete='off'
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>
          <div className='doc__filters' role='group' aria-label='문서 종류 필터'>
            {documentTypeData.map((type) => (
              <button
                type='button'
                className={`doc__filter doc__filter--${type.type.toLowerCase()}${selectedTypes.includes(type.type) ? ' doc__filter--selected' : ''}`}
                key={type.type}
                aria-pressed={selectedTypes.includes(type.type)}
                onClick={() => handleFilter(type.type)}
              >
                {type.label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className='doc__content'>
        <div className='doc__month'>
          <p className='doc__month--title' aria-live='polite'>
            {year}년 {month + 1}월
          </p>
          <div className='doc__month--buttons'>
            <button
              type='button'
              className='doc__month--previous'
              aria-label='이전 달'
              disabled={monthOffset === -12}
              onClick={() => setMonthOffset((previous) => Math.max(-12, previous - 1))}
            >
              <Icon name='month-prev' width={9} height={15} aria-hidden='true' />
            </button>
            <button
              type='button'
              className='doc__month--next'
              aria-label='다음 달'
              disabled={monthOffset === 12}
              onClick={() => setMonthOffset((previous) => Math.min(12, previous + 1))}
            >
              <Icon name='month-next' width={9} height={15} aria-hidden='true' />
            </button>
          </div>
        </div>

        {(loaded.error || error) && (
          <p className='doc__error' role='alert'>
            {loaded.error || error}
          </p>
        )}
        <div className='doc__cards' aria-live='polite'>
          {documents.map((document) => {
            const type = documentTypeData.find((item) => item.type === document.documentType)
            return (
              <article
                className={`doc__card doc__card--${document.documentType.toLowerCase()}`}
                aria-label={document.title}
                key={document.id}
              >
                <CardInfo
                  category={type?.category ?? '기타 문서'}
                  label1='등록일'
                  value1={formatDate(getDocumentRegisteredDate(document))}
                  label2='발급기관'
                  value2={document.hospitalName || '-'}
                  label3='등록인'
                  value3={document.createdBy.displayName}
                />
                <div className='doc__card--buttons'>
                  <div className='doc__card--button'>
                    <PopupButton
                      content='문서 열람하기'
                      color='green'
                      onClick={() => handleRead(document)}
                    />
                  </div>
                  {document.recordType !== 'VISIT' && (
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
          {!loaded.error && documents.length === 0 && (
            <p className='doc__empty'>등록된 의료 문서가 없어요</p>
          )}
        </div>
      </div>

      <BottomButton content='의료 문서 업로드하기' onClick={() => navigate('/doc-select')} />
    </div>
  )
}

export default DocPage
