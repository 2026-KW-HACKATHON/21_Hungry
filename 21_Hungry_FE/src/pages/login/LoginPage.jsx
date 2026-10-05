import { Icon } from '../../components/icon/Icon'
import {useState} from 'react'

function LoginPage() {
  const [selected, setSelected] = useState(null);
  return (
    <main className="SignupPage">
      <Icon name='back-button' width={11} height={19} />
      <h1 className="Signup__Title">어떤 분이 이 기기를<br />사용하시나요</h1>
      <div className="Signup__SelectUserType">
        {/* 주돌봄자녀 박스 */}
        <div className='Signup__UserTypeBox--MainUser'>
          <h1 className='Signup__UserTypeBox__Title'>주돌봄자녀</h1>
          <p className='Signup__UserTypeBox__Description'>가족 요청을 관리하고 부모님의 건강·생활 기록을 확인, 가족과 공유하는 기능이 포함되어 있어요.</p>
        </div>
        {/* 공동봄자녀 박스 */}
        <div className='Signup__UserTypeBox--SubUser' >
          <h1 className='Signup__UserTypeBox__Title'>공동돌봄자녀</h1>
          <p className='Signup__UserTypeBox__Description'>부모님의 건강·생활 기록을 확인,가족과 공유하는 기능이 포함되어 있어요.</p>
        </div>
        {/* 부모 박스 */}
        <div className='Signup__UserTypeBox--Parent'>
          <h1 className='Signup__UserTypeBox__Title'>부모</h1>
          <p className='Signup__UserTypeBox__Description'>자녀에게 안부와 건강기록을 전하고,공유 범위를 정하는 기능이 포함되어 있어요</p>
        </div>
      </div>
      {/* continue button */}

    </main>
  )
}

export default LoginPage
