import './PopupButton.css'

/*color options : green, blue, red, gray, notice */

function PopupButton({ content, onClick, color }) {
  return (
    <button className={`popupButton popupButton--${color}`} onClick={onClick}>
      {content}
    </button>
  )
}

export default PopupButton
