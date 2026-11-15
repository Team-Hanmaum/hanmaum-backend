# 공간 정보 수정

## 소유자 직접 수정

`PATCH /api/spaces/{spaceId}`에 `subjectLabel`, `expectedSpaceVersion`, `clientRequestId`를 전달한다. 세션·CSRF·현재 소유권을 확인한다. 성공은 `200`과 `{spaceId, subjectLabel, version, updatedAt}`이다.

호칭 규칙은 [공간 생성](api-conventions.md#돌봄-공간-api)과 같다. 현재 소유자의 다른 공간과 호칭이 겹치면 `409 SPACE_LABEL_DUPLICATED`, 버전이 다르면 `409 VERSION_CONFLICT`다. 실제 호칭이 달라질 때만 버전과 수정 시각을 갱신한다.

## 구성원 요청과 소유자 결정

- 구성원은 [변경 요청](member-change-requests.md)으로 `sourceType: "DIRECT_INPUT"`, `action: "UPDATE_SPACE"`, `expectedSpaceVersion`, `values: {subjectLabel}`을 제출한다. `201`은 검토 대기이며 공간 상태는 유지된다.
- 소유자는 `requestEntryId`, 상세의 `reviewToken`, `expectedSpaceVersion`으로 반영한다. `values`는 생략 시 요청값, 제공 시 최종 호칭이다. 공간 수정은 항목 편집 잠금을 요구하지 않는다.
- 승인 결과의 `spaceVersion`으로 반영된 버전을 확인한다. 공간 버전이 달라지면 기존 요청을 다시 적용할 수 없다. 성공 후 공간 목록·상세를 갱신한다.
