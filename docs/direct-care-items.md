# 관리 항목 직접 등록·수정·상태 변경·일반 삭제 (#37, #61)

기본 경로 `/api/spaces/{spaceId}`. 모든 쓰기는 현재 참여, 로그인 세션, CSRF가 필요하다. FE는 저장할 때 서버 응답의 ID와 상태를 기준으로 화면을 갱신한다.

| 사용자 의도 | 호출 | 성공 시 화면 |
| --- | --- | --- |
| 소유자가 일정·할 일·질문 등록 | POST /items | 201, 확정 목록에 표시 |
| 구성원이 **자신을 담당자로 지정**한 할 일 등록 | POST /items | 201, 미시작 상태로 즉시 표시 |
| 구성원이 미지정·타인 담당 할 일, 일정 또는 질문 등록 요청 | POST /change-requests | 201, 요청 목록에 승인 대기로 표시 |
| 소유자가 기존 다섯 유형의 내용·담당자 수정 | PATCH /items/{itemId} | 200, 최신 항목 조회 |
| 소유자의 일정·할 일 상태 변경, 현재 담당자의 본인 할 일 상태 변경 | PATCH /items/{itemId}/status | 200, 최신 상태 표시 |
| 그 밖의 구성원 수정 | POST /change-requests | 201, 승인까지 현재 항목 유지 |
| 소유자가 다섯 유형의 기존 항목 삭제 | POST /items/{itemId}/deletions | 200, 확정 목록에서 제외 |
| 구성원이 기존 항목 삭제 요청 | POST /change-requests | 201, 승인까지 현재 항목 유지 |

관찰·안내는 소식과 AI 제안으로 생성한다. 서버는 권한 없는 직접 호출을 자동으로 승인 요청으로 바꾸지 않는다. 본인 담당인지 여부는 로그인 계정의 **현재 공간 참여 ID**와 입력/저장된 담당자를 비교한다.

## 생성

```json
{"clientRequestId":"33333333-3333-4333-8333-333333333333","itemType":"TASK","title":"약 받아오기","description":null,"fields":{"dueDate":null,"assigneeMembershipId":"11111111-1111-4111-8111-111111111111"}}
```

담당자에는 같은 공간의 실제 활성 membershipId를 사용한다. 미지정은 생략 또는 null이다. 기존 일정 형식은 그대로이며, 질문은 `fields: {}`이다. 초기 상태는 각각 SCHEDULED / NOT_STARTED / UNCONFIRMED. 성공 data는 `itemId,itemType,version,createdAt`, 버전은 문자열 `"1"`이다.

## 기존 항목 변경

본인 잠금 획득 후 상세에서 확인한 버전을 사용한다. 두 PATCH 모두 성공 data는 `itemId,version,updatedAt`이다.

```json
{"clientRequestId":"44444444-4444-4444-8444-444444444444","expectedItemVersion":"1","leaseToken":"<내 잠금 토큰>","values":{"fields":{"dueDate":"2026-11-02"}}}
```

상태 전용 경로는 `values` 대신 최상위 `"status":"COMPLETED"`만 받는다. 일정은 예정→완료/취소, 할 일은 미시작→진행 중/완료, 진행 중→완료, 완료→진행 중이다. 날짜 경과로 자동 완료하지 않는다. 생략한 필드는 유지되고 nullable 필드의 명시적 null은 값을 지운다. 유형·작성자·근거·확정 답변 직접 수정은 지원하지 않는다.

## 구성원 직접 입력 요청

기존 [변경 요청 API](member-change-requests.md)의 entry에 `sourceType: "DIRECT_INPUT"`과 `CREATE_ITEM`, `UPDATE_ITEM`, `DELETE_ITEM`을 사용한다. 생성·수정의 `values`는 생성 필드 또는 변경분이며 삭제에는 생략/null만 허용한다. UPDATE·DELETE는 `targetItemId,expectedItemVersion,leaseToken`이 필요하다. 제출 시 AI proposalId·제안 reviewToken은 사용하지 않는다. AI 경로는 sourceType 생략 또는 AI_PROPOSAL이며 기존 필수값과 재전송 계약을 유지한다. AI 제안으로 삭제를 제출하거나 반영할 수 없다.

