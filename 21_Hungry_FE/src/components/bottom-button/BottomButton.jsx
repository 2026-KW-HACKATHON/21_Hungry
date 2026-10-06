import './BottomButton.css'

function BottomButton({ content, onClick }) {
  return (
    <div className='bottomButton__container'>
      <div className='bottomButton__button' onClick={onClick}>
        {content}
      </div>
    </div>
  )
}

export default BottomButton
