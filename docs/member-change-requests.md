# 구성원 AI 제안 변경 요청 및 소유자 검토 (#35)

2026-10-23 사용자 승인 8개 정책 기준. AI 제안의 요청 제출·조회·취소와 소유자 검토·반영을 지원한다. 기존 [소유자 직접 반영](proposal-decisions.md)의 항목 검증·편집 잠금·최종 저장 규칙을 공유한다. BE↔AI v1/v2 및 공통 fixture는 변경하지 않고 AI 서버도 호출하지 않는다.

## 공개 API

모든 경로 앞에 `/api/spaces/{spaceId}`를 붙인다. 공통 JSON 응답과 `Cache-Control: no-store`를 사용한다. 모든 요청은 로그인 세션, POST는 CSRF·JSON 본문이 필요하다.

| Method / 경로 | 권한 | 성공 |
| --- | --- | --- |
| POST `/change-requests` | 현재 MEMBER | 201 `{requestId, submittedAt, entries:[{requestEntryId,status}]}` |
| GET `/change-requests` | 현재 참여자 | 200 `{items,nextCursor,hasNext}` |
| GET `/change-requests/{requestId}` | 현재 참여자 | 200 요청 내용·현재 값·근거·처리 결과 |
| POST `/request-applications` | 현재 OWNER | 200 `{batchId,appliedAt,results:[{requestEntryId,itemId,itemVersion}]}` |
| POST `/request-declines` | 현재 OWNER | 200 `{batchId,decidedAt,declinedRequestEntryIds}` |
| POST `/change-requests/{requestId}/cancellations` | 원래 제출자·같은 활성 참여 | 200 `{batchId,decidedAt,canceledRequestEntryIds}` |

`requestId`는 서버가 만드는 제출 묶음 ID, `requestEntryId`는 묶음 안의 항목 ID다. FE가 만드는 `clientRequestId`는 각 변경 API의 중복 처리 방지 ID이며 이 둘과 다르다.

## 요청 제출

```json
{
  "clientRequestId": "832cbe35-9970-4f99-a219-8f019315b3ab",
  "entries": [
    {
      "proposalId": "2f4fd7af-5b2f-4d38-b1ed-8ea1141db910",
      "action": "CREATE_ITEM",
      "reviewToken": "<구성원 제안 상세에서 받은 값>",
      "values": {
        "itemType": "SCHEDULE",
        "title": "진료 일정",
        "description": null,
        "fields": {"scheduledDate": "2026-10-25", "scheduledTime": null, "location": null}
      }
    }
  ]
}
```

