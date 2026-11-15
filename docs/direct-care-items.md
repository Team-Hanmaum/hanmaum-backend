# 관리 항목 직접 등록·수정·삭제

모든 경로는 `/api/spaces/{spaceId}` 아래다. 쓰기에는 세션·CSRF·현재 참여가 필요하다.

| 요청 | 허용 범위 |
| --- | --- |
| POST `/items` | 소유자는 일정·할 일·질문, 구성원은 자신을 담당자로 지정한 할 일 등록 |
| PATCH `/items/{itemId}` | 소유자가 기존 항목의 내용 변경 |
| PATCH `/items/{itemId}/status` | 소유자는 일정·할 일, 구성원은 본인 담당 할 일의 상태 변경 |
| POST `/items/{itemId}/deletions` | 소유자가 기존 항목 삭제 |
| POST `/change-requests` | 구성원이 직접 반영할 권한이 없는 변경을 제출 |

## 생성

`clientRequestId`, `itemType`, `title`, `fields`와 선택 항목 `description`을 보낸다. 질문은 `fields: {}`를 사용한다. 초기 상태는 일정 `SCHEDULED`, 할 일 `NOT_STARTED`, 질문 `UNCONFIRMED`다. 관찰·안내는 AI 제안을 통해 생성한다.

## 기존 항목 변경

`clientRequestId`, `expectedItemVersion`, `leaseToken`과 `values`를 보낸다. 상태 전용 PATCH는 `values` 대신 최상위 `status`를 사용한다. 생략한 값은 유지하고 null 허용 필드를 명시적으로 null로 보내면 지운다. [편집 잠금](proposal-decisions.md#잠금검토트랜잭션)

| 유형 | 상태 전이 |
| --- | --- |
| 일정 | `SCHEDULED → COMPLETED / CANCELED` |
| 할 일 | `NOT_STARTED → IN_PROGRESS / COMPLETED`, `IN_PROGRESS → COMPLETED`, `COMPLETED → IN_PROGRESS` |

## 구성원 직접 입력 요청

[변경 요청](member-change-requests.md)에서 `sourceType: "DIRECT_INPUT"`과 `action`(`CREATE_ITEM`, `UPDATE_ITEM`, `DELETE_ITEM`)을 보낸다. 기존 항목은 대상 ID·버전·본인 잠금이 필요하다. 제출은 승인 대기만 기록하며, 승인할 때 소유자가 새 잠금을 얻는다.

## 일반 삭제

`expectedItemVersion`, `leaseToken`, `clientRequestId`를 보낸다. 재확인 대상 또는 내용이 제거된 항목은 `DELETION` 목적의 잠금을 사용한다. 구성원의 `DELETE_ITEM` 요청은 `values`가 없거나 null이어야 한다.

성공은 `200`과 `{itemId, version, visibility: "DELETED", deletedAt}`이다. 일반 목록·현황판·분석 후보에서 제외하고 원본 소식·기존 근거·이력은 보존한다. 같은 요청 ID로 재전송하면 현재 권한을 확인해 최초 결과를 반환한다. 새 ID로 재삭제하면 `409 ITEM_STATE_CONFLICT`다.

버전과 잠금 충돌은 최신 값을 조회한 뒤 다시 검토한다. 자세한 필드·오류는 [공개 API 규격](api-conventions.md)과 Swagger를 참고한다.
