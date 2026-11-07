# 공간 정보 수정

공간에서 사용하는 돌봄 대상 이름·호칭 `subjectLabel`을 변경한다. 현재 소유자는 직접 수정하고 현재 구성원은 수정 요청을 제출한다. 실제 화면 연결과 Notion 최종 동기화는 별도 진행한다.

## 소유자 직접 수정

`PATCH /api/spaces/{spaceId}`. 로그인 세션·CSRF·현재 소유권을 확인한다. 공간 상세 GET에서 확인한 버전을 전달한다.

```json
{"subjectLabel":"어머니","expectedSpaceVersion":"3","clientRequestId":"832cbe35-9970-4f99-a219-8f019315b3ab"}
```

세 필드는 필수다. 버전은 1 이상 정규 십진 정수 문자열이며 숫자 JSON은 허용하지 않는다. 알 수 없는 필드·사용자 ID·역할 등은 전달할 수 없다.

호칭은 생성과 동일하게 앞뒤 Unicode 공백을 제거한 뒤 1~30 코드 포인트다. 줄바꿈·NUL·잘못된 서로게이트는 불허하며 내부 공백·대소문자는 유지한다. 같은 현재 소유자의 다른 공간과 정규화된 호칭이 같으면 충돌이다. 다른 소유자의 공간과는 중복을 허용한다. 소유자별 기존 DB UNIQUE 제약이 동시 변경·생성·이전의 최종 중복을 검사한다.

성공은 `200 SUCCESS`, `Cache-Control: no-store`이며 data는 다음과 같다.

```json
{"spaceId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","subjectLabel":"어머니","version":"4","updatedAt":"2026-11-07T08:00:00Z"}
```

실제 호칭 변경·공간 버전 1 증가·수정 시각·성공 요청 기록을 하나의 트랜잭션으로 저장한다. 생성 시각은 유지한다. 정규화한 호칭이 현재와 같으면 성공하되 버전과 수정 시각은 유지한다. 이 경우도 현재 버전을 확인하고 성공 요청 기록을 남긴다.

동일한 논리적 요청의 재전송은 같은 `clientRequestId`를 사용한다. 직접 수정은 정규화한 호칭·대상 공간·조회한 버전까지 비교한다. 현재 소유권과 원래 참여를 다시 확인한 뒤 최초 결과를 반환하며 나중의 호칭을 과거 값으로 되돌리지 않는다. 최초 결과의 본문은 별도 `DIRECT_INPUT` 문서에서 읽으며 성공 기록에는 문서 ID·참여 ID·버전·시각만 저장한다. 내용이 제거된 문서는 입력값으로 복원하지 않는다. 같은 키를 다른 작업·대상·내용·버전에 재사용할 수 없다.

## 구성원 요청과 소유자 결정

기존 [변경 요청 API](member-change-requests.md)를 사용한다. 공간 수정 요청은 `DIRECT_INPUT UPDATE_SPACE`다.

```json
{"clientRequestId":"8825a4d7-b51e-45a4-ad42-04f9eae7bb3d","entries":[{"sourceType":"DIRECT_INPUT","action":"UPDATE_SPACE","expectedSpaceVersion":"3","values":{"subjectLabel":"어머니"}}]}
```

