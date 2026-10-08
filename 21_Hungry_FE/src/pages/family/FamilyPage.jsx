import './FamilyPage.css'

import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import TitleHeader from '../../components/title-header/TitleHeader'
import BottomButton from '../../components/bottom-button/BottomButton'
import PopupButton from '../../components/popup-button/PopupButton'
import { getAccessToken, messageOf } from '../../api/http'
import { getTodayAddContext as getFamilyContext } from '../../api/todayAddApi'
import { changeFamilyPriority, getFamilyMembers } from '../../api/familyApi'
import { getParentName } from '../today/todayUtils'

function FamilyPage() {
  const navigate = useNavigate()

  const [result, setResult] = useState(null)
  const [retry, setRetry] = useState(0)
  const [savingMemberId, setSavingMemberId] = useState('')
  const [saveError, setSaveError] = useState('')

  const operationRef = useRef(null)
  const requestKeys = useRef(new Map())

  const current = result?.key === retry ? result : null
  const context = current?.data
  const group = context?.group
  const members = context?.members || []
  const error = current?.error || ''

  const isLoading = !current
  const isSaving = Boolean(savingMemberId)

  const myMember = members.find((member) => member.user.id === context?.user.id)

  const canViewRequests = myMember?.role === 'CAREGIVER' && myMember.priority === 1

  const relationship = {
    MOTHER: '어머니',
    FATHER: '아버지',
  }[group?.parentProfile?.relation]

  const displayMembers = [...members].sort((a, b) => {
    const rank = (member) =>
      member.role === 'RECIPIENT' ? 0 : member.user.id === context?.user.id ? 1 : 2

    return rank(a) - rank(b)
  })

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/loginselect', { replace: true })
      return
    }

    const controller = new AbortController()

    getFamilyContext({ signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) {
          setResult({ key: retry, data, error: '' })
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setResult({
            key: retry,
            data: null,
            error: error.message || messageOf(error),
          })
        }
      })

    return () => {
      controller.abort()
      operationRef.current?.abort()
    }
  }, [navigate, retry])

  const reload = () => {
    setSaveError('')
    setRetry((value) => value + 1)
  }

  const changePriority = async (member, priority) => {
    if (!group || operationRef.current || member.priority === priority) {
      return
    }

    const controller = new AbortController()
    operationRef.current = controller

    setSavingMemberId(member.id)
    setSaveError('')

    const signature = JSON.stringify([group.id, member.id, member.version, priority])

    if (!requestKeys.current.has(signature)) {
      requestKeys.current.set(signature, crypto.randomUUID())
    }

    const replaceMembers = (updated, merge = false) => {
      if (controller.signal.aborted) return

      setResult((previous) => {
        if (previous?.key !== retry || previous?.data?.group?.id !== group.id) {
          return previous
        }

        const nextMembers = merge
          ? previous.data.members.map((item) => updated.find((next) => next.id === item.id) || item)
          : updated

        return {
          ...previous,
          data: {
            ...previous.data,
            members: nextMembers,
          },
        }
      })
    }

    let saved = false

    try {
      const updated = await changeFamilyPriority(
        group.id,
        member,
        priority,
        requestKeys.current.get(signature),
        { signal: controller.signal },
      )

      saved = true
      replaceMembers(updated, true)

      const latestMembers = await getFamilyMembers(group.id, {
        signal: controller.signal,
      })

      replaceMembers(latestMembers)
    } catch (error) {
      if (controller.signal.aborted) return

      const message =
        error.apiCode === 'LAST_PRIMARY_CAREGIVER'
          ? '주돌봄자녀는 최소 한 명이어야 해요. 다른 가족을 주돌봄자녀로 변경한 뒤 다시 선택해 주세요.'
          : error.message || messageOf(error)

      setSaveError(saved ? `역할은 저장됐지만 최신 목록을 불러오지 못했어요. ${message}` : message)

      if (!saved && error.status === 409) {
        try {
          const latestMembers = await getFamilyMembers(group.id, {
            signal: controller.signal,
          })

          replaceMembers(latestMembers)
        } catch {
          // 조회 실패 시 현재 목록을 유지한다.
        }
      }
    } finally {
      if (!controller.signal.aborted) {
        setSavingMemberId('')
      }

      if (operationRef.current === controller) {
        operationRef.current = null
      }
    }
  }

  return (
    <div className='family__page'>
      <TitleHeader
        content='가족 관리'
        subcontent={'AI가 개인 일정을 분석해\n돌봄 일정마다 담당자를 정해드려요'}
      />

      <div className='family__content'>
        {isLoading && (
          <p role='status' className='family__status'>
            가족 정보를 불러오고 있어요.
          </p>
        )}

        {(error || saveError) && (
          <div role='alert' className='family__error'>
            <p>{error || saveError}</p>

            <PopupButton
              content='다시 불러오기'
              color='gray'
              disabled={isSaving}
              onClick={reload}
            />
          </div>
        )}

        {context && !group && (
          <p className='family__status'>가족 연결을 완료하면 가족 정보를 확인할 수 있어요.</p>
        )}

        {displayMembers.map((member) => {
          const isMe = member.user.id === context.user.id
          const isParent = member.role === 'RECIPIENT'

          const name = isParent ? getParentName(group) : member.user.displayName || '-'

          const roleLabel = isParent
            ? '부모'
            : {
                1: '주돌봄자녀',
                2: '공동돌봄자녀',
              }[member.priority] || '-'

          return (
            <div
              className={`family__card${isParent ? ' family__card--parent' : ''}`}
              key={member.id}
            >
              <div className='family__card--info'>
                <div className='family__card--name'>
                  {name}
                  {isMe ? '(나)' : isParent && relationship ? `(${relationship})` : null}
                </div>

                <div className='family__card--role'>{roleLabel}</div>
              </div>

              {member.role === 'CAREGIVER' && (
                <div className='family__priorities' role='group' aria-label={`${name} 돌봄 역할`}>
                  {[1, 2].map((priority) => (
                    <PopupButton
                      key={priority}
                      content={priority === 1 ? '주돌봄자녀' : '공동돌봄자녀'}
                      color='gray'
                      aria-pressed={member.priority === priority}
                      disabled={isSaving}
                      onClick={() => changePriority(member, priority)}
                    />
                  ))}
                </div>
              )}

              {!isMe && !isParent && (
                <div className='family__card--button'>
                  <PopupButton
                    content='개인 일정 조회하기'
                    color='green'
                    onClick={() =>
                      navigate(`/familyschedule?userId=${encodeURIComponent(member.user.id)}`)
                    }
                  />
                </div>
              )}

              {savingMemberId === member.id && (
                <p role='status' className='family__status'>
                  역할을 저장하고 있어요.
                </p>
              )}
            </div>
          )
        })}

        {group && (
          <p className='family__guide'>
            주돌봄자녀는 돌봄 할당 1순위예요. 필요하면 순서를 바꿀 수 있고, 일정마다 직접 다른
            담당자를 선택할 수도 있어요.
          </p>
        )}

        {group && !canViewRequests && (
          <p className='family__status'>가족 참여 요청은 주돌봄자녀만 확인할 수 있어요.</p>
        )}
      </div>

      <BottomButton
        content='가족 참여 요청 보기'
        disabled={isLoading || isSaving || Boolean(error) || !group || !canViewRequests}
        onClick={() => navigate('/familyreq')}
      />
    </div>
  )
}

export default FamilyPage
