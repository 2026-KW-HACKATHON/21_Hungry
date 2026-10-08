import './DocRecordPage.css'

import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import CardInfo from '../../components/card-info/CardInfo'
import { formatDate } from '../../mocks/todayAddMock'
import { getDocumentRegisteredDate, getMedicalDocument } from '../../mocks/docMock'

const demoEncounterId = '88888888-8888-4888-8888-888888888882'

const demoRecordDetail = {
  departmentName: '내과',
  visitTime: '14:30',
  recordingDurationSeconds: 372,
  summary: {
    inputVersion: 1,
    text: '정기 진료에서 최근 생활 상태와 기존 검사 결과를 확인했어요. 복용 중인 약과 다음 방문 일정을 함께 정리했어요.',
    details: {
      schemaVersion: 1,
      symptoms: [{ text: '최근 생활 상태와 불편했던 점을 이야기했어요.', evidence: [] }],
      tests: [{ text: '기존 검사 결과를 확인했어요.', evidence: [] }],
      medicationMentions: [{ text: '현재 복용 중인 약을 확인했어요.', evidence: [] }],
      precautions: [
        { text: '생활 기록을 다음 진료 때 함께 확인하기로 했어요.', evidence: [] },
        { text: '가족이 함께 확인할 내용을 정리했어요.', evidence: [] },
      ],
      followUps: [{ text: '다음 방문 일정은 병원 안내에 따라 확인하기로 했어요.', evidence: [] }],
    },
  },
  sourceText: {
    sourceType: 'AUDIO',
    status: 'READY',
    textVersion: 1,
    text: '의료진: 최근 생활 상태는 어떠셨어요?\n부모: 지난번 진료 이후의 생활 기록을 가져왔어요.\n의료진: 기록과 기존 검사 결과를 함께 확인하겠습니다. 현재 복용 중인 약도 확인할게요.\n자녀: 가족이 함께 확인해야 할 내용도 정리하겠습니다.\n의료진: 다음 방문 일정은 병원 안내를 확인해 주세요.',
  },
}

const detailSections = ['symptoms', 'tests', 'medicationMentions', 'precautions', 'followUps']

function formatRecordingDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00:00'
  const total = Math.floor(seconds)
  return [Math.floor(total / 3600), Math.floor((total % 3600) / 60), total % 60]
    .map((value) => String(value).padStart(2, '0'))
    .join(':')
}

function RecordDetail({ encounterId }) {
  const [loaded] = useState(() => {
    try {
      const document = encounterId ? getMedicalDocument(encounterId) : null
      if (!document || document.recordType !== 'VISIT') {
        return {
          record: null,
          error: '녹음 진료 기록을 찾을 수 없어요. 보관함에서 다시 선택해 주세요.',
        }
      }
      const record =
        document.id === demoEncounterId ? { ...demoRecordDetail, ...document } : document
      return { record, error: '' }
    } catch {
      return {
        record: null,
        error: '진료 기록을 불러오지 못했어요. 보관함에서 다시 선택해 주세요.',
      }
    }
  })

  const record = loaded.record
  if (!record)
    return (
      <p className='docRecord__error' role='alert'>
        {loaded.error}
      </p>
    )

  const summary = record.summary
  const keyPoints = detailSections
    .flatMap((section) => summary?.details?.[section] ?? [])
    .map((item) => item.text?.trim())
    .filter(Boolean)
  const points = keyPoints.length > 0 ? keyPoints : ['-']
  const visitTime = /^([01]\d|2[0-3]):[0-5]\d$/.test(record.visitTime ?? '')
    ? record.visitTime
    : '--:--'
  const basicInformation = [
    { label: '병원', value: record.hospitalName || '-' },
    { label: '진료과목', value: record.departmentName || '-' },
    { label: '방문 일시', value: visitTime },
    { label: '동행한 자녀', value: record.createdBy.displayName },
  ]

  return (
    <>
      <div className='docRecord__card'>
        <CardInfo
          category='진료 기록'
          label1='등록일'
          value1={formatDate(getDocumentRegisteredDate(record))}
          label2='발급기관'
          value2={record.hospitalName || '-'}
          label3='등록인'
          value3={record.createdBy.displayName}
        />
      </div>

      <dl className='docRecord__basic'>
        {basicInformation.map((item) => (
          <div className='docRecord__basicRow' key={item.label}>
            <dt>{item.label}</dt>
            <dd>{item.value}</dd>
          </div>
        ))}
      </dl>

      <div className='docRecord__duration' aria-label='녹음 전체 길이'>
        {formatRecordingDuration(record.recordingDurationSeconds)}
      </div>

      <section className='docRecord__summary' aria-labelledby='doc-record-summary-title'>
        <h2 id='doc-record-summary-title'>쉬운 요약</h2>
        <p>{summary?.text?.trim() || '-'}</p>
      </section>

      <section className='docRecord__keyPoints' aria-labelledby='doc-record-points-title'>
        <h2 id='doc-record-points-title'>진료 핵심</h2>
        <ol>
          {points.map((text, index) => (
            <li key={index}>
              <span className='docRecord__number' aria-hidden='true'>
                {index + 1}
              </span>
              <p>{text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className='docRecord__transcript' aria-labelledby='doc-record-transcript-title'>
        <h2 id='doc-record-transcript-title'>녹음 원문</h2>
        <p>{record.sourceText?.text?.trim() || '-'}</p>
      </section>

      <p className='docRecord__notice'>
        내가 직접 참여한 진료 대화만 녹음할 수 있어요. 공개되지 않은 다른 사람들 사이의 대화를 몰래
        녹음하는 건 통신비밀보호법 제14조로 금지되어 있어요.
      </p>
    </>
  )
}

function DocRecordPage() {
  const [searchParams] = useSearchParams()
  const encounterId = searchParams.get('encounterId') || ''

  return (
    <div className='docRecord__page'>
      <BackHeader content='문서 열람' />
      <div className='docRecord__content'>
        <RecordDetail key={encounterId} encounterId={encounterId} />
      </div>
    </div>
  )
}

export default DocRecordPage
