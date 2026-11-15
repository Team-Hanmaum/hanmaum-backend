# AI 제안 검토 및 소유자 반영

모든 경로는 `/api/spaces/{spaceId}` 아래다. POST에는 로그인 세션·CSRF가 필요하다.

| 요청 | 권한 | 성공 |
| --- | --- | --- |
| POST `/proposal-applications` | 소유자 | `200`과 묶음 ID·결과 항목·버전 |
| POST `/proposal-declines` | 소유자 | `200`과 미적용 결정 정보 |
| POST `/items/{itemId}/edit-lock` | 현재 참여자 | `201`과 잠금·토큰 |
| POST `/items/{itemId}/edit-lock/renewals` | 본인 잠금 | `200`과 갱신 정보 |
| POST `/items/{itemId}/edit-lock/releases` | 본인 잠금 | `204` |

## 일괄 반영

`clientRequestId`와 `entries` 1~20개를 보낸다. 각 항목은 `proposalId`, `action`, 상세의 `reviewToken`이 필요하다. 기존 항목 작업에는 `targetItemId`, `expectedItemVersion`, `leaseToken`을 함께 보낸다. 원래 제안의 작업·유형을 유지하며 미확정 대상은 유효한 후보에서 선택한다.

| action | values |
| --- | --- |
| `CREATE_ITEM` | `{itemType, title, description?, fields}` |
| `UPDATE_ITEM` | `{title?, description?, fields?, status?}` |
| `RESOLVE_QUESTION` | `{confirmedAnswer}` |
| `LINK_EVIDENCE` | 생략 또는 null |

생략한 변경 필드는 유지하고 null 가능 필드의 명시적 null은 값을 지운다. 날짜는 `YYYY-MM-DD`, 시각은 `HH:mm`이다. 모르는 시간이나 대상을 임의로 채우지 않는다. 상태 전이는 [항목 직접 변경](direct-care-items.md#기존-항목-변경)을 따른다.

전체 선택을 먼저 검증하고 항목·이력·제안 결정·성공 기록을 함께 저장한다. 중복 대상 또는 충돌이 있으면 전체 미반영이다. 원시 본문은 1 MiB, 자유 문자열은 각각 10,000 UTF-16 코드 단위, 새로 저장되는 최종 문서 합계는 512 KiB다.

## 일괄 미적용

`clientRequestId`와 각 `{proposalId, reviewToken}`을 보낸다. 유효한 미처리 제안을 `DECLINED`로 기록하며 확정 항목은 유지한다.

## 잠금·검토·트랜잭션

- 잠금 획득은 `expectedItemVersion`, 갱신·해제는 본인의 `leaseToken`을 사용한다. 유효 시간 기본 2분, 갱신 권장 30초다. `RECONFIRMATION`·`DELETION` 목적은 해당 쓰기에만 사용한다.
- 다른 사용자 잠금은 `409 ITEM_LOCKED`, 본인 잠금 만료나 토큰 불일치는 `409 EDIT_LOCK_EXPIRED`다. 버전·검토 충돌이면 최신 항목·제안을 조회해 검토한다.
- 같은 요청의 재전송은 현재 소유권과 원래 참여를 확인하고 최초 결과 메타데이터를 돌려준다. 검토·잠금 토큰은 업무 내용 비교에서 제외한다.

## 조회와 삭제 전파

확정 값은 [항목 이력](care-item-provenance.md)에서 조회한다. 원본 삭제 경로는 영향 범위의 분석·제안·파생 문서·근거를 한 트랜잭션으로 정리한다. 삭제된 내용은 이전 결정이나 재전송에서 복원하지 않는다. [소식 원본 삭제](api-conventions.md#소식-원본-삭제-api)
