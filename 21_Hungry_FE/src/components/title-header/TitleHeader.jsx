import './TitleHeader.css'

function TitleHeader({ content, subcontent }) {
  return (
    <div className='titleHeader__container'>
      <div className='titleHeader__title'>{content}</div>
      <div className='titleHeader__subtitle'>{subcontent}</div>
    </div>
  )
}

export default TitleHeader
