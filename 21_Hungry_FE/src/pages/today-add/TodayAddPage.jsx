import './TodayAddPage.css'

import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import { Icon } from '../../components/icon/Icon'
import { categoryData, familyData, isValidTime, saveTodaySchedule } from '../../mocks/todayAddMock'

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

  const [selectedKind, setSelectedKind] = useState('OTHER')
  const [selectedFamilyId, setSelectedFamilyId] = useState(null)
  const [description, setDescription] = useState('')
  const [date, setDate] = useState('')
  const [time, setTime] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  const handleFamilySelect = (userId) => {
    setSelectedFamilyId((previous) => (previous === userId ? null : userId))
  }

  const handleAdd = async (event) => {
    event.preventDefault()
    if (savingRef.current) return

    const parsedDate = date
    dateRef.current.setCustomValidity(parsedDate ? '' : '날짜를 선택해 주세요.')
    timeRef.current.setCustomValidity(isValidTime(time) ? '' : '시간을 선택해 주세요.')

    if (!formRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)

    try {
      await saveTodaySchedule({
        kind: selectedKind,
        description: description.trim() || null,
        date: parsedDate,
        localTime: time.trim(),
        assigneeUserId: selectedFamilyId,
      })
      navigate('/today')
    } catch {
      window.alert('더미 일정을 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      savingRef.current = false
      setIsSaving(false)
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
              disabled={!date}
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
              disabled={!time}
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

        <div className='todayAdd__family'>
          <div className='todayAdd__family--title'>담당 가족</div>
          <div className='todayAdd__family--items' role='group' aria-label='담당 가족 선택'>
            {familyData.map((family) => (
              <button
                type='button'
                className='todayAdd__family--item'
                key={family.userId}
                aria-pressed={selectedFamilyId === family.userId}
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
        content='추가하기'
        disabled={isSaving}
        onClick={() => formRef.current.requestSubmit()}
      />
    </div>
  )
}

export default TodayAddPage
