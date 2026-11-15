# 관리 항목 근거·확정 변경 이력 조회

기본 경로는 `/api/spaces/{spaceId}/items/{itemId}`다. 현재 공간 참여자만 조회할 수 있다.

| 요청 | 결과 |
| --- | --- |
| GET `/evidence` | 현재 연결 근거 목록 |
| GET `/history` | 확정 버전 내림차순 이력 |
| GET `/history/{version}` | 선택 버전과 직전 저장 버전의 비교 |

## 현재 근거

근거 유형은 소식 인용(`RECORD_QUOTE`), 직접 입력(`DIRECT_INPUT`), 파생 입력(`DERIVED`), 재확인(`RECONFIRMATION`)이다. `VALID` 근거만 본문을 반환한다. `REDACTED`는 `text`·`recordId`가 null이며 원본 삭제로 연결 자체가 제거되면 현재 목록에서 빠진다. `fieldPaths`는 빈 배열이다.

## 확정 이력과 비교

대기 중인 제안·변경 요청은 확정 이력에 포함하지 않는다. 최초 버전에는 `before`가 없고, 일반 삭제 버전의 `after`는 null이다. 삭제 원본에서 파생된 비교값도 null이며 `redactedFields`에 표시한다. 버전·변경 시각·행위자 메타데이터와 현재 유효한 근거 ID만 보존해서 보여 준다. 회원 연결이 삭제된 경우 작성자는 `withdrawn: true`로 표시한다.

권한과 비교값은 같은 DB 스냅샷에서 확인한다. [항목 직접 변경](direct-care-items.md) · [가족 답변 제출](api-conventions.md#확인사항-답변-제출-api)
