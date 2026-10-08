import './TodayEditPage.css'

import { useRef, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'

import BackHeader from '../../components/back-header/BackHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import CardInfo from '../../components/card-info/CardInfo'
import { Icon } from '../../components/icon/Icon'
import {
  familyData,
  getTodaySchedule,
  isValidTime,
  toScheduleCard,
  updateTodaySchedule,
} from '../../mocks/todayAddMock'

function openNativePicker(event) {
  try {
    event.currentTarget.showPicker?.()
  } catch {
    event.currentTarget.focus()
  }
}

function TodayEditPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [schedule] = useState(() => getTodaySchedule(searchParams.get('id')))
  const [selectedFamilyId, setSelectedFamilyId] = useState(null)
  const [date, setDate] = useState(() => schedule?.date ?? '')
  const [time, setTime] = useState(() => schedule?.localTime ?? '')
  const [isSaving, setIsSaving] = useState(false)
  const dateRef = useRef(null)
  const timeRef = useRef(null)
  const savingRef = useRef(false)

  if (!schedule) return <Navigate to='/today' replace />

  const card = toScheduleCard(schedule)

  const handleSave = async () => {
    if (savingRef.current) return

    const parsedDate = date
    dateRef.current.setCustomValidity(parsedDate ? '' : '날짜를 선택해 주세요.')
    timeRef.current.setCustomValidity(isValidTime(time) ? '' : '시간을 선택해 주세요.')
    if (!dateRef.current.reportValidity() || !timeRef.current.reportValidity()) return

    savingRef.current = true
    setIsSaving(true)

    try {
      await updateTodaySchedule(schedule.id, {
        date: parsedDate,
        localTime: time.trim(),
        assigneeUserId: selectedFamilyId ?? schedule.assigneeUserId,
      })
      navigate('/today')
    } catch {
      window.alert('일정을 저장하지 못했어요. 다시 시도해 주세요.')
    } finally {
      savingRef.current = false
      setIsSaving(false)
    }
  }

  return (
    <div className='todayEdit__page'>
      <BackHeader
        content='돌봄 일정 수정'
        subcontent={`돌봄 일정의 시간을 수정하고\n담당자를 변경할 수 있어요`}
      />

      <div className='todayEdit__content'>
        <div className='todayEdit__card--container'>
          <CardInfo
            className='todayEdit__card'
            category={card.category}
            label1={'날짜'}
            label2={'시간'}
            label3={'담당 가족'}
            value1={card.date}
            value2={card.time}
            value3={card.family}
          />
        </div>

        <div className='todayEdit__description'>
          <div className='todayEdit__description--title'>상세 설명</div>
          <div className='todayEdit__description--description'>{card.description}</div>
        </div>

        <div className='todayEdit__notice'>
          상세 설명에는 장소와 특정 행동을 입력해주는 것이 좋아요
        </div>

        <div className='todayEdit__inputs'>
          <div className='todayEdit__input'>
            <input
              ref={dateRef}
              className='todayEdit__input--input'
              type='date'
              min='0001-01-01'
              max='9999-12-31'
              onClick={openNativePicker}
              aria-label='날짜'
              required
              value={date}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setDate(event.target.value)
              }}
            />
            <button
              type='button'
              aria-label='날짜 지우기'
              disabled={!date}
              onClick={() => {
                setDate('')
                dateRef.current.setCustomValidity('')
                dateRef.current.focus()
              }}
            >
              <Icon name='input-cancel' width={24} height={24} />
            </button>
          </div>

          <div className='todayEdit__input'>
            <input
              ref={timeRef}
              className='todayEdit__input--input'
              type='time'
              step={60}
              onClick={openNativePicker}
              aria-label='시간'
              required
              value={time}
              onChange={(event) => {
                event.target.setCustomValidity('')
                setTime(event.target.value)
              }}
            />
            <button
              type='button'
              aria-label='시간 지우기'
              disabled={!time}
              onClick={() => {
                setTime('')
                timeRef.current.setCustomValidity('')
                timeRef.current.focus()
              }}
            >
              <Icon name='input-cancel' width={24} height={24} />
            </button>
          </div>
        </div>

        <div className='todayEdit__notice'>시간은 24시간제로 입력해 주세요</div>

        <div className='todayEdit__family'>
          <div className='todayEdit__family--title'>담당 가족</div>
          <div className='todayEdit__family--items'>
            {familyData.map((family) => {
              const checkboxIcon =
                selectedFamilyId === family.userId
                  ? 'check-suggest'
                  : schedule.assigneeUserId === family.userId
                    ? 'check-confirm'
                    : 'check-none'

              return (
                <button
                  type='button'
                  className='todayEdit__family--item'
                  key={family.userId}
                  aria-pressed={selectedFamilyId === family.userId}
                  onClick={() => setSelectedFamilyId(family.userId)}
                >
                  <span className='todayEdit__family--family'>
                    {family.name}
                    {family.category !== null && `(${family.category})`}
                  </span>
                  <Icon name={checkboxIcon} width={50} height={50} />
                </button>
              )
            })}
          </div>

          {selectedFamilyId !== null && (
            <div className='todayEdit__button'>
              <PopupButton content='이 가족에게 수행 요청하기' color='green' />
            </div>
          )}
        </div>

        <div className='todayEdit__notice'>
          지정하고 싶은 가족을 클릭하면 수행 요청을 보낼 수 있어요
        </div>
      </div>

      <BottomButton content='변경사항 저장하기' onClick={handleSave} disabled={isSaving} />
    </div>
  )
}

export default TodayEditPage
