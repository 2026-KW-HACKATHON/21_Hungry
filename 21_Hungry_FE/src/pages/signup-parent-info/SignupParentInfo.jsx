import { useState } from 'react'
import BottomButton from '../../components/bottom-button/BottomButton'
import LoginPageTitle from '../../components/loginPage-title/loginPage-title'
import './SignupParentInfo.css'

function SignupParentInfo() {
  const [relationship, setRelationship] = useState('father')
  const [name, setName] = useState('')
  const [age, setAge] = useState('')
  const isComplete = name.trim() !== '' && /^\d+$/.test(age) && Number(age) > 0

  return (
    <main className="SignupParentInfoPage">
      <LoginPageTitle title={<>부모님의 정보를<br />입력해 주세요</>} />
      <fieldset className="SignupParentInfo__relationship">
        <legend className="SignupParentInfo__label">부모님과의 관계</legend>
        {[
          { value: 'father', label: '아버지' },
          { value: 'mother', label: '어머니' },
        ].map((option) => (
          <label className="SignupParentInfo__option" key={option.value}>
            <input
              type="radio"
              name="parentRelationship"
              value={option.value}
              checked={relationship === option.value}
              onChange={(event) => setRelationship(event.target.value)}
            />
            <span>{option.label}</span>
          </label>
        ))}
      </fieldset>
      <input
        className="SignupParentInfo__input"
        type="text"
        aria-label="부모님 이름"
        placeholder="이름"
        value={name}
        onChange={(event) => setName(event.target.value)}
      />
      <input
        className="SignupParentInfo__input"
        type="text"
        inputMode="numeric"
        aria-label="부모님 나이"
        placeholder="나이"
        value={age}
        onChange={(event) => setAge(event.target.value.replace(/[^0-9]/g, ''))}
      />
      <div className="SignupParentInfo__footer" data-complete={isComplete}>
        <BottomButton content="다음" />
      </div>
    </main>
  )
}

export default SignupParentInfo