- `clientRequestId`, `entries` 및 각 entry의 `proposalId`, `action`, `reviewToken`은 필수다. AI 제안 없이 직접 등록·수정하는 요청은 지원하지 않는다.
- 허용 작업은 `CREATE_ITEM`, `UPDATE_ITEM`, `RESOLVE_QUESTION`, `LINK_EVIDENCE`다. 원래 AI 제안의 작업·유형을 바꾸지 않는다. 대상이 모호하면 제안 후보 안에서만 선택하고, `TARGET_NOT_FOUND`를 임의 대상이나 생성 요청으로 바꾸지 않는다.
- `values`의 허용 필드·null/생략·날짜·상태 전이는 [소유자 반영 계약](proposal-decisions.md#일괄-반영)과 같다. CREATE는 AI 제안의 다섯 유형을 지원한다. RESOLVE는 `confirmedAnswer`, LINK는 `values` 생략 또는 null이다.
- 기존 항목 작업은 `targetItemId`, `expectedItemVersion`(양의 정수 문자열), 본인 `leaseToken`을 전달한다. CREATE에서는 이 세 필드를 생략 또는 null로 둔다.
- 한 묶음은 1~20개다. 같은 제안 ID나 같은 기존 대상 항목을 두 번 선택하지 못한다. 전체 검증 후 요청을 함께 저장하며 일부만 제출하지 않는다.
- 제출은 `PENDING` 기록만 만들고 현재 항목·항목 버전·AI 제안의 OPEN 상태를 바꾸지 않는다. 기존 항목의 제출자 잠금은 성공 시 해제하고 실패 시 유지한다. 승인을 기다리는 동안 항목을 장시간 잠그지 않는다.
- 같은 참여자와 같은 제안의 **유효한 PENDING 요청**은 하나만 허용한다. 다른 참여자는 다른 수정안을 제출할 수 있다. 제출 후 내용은 불변이며 수정은 취소 후 새 `clientRequestId`로 재제출한다.

## 목록·상세

목록은 `status`, `cursor`, `size`를 받는다. 기본 상태 PENDING, 기본 20개·최대 50개이며 제출 시각·요청 ID 내림차순이다. 필터는 해당 상태의 entry가 있는 제출 묶음을 선택하고, 그 묶음의 전체 entry 상태 및 counts를 반환한다. cursor는 같은 공간·상태에서 사용하며 권한이나 고정 스냅샷을 대신하지 않는다. 끝 페이지는 `nextCursor:null`, `hasNext:false`다.

각 묶음은 `requestId`, `submittedBy:{membershipId,displayName,withdrawn}`, `submittedAt`, `counts:{pending,applied,declined,canceled,invalidated}`, `entries`를 반환한다. 상태는 묶음 전체가 아니라 entry별로 관리한다.

| 상태 | 의미 |
| --- | --- |
| PENDING | 유효한 검토 대기 |
| APPLIED | 소유자 반영 완료 |
| DECLINED | 소유자가 요청 미적용 |
| CANCELED | 원래 제출자가 요청 취소 |
| INVALIDATED | 미처리 요청의 제출자 참여·제안·대상·문서 등이 무효여서 반영 불가 |

상세 entry에는 작업·제안·대상 ID, `expectedItemVersion`, `currentItemVersion`, `requestedValues`, `currentValues`, `evidenceIds`, `reviewToken`, `invalidReason`, `decision`, `redactedFields`를 제공한다. 현재 소유자의 유효 PENDING에만 요청 검토 토큰을 발급한다. 구성원의 제안 검토 토큰과 다른 토큰이며 원래 토큰을 보관했다가 소유자 승인에 재사용하지 않는다.

`decision`은 미결정이면 null, 결정 후에는 `batchId`, `decidedByMembershipId`, `decidedAt`, `itemId`, `itemVersion`, `appliedValues`다. 미적용·취소는 항목과 반영 값이 null이다. 원래 `requestedValues`와 최종 `appliedValues`는 분리한다. 생성·수정·답변 확정의 `appliedValues`는 소유자 반영 시 저장한 최종 문서 값이며, UPDATE의 유지된 값도 포함한다. 조회로 DB 상태를 바꾸지 않는다.

`LINK_EVIDENCE`는 내용 변경 없이 근거만 연결하므로 `appliedValues`는 항상 null이다. 항목 ID·버전·처리 정보는 유지하고 기존 항목 내용은 `currentValues`에서 확인한다. 이 null은 삭제된 값이 아니므로 `redactedFields`에 `decision.appliedValues`를 추가하지 않는다. 내부 `result_document_id`는 이력의 항목 문서 참조이며, 근거 연결 결정의 입력값을 의미하지 않는다.

## 소유자 반영

```json
{
  "clientRequestId": "f692065f-05e1-4b42-bf19-672ded107e46",
  "entries": [
    {
      "requestEntryId": "7045c73c-e589-40c6-b4b4-e7baa073f01a",
      "reviewToken": "<소유자 요청 상세에서 받은 값>",
      "expectedItemVersion": "3",
      "leaseToken": "<소유자가 새로 획득한 잠금>",
      "values": {"fields": {"scheduledTime": "11:00"}}
    }
  ]
}
```

- `values`를 생략하면 저장된 `requestedValues`를 사용한다. 전달하면 해당 동작의 입력 전체를 대체하고, 원래 requestedValues는 그대로 보존한다. UPDATE의 생략 필드는 현재 항목 값을 유지한다. 요청값과 소유자 입력을 암묵적으로 합치지 않는다.
- 소유자는 허용된 최종 값만 수정할 수 있다. 요청의 작업·유형·대상·출처를 바꾸거나 삭제 근거를 다시 넣을 수 없다.
- 기존 항목은 제출 당시 버전과 현재 버전이 같아야 한다. 버전이 달라지면 기존 요청을 무효로 보고 반영하지 않는다. FE가 버전만 최신으로 바꾸거나 새 토큰만 받아 자동 재시도하지 않는다. 원래 AI 제안도 무효이면 유효한 새 제안을 검토해야 한다.
- 현재 소유자·원래 제출자의 활성 참여·요청/제안 상태·근거·버전·소유자 본인 잠금을 처리 직전에 검사한다. 소유권 이전은 새 소유자가 검토하며, 요청자의 기존 참여가 종료되면 재가입해도 요청이 부활하지 않는다.
- 서로 다른 제출 묶음의 entry도 선택 가능하지만, 동일 제안·대상 충돌 없이 1~20개 전체를 변경 전 상태에서 검증한 후 한 트랜잭션으로 반영한다. 요청 결정·AI 제안 결정·항목·근거·이력·멱등성 결과를 함께 저장한다.
- 반영한 요청은 APPLIED, 연결된 AI 제안도 APPLIED가 된다. 같은 제안의 다른 대기 요청은 INVALIDATED로 조회한다. 기존 소유자 직접 반영·미적용 경로와도 같은 제안 처리 상태를 사용해 중복 적용을 막는다.
- 선택하지 않은 유효 요청은 PENDING으로 남긴다. 관련 일정·할 일을 자동 변경하거나 재분석하지 않는다.

## 미적용·취소

미적용 본문은 `{"clientRequestId":"<새 UUID>","entries":[{"requestEntryId":"<요청 항목 UUID>","reviewToken":"<소유자 상세 토큰>"}]}`다. 취소 본문은 같은 형식에서 reviewToken 없이 requestEntryId만 전달한다. 취소 항목은 URL의 requestId 묶음에 속해야 한다.

유효한 PENDING만 처리하며 선택 전체가 성공하거나 실패한다. 요청 미적용·취소는 현재 항목과 원래 AI 제안을 변경하지 않는다. AI 제안 자체를 미적용하려면 기존 proposal-declines API를 사용한다. 취소·미적용은 항목 잠금을 요구하지 않으며 사유 입력·결정 되돌리기는 제공하지 않는다.

## 크기·재전송·내용 제거

- 새 POST도 JSON 디코딩 전에 원시 본문 최대 1 MiB(1,048,576바이트)를 검사한다. 길이 헤더가 없거나 작게 신고돼도 상한+1바이트까지만 읽고 초과 시 `413 PAYLOAD_TOO_LARGE`다.
- 자유입력 문자열은 각각 10,000 UTF-16 단위까지다. 제출할 요청 문서 묶음 합계와 반영할 새 최종 문서 묶음 합계에는 각각 512 KiB(524,288바이트)를 적용한다. 계산은 실제 저장할 문서를 공백 없는 UTF-8 JSON으로 직렬화한 크기이며 JSONB 디스크 크기가 아니다. 최종 문서는 생략으로 유지한 내용도 포함한다. 상한 초과는 `400 INVALID_REQUEST`이며 자르기·부분 처리하지 않는다.
- 같은 사용자·clientRequestId·동일 업무 내용은 처음 성공의 ID·버전·시각만 반환한다. 다른 작업·공간·내용이면 충돌이다. 재전송 때도 현재 권한과 원래 참여를 검사한다. 처리 기록에는 원문·검토 토큰·잠금 토큰을 넣지 않는다.
- 제출 내용은 별도 `CHANGE_REQUEST` 문서에 저장하고 AI 출력·캡처 문서·기존 대상 문서 의존성을 연결한다. 원본 삭제 전파는 저장된 요청 본문도 실제로 제거한다. 원래 값을 수정해 제출했어도 출처 의존성을 끊지 않는다.
- APPLIED/DECLINED/CANCELED 결정 사실은 출처 삭제 후에도 유지하지만 삭제된 requestedValues·appliedValues·근거를 반환하지 않는다. 과거 요청 재전송으로 삭제 본문을 복원하지 않는다. 공개 원본 삭제 API는 별도 후속이며 기존 삭제 연결점과 같은 공간 잠금·트랜잭션을 사용한다.

## 오류

| HTTP / 코드 | 조건 |
| --- | --- |
| 400 INVALID_REQUEST | 필수·허용 필드·유형·날짜·값·개수·문서 크기·커서 검증 실패 |
| 401 UNAUTHENTICATED | 유효 세션 없음 |
| 403 FORBIDDEN | 역할·현재/원래 참여·취소자·CSRF 검증 실패 |
| 404 RESOURCE_NOT_FOUND | 공간 또는 같은 공간의 요청·대상 없음 |
| 413 PAYLOAD_TOO_LARGE | JSON 본문 1 MiB 초과 |
| 409 CHANGE_REQUEST_ALREADY_PENDING | 동일 참여자·제안에 유효한 대기 요청 존재 |
| 409 CHANGE_REQUEST_STATE_CONFLICT | 이미 처리·취소·무효인 요청 또는 검토 조건 변경 |
| 409 VERSION_CONFLICT | 요청한 대상 버전과 현재 버전 불일치 |
| 409 PROPOSAL_STATE_CONFLICT / PROPOSAL_BATCH_CONFLICT | 원래 제안 유효성·처리 상태 또는 선택 묶음 대상 충돌 |
| 409 ITEM_LOCKED / EDIT_LOCK_EXPIRED | 잠금 충돌·만료·본인 토큰 불일치 |
| 409 IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS | 요청 ID의 다른 업무 내용 재사용·처리 중 |
| 500 INTERNAL_ERROR | 예상하지 못한 저장 실패, 전체 롤백 |

## 마이그레이션·검증 범위

V12는 제출 묶음·요청 항목·결정 묶음과 요청 문서 유형을 추가한다. 이미 적용된 V1~V11은 수정하지 않는다. 새 테이블을 사용하는 변경 요청 기능은 V12 적용 후 기동한 버전에서 사용한다.

격리 Testcontainers PostgreSQL과 합성 AI 응답으로 권한·초기 제출·소유자 최종 수정·취소/미적용·동일 제안 경합·선택 원자성·버전/잠금·멱등성·참여 종료/소유권 이전·원본 삭제를 검증한다. 자동 테스트 결과는 별도 실행 결과로 보고하며 문서 작성만으로 검증 완료로 간주하지 않는다.

Swagger 수동 확인은 두 브라우저 프로필에서 구성원/소유자로 각각 로그인한 뒤 진행한다. 구성원 제안 상세 → 기존 대상이면 잠금 → 제출(201, 항목 불변) → 소유자 요청 상세 → 기존 대상 잠금 → 반영(200) → 항목 버전·요청/제안 APPLIED 확인 순서다. 두 세션은 각자 CSRF 토큰을 사용한다. 빈 제안 배열을 반환하는 AI stub만으로는 제출 대상이 생기지 않는다.

AI 제안 없는 일반 직접 CRUD, 가족 답변 제출, 공간 정보 수정, 관련 항목 재분석·알림은 이번 범위가 아니다. 노션 DB와 기존 템플릿의 최종 동기화는 별도 사용자 지시 시 진행한다.
