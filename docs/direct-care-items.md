# 관리 항목 직접 등록·수정·상태 변경 (#37)

기본 경로 `/api/spaces/{spaceId}`. 모든 쓰기는 현재 참여, 로그인 세션, CSRF가 필요하다. FE는 저장할 때 서버 응답의 ID와 상태를 기준으로 화면을 갱신한다.

| 사용자 의도 | 호출 | 성공 시 화면 |
| --- | --- | --- |
| 소유자가 일정·할 일·질문 등록 | POST /items | 201, 확정 목록에 표시 |
| 구성원이 **자신을 담당자로 지정**한 할 일 등록 | POST /items | 201, 미시작 상태로 즉시 표시 |
| 구성원이 미지정·타인 담당 할 일, 일정 또는 질문 등록 요청 | POST /change-requests | 201, 요청 목록에 승인 대기로 표시 |
| 소유자가 기존 다섯 유형의 내용·담당자 수정 | PATCH /items/{itemId} | 200, 최신 항목 조회 |
| 소유자의 일정·할 일 상태 변경, 현재 담당자의 본인 할 일 상태 변경 | PATCH /items/{itemId}/status | 200, 최신 상태 표시 |
| 그 밖의 구성원 수정 | POST /change-requests | 201, 승인까지 현재 항목 유지 |

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

기존 [변경 요청 API](member-change-requests.md)의 entry에 `sourceType: "DIRECT_INPUT"`과 `CREATE_ITEM` 또는 `UPDATE_ITEM`을 사용한다. `values`는 생성 필드 또는 변경분이다. UPDATE는 `targetItemId,expectedItemVersion,leaseToken`이 필요하다. 제출 시 AI proposalId·제안 reviewToken은 사용하지 않는다. AI 경로는 sourceType 생략 또는 AI_PROPOSAL이며 기존 필수값과 재전송 계약을 유지한다.

```json
{"clientRequestId":"55555555-5555-4555-8555-555555555555","entries":[{"sourceType":"DIRECT_INPUT","action":"CREATE_ITEM","values":{"itemType":"TASK","title":"병원에 전화하기","fields":{"assigneeMembershipId":null}}}]}
```

FE는 승인 대기 목록과 확정 목록을 구분한다. 소유자는 요청 상세의 reviewToken으로 기존 승인/미적용 API를 사용한다. 대상과 생성 유형은 바꿀 수 없다. 제출 시 잠금을 풀고, 기존 항목 승인 시 소유자가 다시 잠근다. 취소는 원래 활성 참여자만 가능하다. 제출 원문·입력자·시각과 최종 반영 문서·결정자는 따로 보존한다. AI/직접 입력을 함께 선택해도 모두 검증한 뒤 한 트랜잭션에서 처리한다.

같은 참여자·대상·작업·버전의 유효한 직접 수정 요청은 하나만 허용한다. 동일 제목으로 새 항목을 만들 수 있으며 성공 재전송은 같은 clientRequestId를 사용한다. 승인 후 해당 항목 버전이 달라지면 같은 버전을 본 다른 요청은 자동 병합되지 않는다.

## 저장·오류

- 최초 등록 및 각 확정 변경은 문서·근거·이력·성공 기록과 함께 커밋한다. 항목 버전만 증가시킨다. 요청/직접 문서와 종전 문서에 대한 삭제 의존성은 유지하고 성공 기록에는 원문·토큰을 넣지 않는다.
- 참여 종료·내보내기는 같은 트랜잭션에서 현재 보이는 미완료 담당 할 일을 미지정으로 전환한다. 새 버전·UNASSIGN 이력과 영향받은 잠금 해제를 함께 기록한다. 완료 항목의 문서와 과거 이력은 보존하고 재가입으로 담당을 복원하지 않는다.
- 기존 본문 1 MiB, 자유 문자열 각각 10,000 UTF-16 단위, 새 최종 문서 합계 512 KiB를 사용한다. 필드/실제 변경 없음은 400 INVALID_REQUEST, 원시 본문 초과는 413 PAYLOAD_TOO_LARGE. 인증·권한·대상 없음은 기존 401/403/404.
- 409 ITEM_STATE_CONFLICT는 일반 항목의 허용되지 않는 상태 전이. 버전/잠금·재전송·대기 중복은 기존 VERSION_CONFLICT, ITEM_LOCKED / EDIT_LOCK_EXPIRED, IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS, CHANGE_REQUEST_ALREADY_PENDING.
- V13은 기존 요청을 AI_PROPOSAL로 유지하고 출처별 제약·직접 요청 유효성 판정·담당 해제 이력을 추가한다. 마이그레이션 후 DIRECT_INPUT을 쓰기 전에 모든 BE를 호환 버전으로 교체한다. AI 서버·모델 및 공통 fixture 변경은 필요 없다.

Swagger 확인: 구성원으로 로그인 → 구성원 목록의 내 membershipId 확인 → CSRF 발급 → POST /items로 본인 담당 할 일 등록 → 편집 잠금 획득 → PATCH 상태 변경. 미지정 할 일은 POST /change-requests로 제출한 뒤 다른 브라우저의 소유자 세션에서 승인하고 확정 목록을 조회한다.
