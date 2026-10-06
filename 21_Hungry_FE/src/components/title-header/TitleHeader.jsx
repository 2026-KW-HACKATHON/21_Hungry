import './TitleHeader.css'

function TitleHeader({ content, subcontent }) {
  return (
    <div className='titleHeader__container'>
      <p className='titleHeader__title'>{content}</p>
      <p className='titleHeader__subtitle'>{subcontent}</p>
    </div>
  )
}

export default TitleHeader
