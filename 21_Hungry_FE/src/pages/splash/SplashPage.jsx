import './SplashPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PopupButton from '../../components/popup-button/PopupButton'
import body from '../../assets/splash/character-body.svg'
import front from '../../assets/splash/eye-front.svg'
import upLeft from '../../assets/splash/eye-up-left.svg'
import downRight from '../../assets/splash/eye-down-right.svg'
import mouth from '../../assets/splash/mouth.svg'

const assets = [body, front, upLeft, downRight, mouth]
const eyes = [
  { src: front, direction: 'front' },
  { src: upLeft, direction: 'up-left' },
  { src: downRight, direction: 'down-right' },
]

function SplashPage() {
  const navigate = useNavigate()
  const pageRef = useRef(null)
  const [scale, setScale] = useState(0)
  const [loaded, setLoaded] = useState(false)
  const [finished, setFinished] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => {
      setScale(entry.contentRect.width / 402)
    })
    observer.observe(pageRef.current)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    let cancelled = false
    Promise.all(
      assets.map((src) => {
        const image = new Image()
        image.src = src
        return image.decode()
      }),
    ).then(
      () => {
        if (!cancelled) setLoaded(true)
      },
      () => {
        if (!cancelled) setLoadError(true)
      },
    )
    return () => {
      cancelled = true
    }
  }, [attempt])

  const ready = loaded && scale > 0

  const retry = () => {
    setLoadError(false)
    setAttempt((previous) => previous + 1)
  }

  return (
    <main
      ref={pageRef}
      className={`splash${ready ? ' splash--ready' : ''}`}
      aria-label='KnowOne 시작 화면'
      aria-busy={!ready && !loadError}
    >
      <div className='splash__art-position' aria-hidden='true'>
        <div className='splash__art' style={{ transform: `scale(${scale})` }}>
          <div
            className='splash__body'
            onAnimationEnd={(event) => {
              if (event.target === event.currentTarget) setFinished(true)
            }}
          >
            <img src={body} alt='' draggable={false} />
          </div>
          {['left', 'right'].map((side) => (
            <div className={`splash__eye splash__eye--${side}`} key={side}>
              {eyes.map((eye) => (
                <img
                  key={eye.direction}
                  className={`splash__gaze splash__gaze--${eye.direction}`}
                  src={eye.src}
                  alt=''
                  draggable={false}
                />
              ))}
            </div>
          ))}
          <div className='splash__mouth'>
            <img src={mouth} alt='' draggable={false} />
          </div>
        </div>
      </div>

      <h1 className='splash__title' aria-hidden={!finished}>
        부모님 부양 걱정,
        <br />
        KnowOne이 덜어드릴게요
      </h1>

      <footer className='splash__footer' aria-hidden={!finished}>
        <PopupButton
          content='KnowOne 시작하기'
          color='blue'
          disabled={!finished}
          onClick={() => navigate('/loginselect')}
        />
        <p className='splash__privacy'>건강 정보는 가족에게만 공유돼요</p>
      </footer>

      {loadError && (
        <div className='splash__error' role='alert'>
          <p>화면을 불러오지 못했어요.</p>
          <PopupButton content='다시 불러오기' color='blue' onClick={retry} />
        </div>
      )}
    </main>
  )
}

export default SplashPage
