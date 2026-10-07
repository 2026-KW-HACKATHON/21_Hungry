import './BottomButton.css'

function BottomButton({ content, onClick, disabled = false }) {
  return (
    <div className='bottomButton__container'>
      <button type='button' className='bottomButton__button' onClick={onClick} disabled={disabled}>
        {content}
      </button>
    </div>
  )
}

export default BottomButton