```json
{"clientRequestId":"55555555-5555-4555-8555-555555555555","entries":[{"sourceType":"DIRECT_INPUT","action":"CREATE_ITEM","values":{"itemType":"TASK","title":"병원에 전화하기","fields":{"assigneeMembershipId":null}}}]}
```

FE는 승인 대기 목록과 확정 목록을 구분한다. 소유자는 요청 상세의 reviewToken으로 기존 승인/미적용 API를 사용한다. 대상과 생성 유형은 바꿀 수 없다. 제출 시 잠금을 풀고, 기존 항목 승인 시 소유자가 다시 잠근다. 취소는 원래 활성 참여자만 가능하다. 제출 원문·입력자·시각과 최종 반영 문서·결정자는 따로 보존한다. AI/직접 입력을 함께 선택해도 모두 검증한 뒤 한 트랜잭션에서 처리한다.

같은 참여자·대상·작업·버전의 유효한 직접 수정 요청은 하나만 허용한다. 동일 제목으로 새 항목을 만들 수 있으며 성공 재전송은 같은 clientRequestId를 사용한다. 승인 후 해당 항목 버전이 달라지면 같은 버전을 본 다른 요청은 자동 병합되지 않는다.

## 일반 삭제

현재 소유자가 `POST /items/{itemId}/deletions`에 아래 본문을 보낸다. 다섯 유형 모두 지원하며 완료·취소 상태도 삭제할 수 있다. 일반 삭제는 업무 상태 변경이나 원본 소식 삭제와 별개다.

```json
{"expectedItemVersion":"1","leaseToken":"<내 잠금 토큰>","clientRequestId":"66666666-6666-4666-8666-666666666666"}
```

성공 data는 `{itemId,version,visibility:"DELETED",deletedAt}`이며 `deletedAt`은 최초 삭제 시각이다. 버전 증가·DELETE 이력·삭제 표시·잠금 해제·성공 기록을 하나의 트랜잭션으로 저장한다. 원본 소식, 기존 문서·근거·이력은 보존하고 새 내용 문서나 근거를 만들지 않는다. 일반 목록·현황판·신규 분석 후보에서 제외하고 이미 진행 중인 분석 결과도 대상 버전·가시성 재검증을 거친다. 삭제 후 ID 상세는 내용 없는 삭제 메타데이터를 반환한다. 현재 참여자는 기존 `/items/{itemId}/evidence`, `/history`, `/history/{version}`을 조회할 수 있으며 DELETE 버전의 `before`는 유효한 직전 값, `after`는 null이다. 이후 원본 삭제가 발생하면 기존 파생 내용 제거 규칙에 따라 이력 내용도 가린다. 복구와 공유 링크 기능은 이 작업에 포함하지 않는다.

직접 삭제·구성원 요청 제출·소유자 승인은 모두 **본인 잠금 필수**다. 잠금 획득 `POST /items/{itemId}/edit-lock` 및 갱신에 `purpose:"DELETION"`을 전달한다. 기본 EDIT 잠금도 읽을 수 있는 일반 항목 삭제에 사용할 수 있지만, 재확인 대상·내용 제거 항목은 DELETION 잠금을 사용한다. DELETION은 현재 참여자가 획득할 수 있고 편집·재확인 저장에는 사용할 수 없다. RECONFIRMATION 잠금으로 삭제할 수 없다. 잠금 공개 조회는 점유자·만료·본인 여부만 제공한다.

구성원은 아래처럼 제출한 뒤 기존 소유자 승인·미적용 또는 제출자 취소 API를 사용한다. 제출은 삭제를 실행하지 않으며 제출자 잠금을 해제한다. 승인할 때 소유자가 새 잠금을 획득하고 요청 상세의 `reviewToken`, 현재 버전과 함께 전달한다. 선택한 여러 요청은 모두 검증한 뒤 함께 반영한다. 삭제 요청에는 내용을 복사하지 않으며 요청 상세의 `requestedValues`와 결정의 `appliedValues`는 null이다. 이 null은 삭제에 의한 가림 표시가 아니다.

```json
{"clientRequestId":"77777777-7777-4777-8777-777777777777","entries":[{"sourceType":"DIRECT_INPUT","action":"DELETE_ITEM","targetItemId":"<항목 UUID>","expectedItemVersion":"1","leaseToken":"<내 잠금 토큰>"}]}
```