- `values.subjectLabel`과 `expectedSpaceVersion`이 필수다. `targetItemId`, `expectedItemVersion`, `leaseToken`, AI 제안 ID·제안 검토 토큰은 생략/null이다. 공간 편집은 공간 버전 비교를 사용한다.
- 제출은 `201` 승인 대기 기록만 만들며 호칭·공간 버전을 바꾸지 않는다. 현재 소유자의 호칭 중복 기준을 제출·승인 시 모두 검사한다. 같은 참여자·공간 버전의 유효 대기 공간 수정 요청은 하나만 허용한다.
- 요청 상세는 `expectedSpaceVersion`, `currentSpaceVersion`, `requestedValues.subjectLabel`, `currentValues.subjectLabel`을 제공한다. 유효한 PENDING 요청에 현재 소유자 전용 `reviewToken`을 발급한다.
- 소유자는 `/request-applications`에 `requestEntryId`, `reviewToken`, `expectedSpaceVersion`을 전달한다. values를 생략하면 저장된 요청값을 사용하고, 전달하면 전체 `{subjectLabel}`을 대체한다. 원래 요청값은 보존하고 정규화한 최종 값을 결정 문서에 저장한다.
- 반영 결과는 `{requestEntryId,itemId:null,itemVersion:null,spaceVersion}`이다. 항목 작업의 기존 ID·버전은 유지하며 추가 필드 `spaceVersion`은 null이다. 상세 결정의 `appliedValues`는 최종 `{subjectLabel}`, `spaceVersion`은 반영 결과 버전이다.
- 수정 요청의 승인도 실제 호칭이 달라진 경우에만 공간 버전·수정 시각을 변경한다. 같은 호칭은 APPLIED 결정만 기록하고 기존 버전을 반환한다.
- 한 제출·반영 묶음에서 같은 공간을 두 번 수정할 수 없다. 공간 수정 한 건과 다른 관리 항목 작업은 함께 선택할 수 있으며, 전체 검증 후 함께 성공하거나 함께 롤백한다.
- 공간 호칭·참여·소유권 등의 변경으로 공간 버전이 달라진 대기 요청은 `INVALIDATED / SPACE_VERSION_CHANGED`다. 조회로 상태를 쓰거나 버전을 자동 갱신하지 않는다. 반영 시 `409 VERSION_CONFLICT`이며 최신 공간을 확인한 새 요청을 제출한다.
- 제출자 참여 종료는 `REQUESTER_LEFT`로 무효화한다. 종료 후 재가입은 새 참여이므로 과거 요청의 성공 재전송 권한을 복원하지 않는다.
- 기존 소유자 미적용·원래 제출자 취소 API를 그대로 사용한다. 미적용·취소는 공간 정보를 바꾸지 않는다. 현재 참여자만 결정·요청 내용을 조회한다.

## 오류와 FE 연결

| 상태·코드 | 처리 |
| --- | --- |
| 400 INVALID_REQUEST | 필수 값·형식·호칭·허용 필드 확인 |
| 401 UNAUTHENTICATED | 로그인 재확인 |
| 403 FORBIDDEN | CSRF·현재 역할·원래 참여 확인 |
| 404 RESOURCE_NOT_FOUND | 공간 없음·비활성 또는 재전송 결과 문서 조회 불가 |
| 409 VERSION_CONFLICT | 최신 공간 상세를 재조회하고 재검토 |
| 409 SPACE_LABEL_DUPLICATED | 현재 소유자의 다른 공간과 다른 호칭 입력 |
| 409 CHANGE_REQUEST_ALREADY_PENDING | 기존 유효 요청 확인 또는 취소 후 새 요청 제출 |
| 409 PROPOSAL_BATCH_CONFLICT | 한 묶음의 중복 공간 대상 확인 |
| 409 CHANGE_REQUEST_STATE_CONFLICT | 결정·무효 상태 또는 검토 토큰 재확인 |
| 409 IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS | 새 논리적 작업은 새 UUID, 처리 중 동일 요청은 같은 UUID로 재시도 |

직접 수정·승인 성공 후 공간 목록·상세·헤더 호칭을 재조회한다. 구성원 제출 `201`은 승인 대기로 표시하며 확정 호칭을 먼저 바꾸지 않는다. 성공 재전송의 data는 최초 처리 시점 결과이므로 현재 화면은 재조회한 값을 사용한다. 관리 항목의 편집 잠금은 별도 항목 변경 계약을 유지한다.

## V20 전환

V20은 요청 항목의 공간 검토/결과 버전·작업 제약·무효화 함수·조회 인덱스를 추가한다. V1~V19는 수정하지 않고 기존 항목 요청·삭제 결과·문서를 유지한다. 공간 호칭 변경 결과 문서는 원본 소식·AI 출력과 부모 의존성을 연결하지 않는 독립 입력이다. BE↔AI 계약 변경은 없다.

관련 공개 쓰기·worker 중지 → 진행 중 요청 종료 대기 → 이전 BE 전체 종료 → V20을 적용한 호환 BE 기동 → 마이그레이션·health·Swagger 확인 → 공개 쓰기·worker 재개 순서로 전환한다. 이전 BE는 UPDATE_SPACE와 신규 버전 필드를 처리하지 못하므로 새 요청을 저장하기 전에 모두 종료한다. 새 값 저장 후 즉시 구버전으로 되돌리지 않는다. 개발 DB 초기화·기존 문서 일괄 변환은 수행하지 않는다.
