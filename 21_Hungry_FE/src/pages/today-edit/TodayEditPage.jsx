import './TodayEditPage.css'

import { useState } from 'react'

import BackHeader from '../../components/back-header/backHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import CardInfo from '../../components/card-info/CardInfo'
import { Icon } from '../../components/icon/Icon'

function TodayEditPage() {
  const [selectedFamilyId, setSelectedFamilyId] = useState(null)

  const schedule = {
    id: 1,
    category: '기타 돌봄',
    date: '2024년 06월 01일',
    time: '10:00',
    family: '가족1',
    description: '돌봄 일정 1',
  }

  const family = [
    { id: 1, name: '부모1', category: '어머니', role: true },
    { id: 2, name: '자녀1', category: '나', role: false },
    { id: 3, name: '자녀2', category: null, role: false },
  ]

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
            category={schedule.category}
            label1={'날짜'}
            label2={'시간'}
            label3={'담당 가족'}
            value1={schedule.date}
            value2={schedule.time}
            value3={schedule.family}
          />
        </div>

        <div className='todayEdit__description'>
          <div className='todayEdit__description--title'>상세 설명</div>
          <div className='todayEdit__description--description'>{schedule.description}</div>
        </div>

        <div className='todayEdit__notice'>
          상세 설명에는 장소와 특정 행동을 입력해주는 것이 좋아요
        </div>

        <div className='todayEdit__inputs'>
          <div className='todayEdit__input'>
            <input className='todayEdit__input--input' placeholder='날짜' />
            <Icon name='input-cancel' width={24} height={24} />
          </div>

          <div className='todayEdit__input'>
            <input className='todayEdit__input--input' placeholder='시간' />
            <Icon name='input-cancel' width={24} height={24} />
          </div>
        </div>

        <div className='todayEdit__notice'>시간은 24시간제로 입력해 주세요</div>

        <div className='todayEdit__family'>
          <div className='todayEdit__family--title'>담당 가족</div>

          <div className='todayEdit__family--items'>
            {family.map((family) => {
              const checkboxIcon =
                selectedFamilyId === family.id
                  ? 'check-suggest'
                  : family.role
                    ? 'check-confirm'
                    : 'check-none'

              return (
                <div
                  className='todayEdit__family--item'
                  key={family.id}
                  onClick={() => setSelectedFamilyId(family.id)}
                >
                  <div className='todayEdit__family--family'>
                    {family.name}
                    {family.category !== null && `(${family.category})`}
                  </div>

                  <Icon name={checkboxIcon} width={50} height={50} />
                </div>
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

      <BottomButton content={'변경사항 저장하기'} />
    </div>
  )
}

export default TodayEditPage
