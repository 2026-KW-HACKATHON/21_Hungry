import './TodayAddPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'

import { getAccessToken, messageOf } from '../../api/http'
import {
  assignCreatedTask,
  createTodayTask,
  getCreatedTask,
  getTodayAddContext,
} from '../../api/todayAddApi'

import { getParentName } from '../today/todayUtils'

const categoryData = [
  { kind: 'EXAM', name: '건강검진' },
  { kind: 'HOSPITAL', name: '병원 내원' },
  { kind: 'MEDICATION', name: '약 복용' },
  { kind: 'OTHER', name: '기타 돌봄' },
]

const isValidTime = (value) => /^([01]\d|2[0-3]):[0-5]\d$/.test(value.trim())
const errorMessage = (error) => error.message || messageOf(error)

function openNativePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    event.currentTarget.focus()
  }
}

function TodayAddPage() {
  const navigate = useNavigate()

  const formRef = useRef(null)
  const dateRef = useRef(null)
  const timeRef = useRef(null)
  const savingRef = useRef(false)
  const createRequest = useRef(null)
  const assignmentRequest = useRef(null)
  const mounted = useRef(false)

  const [selectedKind, setSelectedKind] = useState('OTHER')
  const [selectedFamilyId, setSelectedFamilyId] = useState(null)
  const [description, setDescription] = useState('')
  const [date, setDate] = useState('')
  const [time, setTime] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  const [setupResult, setSetupResult] = useState(null)
  const [setupRetry, setSetupRetry] = useState(0)
  const [createdTask, setCreatedTask] = useState(null)

  const setup = setupResult?.key === setupRetry ? setupResult : null
  const group = setup?.data?.group
  const setupError = setup?.error || ''
  const isLoading = !setup
  const draftLocked = isSaving || Boolean(createdTask)

  const relation = {
    MOTHER: '어머니',
    FATHER: '아버지',
  }[group?.parentProfile?.relation]

  const familyData = (setup?.data?.members || []).map((member) => ({
    userId: member.user.id,
    name: member.user.id === group?.recipient.id ? getParentName(group) : member.user.displayName,
    category:
      member.user.id === group?.recipient.id
        ? relation || null
        : member.user.id === setup?.data?.user.id
          ? '나'
          : null,
  }))

  useEffect(() => {
    mounted.current = true

    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()

    getTodayAddContext({ signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) {
          setSetupResult({
            key: setupRetry,
            data,
            error: '',
          })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setSetupResult({
            key: setupRetry,
            data: null,
            error: errorMessage(error),
          })
        }
      })

    return () => controller.abort()
  }, [navigate, setupRetry])

  const handleFamilySelect = (userId) => {
    setSelectedFamilyId((previous) => (previous === userId ? null : userId))
  }

  const handleAdd = async (event) => {
    event.preventDefault()
    if (savingRef.current || !group) return

    dateRef.current.setCustomValidity(date ? '' : '날짜를 선택해 주세요.')
    timeRef.current.setCustomValidity(isValidTime(time) ? '' : '시간을 선택해 주세요.')

    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)

    let task = createdTask

    try {
      if (!task) {
        const payload = {
          kind: selectedKind,
          title: categoryData.find((category) => category.kind === selectedKind).name,
          description: description.trim() || null,
          rule: {
            recurrence: 'ONCE',
            firstDate: date,
            lastDate: date,
            weekdays: [],
            localTime: time.trim(),
            durationMinutes: 60,
          },
        }

        const signature = JSON.stringify([group.id, payload])

        if (createRequest.current?.signature !== signature) {
          createRequest.current = {
            signature,
            key: crypto.randomUUID(),
          }
        }

        const result = await createTodayTask(group.id, payload, createRequest.current.key)

        task = result.occurrences[0]

        if (!task?.id) {
          throw new Error('생성된 일정 정보를 확인할 수 없어요.')
        }

        if (mounted.current) {
          setCreatedTask(task)
        }
      }

      if (selectedFamilyId) {
        const latest = await getCreatedTask(task.id)

        if (latest.assignee?.id !== selectedFamilyId) {
          const signature = JSON.stringify([latest.id, latest.version, selectedFamilyId])

          if (assignmentRequest.current?.signature !== signature) {
            assignmentRequest.current = {
              signature,
              key: crypto.randomUUID(),
            }
          }

          await assignCreatedTask(latest, selectedFamilyId, assignmentRequest.current.key)
        }
      }

      if (mounted.current) {
        navigate('/today', { replace: true })
      }
    } catch (error) {
      if (mounted.current) {
        window.alert(
          task?.id
            ? `일정은 이미 추가됐어요. 담당 가족 지정에 실패했어요.\n${errorMessage(error)}\n가족을 다시 선택해 재시도하거나, 선택을 해제한 뒤 완료해 주세요.`
            : errorMessage(error),
        )
      }
    } finally {
      savingRef.current = false

      if (mounted.current) {
        setIsSaving(false)
      }
    }
  }

  return (
    <div className='todayAdd__page'>
      <BackHeader
        content='돌봄 일정 추가'
        subcontent={
          '필요한 돌봄 일정의 시간을 추가할 수 있어요.\n가족의 개인 일정을 조회하며 담당 자녀를 배정해요.'
        }
      />

      <form className='todayAdd__content' ref={formRef} onSubmit={handleAdd}>
        <div className='todayAdd__categories' role='group' aria-label='돌봄 일정 종류'>
          {categoryData.map((category) => (
            <button
              key={category.kind}
              type='button'
              className={`todayAdd__category${selectedKind === category.kind ? ' todayAdd__category--selected' : ''}`}
              aria-pressed={selectedKind === category.kind}
              disabled={draftLocked || category.kind === 'MEDICATION'}
              title={
                category.kind === 'MEDICATION'
                  ? '약 복용 일정은 처방 정보를 통해 추가할 수 있어요.'
                  : undefined
              }
              onClick={() => setSelectedKind(category.kind)}
            >
              {category.name}
            </button>
          ))}
        </div>

        <div className='todayAdd__description'>
          <label className='todayAdd__description--title' htmlFor='todayAdd-description'>
            상세 설명
          </label>

          <textarea
            id='todayAdd-description'
            className='todayAdd__description--input'
            rows={1}
            placeholder='-'
            disabled={draftLocked}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </div>

        <div className='todayAdd__notice'>
          상세 설명에는 장소와 특정 행동을 입력해주는 것이 좋아요
        </div>

        <div className='todayAdd__inputs'>
          <div className='todayAdd__input'>
            <input
              ref={dateRef}
              className='todayAdd__input--input'
              type='date'
              min='0001-01-01'
              max='9999-12-31'
              onClick={openNativePicker}
              aria-label='날짜'
              placeholder='날짜'
              required
              disabled={draftLocked}
              value={date}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setDate(event.target.value)
              }}
            />

            <button
              type='button'
              className='todayAdd__input--clear'
              aria-label='날짜 지우기'
              disabled={draftLocked || !date}
              onClick={() => {
                setDate('')
                dateRef.current.setCustomValidity('')
                dateRef.current.focus()
              }}
            >
              {date && <Icon name='input-cancel' width={24} height={24} />}
            </button>
          </div>

          <div className='todayAdd__input'>
            <input
              ref={timeRef}
              className='todayAdd__input--input'
              type='time'
              step={60}
              onClick={openNativePicker}
              aria-label='시간'
              placeholder='시간'
              title='23:00'
              required
              disabled={draftLocked}
              value={time}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setTime(event.target.value)
              }}
            />

            <button
              type='button'
              className='todayAdd__input--clear'
              aria-label='시간 지우기'
              disabled={draftLocked || !time}
              onClick={() => {
                setTime('')
                timeRef.current.setCustomValidity('')
                timeRef.current.focus()
              }}
            >
              {time && <Icon name='input-cancel' width={24} height={24} />}
            </button>
          </div>
        </div>

        <div className='todayAdd__notice'>시간은 24시간제로 입력해 주세요</div>

        {isLoading && (
          <p className='todayAdd__status' role='status'>
            가족 정보를 불러오고 있어요.
          </p>
        )}

        {setupError && (
          <div className='todayAdd__status' role='alert'>
            <p>{setupError}</p>
            <button type='button' onClick={() => setSetupRetry((value) => value + 1)}>
              다시 시도하기
            </button>
          </div>
        )}

        {setup?.data && !group && (
          <p className='todayAdd__status'>가족 연결을 완료해야 일정을 추가할 수 있어요.</p>
        )}

        {createdTask && (
          <p className='todayAdd__status'>
            일정은 추가됐어요. 담당 가족을 다시 선택하거나 선택을 해제한 뒤 완료해 주세요.
          </p>
        )}

        <div className='todayAdd__family'>
          <div className='todayAdd__family--title'>담당 가족</div>

          <div className='todayAdd__family--items' role='group' aria-label='담당 가족 선택'>
            {familyData.map((family) => (
              <button
                type='button'
                className='todayAdd__family--item'
                key={family.userId}
                aria-pressed={selectedFamilyId === family.userId}
                disabled={isSaving}
                onClick={() => handleFamilySelect(family.userId)}
              >
                <span className='todayAdd__family--name'>
                  {family.name}
                  {family.category !== null && `(${family.category})`}
                </span>

                <Icon
                  className='todayAdd__family--icon'
                  name={selectedFamilyId === family.userId ? 'check-suggest' : 'check-none'}
                  width={50}
                  height={50}
                />
              </button>
            ))}
          </div>
        </div>
      </form>

      <BottomButton
        content={
          isSaving
            ? '저장 중...'
            : createdTask
              ? selectedFamilyId
                ? '담당 가족 지정하기'
                : '완료하기'
              : '추가하기'
        }
        disabled={isSaving || isLoading || !group || Boolean(setupError)}
        onClick={() => formRef.current.requestSubmit()}
      />
    </div>
  )
}

export default TodayAddPage
