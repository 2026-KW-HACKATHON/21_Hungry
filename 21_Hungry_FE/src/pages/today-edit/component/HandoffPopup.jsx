import './HandoffPopup.css'

import { useEffect, useRef } from 'react'
import CardInfo from '../../../components/card-info/CardInfo'
import PopupButton from '../../../components/popup-button/PopupButton'

function HandoffPopup({ card, onConfirm }) {
  const dialogRef = useRef(null)

  useEffect(() => {
    const dialog = dialogRef.current
    dialog.showModal()

    return () => {
      dialog.close()
    }
  }, [])

  return (
    <dialog
      ref={dialogRef}
      className='handoffPopup'
      aria-labelledby='handoffPopup-title'
      aria-describedby='handoffPopup-description'
      onCancel={(event) => {
        event.preventDefault()
        onConfirm()
      }}
    >
      <div className='handoffPopup--content'>
        <h2 id='handoffPopup-title'>
          다른 가족들에게
          <br />
          아래의 일정을 인계했어요
        </h2>

        <p id='handoffPopup-description'>가족들은 이 일정을 직접 맡을지 선택할 수 있어요.</p>

        <CardInfo
          category={card.category}
          label1='날짜'
          value1={card.date}
          label2='시간'
          value2={card.time}
          label3='이전 담당 가족'
          value3={card.family}
        />

        <div className='handoffPopup__description'>
          <div className='handoffPopup__description--title'>상세 설명</div>

          <div className='handoffPopup__description--text'>{card.description || '-'}</div>
        </div>

        <PopupButton content='확인했어요' color='blue' onClick={onConfirm} />
      </div>
    </dialog>
  )
}

export default HandoffPopup
