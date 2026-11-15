# 구성원 변경 요청 및 소유자 검토

경로는 `/api/spaces/{spaceId}` 아래다. 로그인한 현재 참여자만 조회하며 POST에는 CSRF가 필요하다.

| 요청 | 권한 | 성공 |
| --- | --- | --- |
| POST `/change-requests` | 구성원 | `201`과 `requestId`·요청 항목 |
| GET `/change-requests`, `/change-requests/{requestId}` | 현재 참여자 | `200`과 목록 또는 상세 |
| POST `/request-applications` | 소유자 | `200`과 결정 묶음·결과 |
| POST `/request-declines` | 소유자 | `200`과 미적용 결정 |
| POST `/change-requests/{requestId}/cancellations` | 원래 제출자·같은 활성 참여 | `200`과 취소 결정 |

## 요청 제출

`clientRequestId`와 `entries` 1~20개를 보낸다. AI 제안은 `proposalId`, `action`, 제안 `reviewToken`을 사용한다. 직접 입력은 `sourceType: "DIRECT_INPUT"`으로 제출한다. 기존 항목의 대상·버전·본인 잠금을 확인하고 성공 시 제출자 잠금을 해제한다. 제출은 `PENDING` 기록만 만들며 확정 항목을 바꾸지 않는다.

[AI 제안 값](proposal-decisions.md#일괄-반영) · [항목 직접 입력](direct-care-items.md#구성원-직접-입력-요청) · [공간 수정 요청](care-space-information.md)

## 목록·상세

목록은 `status`, `cursor`, `size`(기본 20, 최대 50)를 사용한다. 상태는 항목별 `PENDING`, `APPLIED`, `DECLINED`, `CANCELED`, `INVALIDATED`다. 상세는 요청값, 현재 값, 근거, 결정과 제거된 필드를 보여 준다. 유효한 대기 요청의 소유자에게만 결정용 `reviewToken`을 제공한다.

## 소유자 반영

`requestEntryId`, 요청 상세의 `reviewToken`, 대상의 버전과 필요한 본인 잠금을 보낸다. `values` 생략 시 제출값을 쓰고 제공 시 해당 작업의 입력 전체를 대체한다. 서버는 현재 소유권·원래 제출자의 참여·대상 버전을 재검사한다. 선택 묶음에 충돌이 있으면 전부 미반영이다. 반영된 요청과 연결된 AI 제안은 `APPLIED`로 표시한다.

미적용과 취소는 확정 항목을 바꾸지 않는다. 미적용에는 소유자 토큰을 보내고 취소에는 해당 묶음의 `requestEntryId`만 보낸다. 작업 내용이 바뀌면 새 `clientRequestId`를 사용한다.

## 크기·재전송·내용 제거

POST 원시 본문은 1 MiB, 자유 문자열은 각각 10,000 UTF-16 코드 단위, 요청 문서와 새 최종 문서의 묶음 합계는 각각 512 KiB다. 같은 요청은 최초 결과를 재사용하고, 원본 삭제 후에는 이미 제거된 요청값·반영값·근거를 복원하지 않는다. [공개 오류 규격](api-conventions.md#오류-처리)
