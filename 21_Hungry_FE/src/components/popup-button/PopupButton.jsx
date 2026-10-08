import './PopupButton.css'

/*color options : green, blue, red, gray, notice */

function PopupButton({ content, onClick, color, disabled = false, ...props }) {
  return (
    <button
      type='button'
      className={`popupButton popupButton--${color}`}
      onClick={onClick}
      disabled={disabled}
      {...props}
    >
      {content}
    </button>
  )
}

export default PopupButton
