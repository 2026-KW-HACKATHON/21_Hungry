import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { getAccessToken } from '../../api/http'
import { getRecordGroup, listRecords } from '../../api/recordApi'
import { recordStateLabels, todayInSeoul } from '../../api/recordModel'

import CardInfo from '../../components/card-info/CardInfo'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'

import './DocPage.css'

function RecordList({ filters, query, accessToken }) {
  const navigate = useNavigate()

  const [result, setResult] = useState({
    items: [],
    hasMore: false,
    nextCursor: null,
  })
  const [group, setGroup] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)

  const controllerRef = useRef(null)
  const loadingRef = useRef(false)
  const filterKey = JSON.stringify(filters)

  useEffect(() => {
    const controller = new AbortController()
    controllerRef.current = controller

    async function load() {
      try {
        const active = await getRecordGroup(accessToken, controller.signal)

        const data = await listRecords(
          active.id,
          accessToken,
          {
            ...JSON.parse(filterKey),
            limit: 20,
          },
          controller.signal,
        )

        if (!controller.signal.aborted) {
          setGroup(active)
          setResult(data)
          setError('')
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setError(error.message)
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
        }
      }
    }

    load()

    return () => controller.abort()
  }, [filterKey, accessToken, refresh])

  async function more() {
    if (loadingRef.current || !result.hasMore || !result.nextCursor) {
      return
    }

    loadingRef.current = true
    setLoading(true)

    try {
      const data = await listRecords(
        group.id,
        accessToken,
        {
          ...filters,
          limit: 20,
          cursor: result.nextCursor,
        },
        controllerRef.current.signal,
      )

      if (!controllerRef.current.signal.aborted) {
        setResult((previous) => ({
          ...data,
          items: [
            ...new Map([...previous.items, ...data.items].map((item) => [item.id, item])).values(),
          ],
        }))
        setError('')
      }
    } catch (error) {
      if (!controllerRef.current.signal.aborted) {
        setError(error.message)
      }
    } finally {
      loadingRef.current = false

      if (!controllerRef.current.signal.aborted) {
        setLoading(false)
      }
    }
  }

  const documents = result.items.filter((item) =>
    `${item.title} ${item.hospitalName || ''}`
      .toLocaleLowerCase()
      .includes(query.trim().toLocaleLowerCase()),
  )

  return (
    <>
      {group && <p>돌봄 대상: {group.recipient?.displayName || '부모 정보 입력 전'}</p>}

      {error && (
        <div>
          <p role='alert' className='doc__error'>
            {error}
          </p>

          <button
            onClick={() => {
              setLoading(true)
              setRefresh((value) => value + 1)
            }}
          >
            목록 다시 조회
          </button>
        </div>
      )}

      {loading && <p role='status'>기록을 불러오는 중이에요.</p>}

      {query && <p>현재 불러온 기록에서 검색해요. 더 보기를 누르면 검색 범위가 늘어나요.</p>}

      <div className='doc__cards'>
        {documents.map((document) => (
          <article
            className={`doc__card doc__card--${
              document.recordType === 'VISIT' ? 'visit' : 'other'
            }`}
            key={document.id}
          >
            <h2>{document.title}</h2>

            <CardInfo
              category={document.recordType === 'VISIT' ? '진료 기록' : '문서 기록'}
              label1='진료일'
              value1={document.occurredOn || '미상'}
              label2='발급기관'
              value2={document.hospitalName || '-'}
              label3='등록인'
              value3={document.createdBy?.displayName || '부모 정보 입력 전'}
            />

            <p>
              {recordStateLabels[document.processingState] || '상태 확인 중'}
              {document.isSummaryStale ? ' · 이전 분석 결과' : ''}
              {document.hasReviewItems ? ' · 확인할 내용 있음' : ''}
            </p>

            <div className='doc__card--buttons'>
              <PopupButton
                content='기록 열람하기'
                color='green'
                onClick={() =>
                  navigate(
                    `${
                      document.recordType === 'VISIT' ? '/doc-record' : '/doc-search'
                    }?encounterId=${encodeURIComponent(document.id)}`,
                  )
                }
              />
            </div>
          </article>
        ))}
      </div>

      {!loading && !error && documents.length === 0 && (
        <p className='doc__empty'>조건에 맞는 기록이 없어요.</p>
      )}

      {result.hasMore && (
        <button className='doc__filter' disabled={loading} onClick={more}>
          기록 더 보기
        </button>
      )}
    </>
  )
}

export default function DocPage() {
  const navigate = useNavigate()
  const { state } = useLocation()
  const accessToken = getAccessToken()

  const [query, setQuery] = useState('')
  const [type, setType] = useState('')
  const [month, setMonth] = useState(todayInSeoul().slice(0, 7))
  const [allDates, setAllDates] = useState(true)

  const filters = {
    recordType: type,
  }

  if (!allDates && month) {
    const [year, number] = month.split('-').map(Number)

    filters.fromDate = `${month}-01`
    filters.toDateExclusive = `${number === 12 ? year + 1 : year}-${String(
      number === 12 ? 1 : number + 1,
    ).padStart(2, '0')}-01`
  }

  return (
    <div className='doc__page'>
      <header className='doc__header'>
        <h1 className='doc__header--title'>의료 문서 보관함</h1>

        <div className='doc__controls'>
          <div className='doc__search'>
            <input
              aria-label='기록 이름 또는 병원 검색'
              placeholder='기록 이름 또는 병원 검색'
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>

          <div className='doc__filters'>
            {[
              ['', '전체'],
              ['VISIT', '진료 기록'],
              ['DOCUMENT', '문서 기록'],
            ].map(([value, label]) => (
              <button
                key={value}
                className={`doc__filter${type === value ? ' doc__filter--selected' : ''}`}
                aria-pressed={type === value}
                onClick={() => setType(value)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
      </header>

      <div className='doc__content'>
        <div className='doc__month'>
          <label>
            <input
              type='checkbox'
              checked={allDates}
              onChange={(event) => setAllDates(event.target.checked)}
            />{' '}
            전체 날짜 (진료일 미상 포함)
          </label>
        </div>

        {!allDates && (
          <label>
            진료월{' '}
            <input
              type='month'
              aria-label='진료월'
              value={month}
              onChange={(event) => setMonth(event.target.value)}
            />
          </label>
        )}

        {state?.message && <p role='status'>{state.message}</p>}

        {accessToken ? (
          <RecordList
            key={JSON.stringify(filters)}
            filters={filters}
            query={query}
            accessToken={accessToken}
          />
        ) : (
          <>
            <p>로그인 후 가족의 기록을 볼 수 있어요.</p>
            <button onClick={() => navigate('/loginselect')}>로그인하기</button>
          </>
        )}
      </div>

      <BottomButton content='의료 문서 업로드하기' onClick={() => navigate('/doc-select')} />
    </div>
  )
}