같은 요청은 같은 `clientRequestId`로 재전송한다. 서버는 현재 소유권·원래 참여를 확인하고 최초 결과를 반환하며 소모된 잠금을 다시 요구하지 않는다. 같은 키의 대상·버전 변경은 `409 IDEMPOTENCY_KEY_REUSED`, 새 키로 이미 삭제된 항목을 삭제하면 `409 ITEM_STATE_CONFLICT`다. 버전 불일치는 `409 VERSION_CONFLICT`, 타인 잠금은 `409 ITEM_LOCKED`, 본인 잠금 없음·만료·잘못된 토큰·목적은 `409 EDIT_LOCK_EXPIRED`다. 삭제할 원본 항목이 없거나 다른 공간의 ID면 `404 RESOURCE_NOT_FOUND`다.

V19는 DELETE 이력·DELETE_ITEM 직접 요청·DELETION 잠금과 삭제 요청 유효성 규칙을 추가한다. V1~V18은 수정하지 않는다. 기존 데이터는 유지하며 다음 순서로 전환한다.

1. 관련 공개 쓰기와 분석 worker를 중지하고 기존 요청이 끝날 때까지 대기한다.
2. 새 작업·잠금이 저장되기 전에 이전 BE 인스턴스를 모두 종료한다. 이전 BE는 DELETION 잠금을 enum으로 변환하지 못하고 DELETE_ITEM 요청도 처리하지 못하므로 두 버전을 혼합 운영하지 않는다. 일반적인 블루/그린 전환의 인스턴스 중첩만으로 이 조건을 충족하지 않는다.
3. V19를 적용한 호환 BE만 실행하고 마이그레이션 성공·health·Swagger를 확인한다.
4. 이전 BE가 요청을 처리하지 않음을 확인한 뒤 공개 쓰기와 worker를 재개한다. 새 값이 저장된 뒤에는 이전 BE로 즉시 되돌리지 않고 새 값을 읽을 수 있는 호환 버전을 사용한다.

BE↔AI 계약 변경은 없다.

## 저장·오류

- 최초 등록 및 각 확정 변경은 문서·근거·이력·성공 기록과 함께 커밋한다. 항목 버전만 증가시킨다. 요청/직접 문서와 종전 문서에 대한 삭제 의존성은 유지하고 성공 기록에는 원문·토큰을 넣지 않는다.
- 참여 종료·내보내기는 같은 트랜잭션에서 현재 보이는 미완료 담당 할 일을 미지정으로 전환한다. 새 버전·UNASSIGN 이력과 영향받은 잠금 해제를 함께 기록한다. 완료 항목의 문서와 과거 이력은 보존하고 재가입으로 담당을 복원하지 않는다.
- 기존 본문 1 MiB, 자유 문자열 각각 10,000 UTF-16 단위, 새 최종 문서 합계 512 KiB를 사용한다. 필드/실제 변경 없음은 400 INVALID_REQUEST, 원시 본문 초과는 413 PAYLOAD_TOO_LARGE. 인증·권한·대상 없음은 기존 401/403/404.
- 409 ITEM_STATE_CONFLICT는 일반 항목의 허용되지 않는 상태 전이. 버전/잠금·재전송·대기 중복은 기존 VERSION_CONFLICT, ITEM_LOCKED / EDIT_LOCK_EXPIRED, IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS, CHANGE_REQUEST_ALREADY_PENDING.
- V13은 기존 요청을 AI_PROPOSAL로 유지하고 출처별 제약·직접 요청 유효성 판정·담당 해제 이력을 추가한다. 마이그레이션 후 DIRECT_INPUT을 쓰기 전에 모든 BE를 호환 버전으로 교체한다. AI 서버·모델 및 공통 fixture 변경은 필요 없다.

Swagger 확인: 구성원으로 로그인 → 구성원 목록의 내 membershipId 확인 → CSRF 발급 → POST /items로 본인 담당 할 일 등록 → 편집 잠금 획득 → PATCH 상태 변경. 미지정 할 일은 POST /change-requests로 제출한 뒤 다른 브라우저의 소유자 세션에서 승인하고 확정 목록을 조회한다.
