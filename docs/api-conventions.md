# 공개 API 공통 규격

업무 API는 `/api` 아래에 있다. 개별 요청·응답 필드와 상태 코드는 [운영 Swagger](https://api.hanmaum.site/swagger-ui/index.html)와 [노션 API 명세](https://app.notion.com/p/3ec0f25ea138807cad15e07696472145)를 참고한다.

## 응답

`ApiResponse<T>`는 `success`, `code`, `message`, `data`, `errors`, `timestamp`를 반환한다. `errors`는 `[{field, reason}]` 배열이고 없으면 `[]`다. 오류의 `data`는 `null`이다.

| HTTP | 코드 | 규칙 |
| --- | --- | --- |
| 200·201 | `SUCCESS` | 조회·처리·생성 결과 |
| 202 | `ACCEPTED` | 비동기 작업 접수 |
| 204 | 없음 | 본문 없음 |

식별자는 UUID 문자열, 공개 버전은 십진 정수 문자열, 응답 시각은 UTC ISO 8601을 사용한다. 날짜만 있는 일정은 시간을 임의로 채우지 않는다.

## 오류 처리

- `400 INVALID_REQUEST`는 입력 오류, `401 UNAUTHENTICATED`는 인증 오류, `403 FORBIDDEN`은 권한·CSRF 오류, `404 RESOURCE_NOT_FOUND`는 유효한 대상이 없는 경우다.
- 버전 충돌, 편집 잠금, 분석 상태와 제안 결정 충돌은 개별 API의 `409` 코드를 따른다. HTTP와 `code`로 분기한다.
- `405`·`415` 등 프레임워크 오류는 `HTTP_<상태>`를 사용한다. `500 INTERNAL_ERROR`는 내부 예외나 원문을 노출하지 않는다.
- `clientRequestId`를 요구하는 API는 같은 작업을 재전송할 때 ID와 본문을 유지한다. 다른 내용으로 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`, 동일 요청 처리 중에는 `409 REQUEST_IN_PROGRESS`다.

## 인증과 적용 예외

- 요청에 세션 쿠키를 포함한다(`credentials: "include"`). `GET /api/auth/csrf`는 `{headerName, token}`을 직접 반환한다. 변경 요청에 지정된 헤더로 토큰을 보내고 로그인·로그아웃 후 다시 발급받는다.
- `POST /api/auth/logout`은 `204`를 반환한다. OAuth 성공 리다이렉트, Actuator, Swagger, BE↔AI 내부 응답은 일반 업무 응답과 별도다.
- 보호된 Controller는 `@CurrentUser AuthenticatedUser`로 현재 회원을 받는다. 요청의 사용자 ID를 신뢰하지 않으며 대상 공간·항목 권한은 Service에서 다시 확인한다. [백엔드 지침](../AGENTS.md)

### OAuth 로그인 시작 문서

브라우저를 `GET /oauth2/authorization/{provider}`로 이동한다. BE 콜백은 `/login/oauth2/code/{provider}`이며 제공자는 `google`·`kakao`다. 성공 복귀는 시작 요청의 `frontend` 선택을 따르고, 실패 시 `401 OAUTH_LOGIN_FAILED` JSON을 반환한다. [운영·로컬 OAuth 설정](deployment.md#운영-fe-로그인세션-연결)

`GET /api/users/me`는 `profileImageUrl`을 반환한다. 소셜 로그인 성공 시 받은 HTTPS 주소를 저장하며, 사진을 받지 못하면 `null`이다. 기존 회원의 사진도 다시 로그인할 때 갱신한다.

## 돌봄 공간 API

| 요청 | 핵심 규칙 |
| --- | --- |
| `POST /api/spaces` | `subjectLabel`, `clientRequestId`로 공간과 소유자 참여를 함께 생성. `201`과 `Location` 반환 |
| `GET /api/spaces` | 현재 참여 공간 목록 |
| `GET /api/spaces/{spaceId}` | 현재 참여자에게 해당 공간 상세·역할·버전 반환 |
| `PATCH /api/spaces/{spaceId}` | [공간 정보 수정](care-space-information.md) |

호칭은 한 줄이며 앞뒤 Unicode 공백 제거 후 1~30 Unicode code point다. 같은 소유자의 중복 호칭은 `409 SPACE_LABEL_DUPLICATED`다.

## 가족 초대·참여·구성원 API

- `POST /api/spaces/{spaceId}/invitations`로 현재 참여자가 링크를 발급한다. 유효 기간은 24시간이며 발급 당시 참여가 끝나면 링크가 무효다.
- `GET /api/invitations/{invitationToken}`으로 로그인 후 초대를 확인한다. `POST /api/invitations/{invitationToken}/join`은 새 참여에 `201`, 이미 참여 중이면 `200`을 반환한다.
- `GET /api/spaces/{spaceId}/members`는 현재 구성원을 소유자 우선으로 반환한다. 각 구성원의 `profileImageUrl`은 사진이 없으면 `null`이다.

## 소유권 이전·공간 나가기·구성원 내보내기 API

| POST 경로 (`/api/spaces/{spaceId}` 아래) | 권한 |
| --- | --- |
| `/ownership-transfers` | 소유자가 다른 현재 참여자에게 이전 |
| `/departures` | 구성원이 본인 참여 종료 |
| `/memberships/{membershipId}/removals` | 소유자가 구성원을 내보냄 |

각 요청의 버전·재전송 조건은 Swagger를 따른다. 참여가 끝나면 해당 참여의 잠금과 미완료 할 일 담당을 정리한다. 공간 전체 삭제는 [공간 삭제 계약](care-space-deletion.md)을 따른다.

## 소식 원본 저장·조회 API

- `POST /api/spaces/{spaceId}/source-records`는 `content`와 `clientRequestId`를 받아 `201`로 `recordId`, `version`, `recordedAt`, `analysis`를 반환한다. 원문은 유효한 공백·줄바꿈을 보존하며 10,000 UTF-16 코드 단위로 제한한다.
- `analysis`는 최초 분석 접수 정보다. 같은 생성 요청의 재전송은 최초 접수 정보를 재사용한다. 분석 기능 도입 전 원본의 재전송은 `analysis: null`이며 새 분석을 만들지 않는다.
- 목록·상세 GET은 `latestAnalysis`로 최신 분석을 반환한다. 목록은 `items`, `nextCursor`, `hasNext`를 사용한다.
- 목록·상세의 `author.profileImageUrl`은 작성자의 현재 프로필 사진 주소다. 사진이 없거나 작성자의 회원 연결이 제거되었으면 `null`이다.

## 소식 원본 삭제 API

1. `GET /api/spaces/{spaceId}/source-records/{recordId}/deletion-impact`로 버전과 `impactToken`을 받는다.
2. `POST /api/spaces/{spaceId}/source-records/{recordId}/deletions`에 `expectedRecordVersion`, `impactToken`, `clientRequestId`를 보낸다.
3. 현재 소유자 또는 원본 작성자만 삭제할 수 있다. 영향이 달라졌으면 `409 DELETION_IMPACT_CHANGED` 후 다시 조회한다. 성공 시 관련 소식·제안·근거·항목을 갱신한다.

## 소식 분석 상태·실패 재시도

- 소식 아래 `GET /analyses/{analysisId}`에서 `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `DISCARDED` 상태를 확인한다. 완료 시 `proposalIds`를, 실패 시 `failure`를 확인한다.
- `canRetry`가 true인 최신 실패 실행은 같은 소식의 `POST /analyses`에 `failedAnalysisId`, `expectedRecordVersion`, `clientRequestId`를 보내 `202`로 새 분석을 접수할 수 있다. 같은 요청 ID의 재전송은 중복 실행하지 않는다.
- 소식 저장 성공과 분석 성공은 별개다. [워커 설정](deployment.md#분석-워커-활성화비활성화) · [AI v2 저장과 조회](ai-contract-v2-execution.md)

## AI 제안 목록·상세·근거 조회

`GET /api/spaces/{spaceId}/proposals` 목록과 `GET .../proposals/{proposalId}` 상세는 현재 공간 참여자가 사용한다. 목록을 `recordId`, `status`, `cursor`, `size`로 조회한다. `status`는 `REVIEW_PENDING`(기본), `APPLIED`, `DECLINED`, `INVALIDATED`를 지원한다. 구성원 승인 요청은 별도 변경 요청 API에서 관리한다.

상세의 `currentValues`, `proposedValues`, `changedFields`, `clearFields`, 근거·후보·검토 사유를 함께 읽는다. 유효한 미처리 제안의 `reviewToken`은 결정 요청에 사용한다. 제거된 원본에서 파생된 내용은 목록·상세에서 가리며, 필요한 식별·결정 메타데이터만 유지한다.

## 일정 직접 등록·관리 항목 조회 (#22)

`GET /api/spaces/{spaceId}/items`는 `itemType`과 `view` 또는 `status`를 사용한다. 재확인 항목은 별도 배열이며, 일정의 예정·지난 분류는 Asia/Seoul 기준이다. 상세는 `GET .../items/{itemId}`다. 쓰기 계약은 [관리 항목 직접 변경](direct-care-items.md)을 참고한다.

### 확인사항 답변 제출 API

`POST /api/spaces/{spaceId}/items/{itemId}/answers`는 `answer`, `basis`, `expectedItemVersion`, `clientRequestId`를 받는다. 참여자가 답변을 제출하면 소유자는 별도 변경 요청 승인으로 확정한다. 답변 제출만으로 질문이 해결되지 않는다.

### 돌봄 항목 근거 재확인 API

현재 소유자가 `purpose: "RECONFIRMATION"`으로 편집 잠금을 획득한 뒤 `POST .../items/{itemId}/reconfirmations`에 `expectedItemVersion`, `leaseToken`, `values`, `evidenceIds`, `clientRequestId`를 보낸다. 확인할 값과 유효한 근거를 검증하여 새 버전을 저장한다.

## 관리 항목 일반 삭제

항목 버전·편집 잠금·권한을 확인하여 삭제 표시를 남긴다. 원본 소식 삭제와 별도 동작이다. [일반 삭제 계약](direct-care-items.md#일반-삭제)

## 돌봄 현황판

`GET /api/spaces/{spaceId}/dashboard`는 예정 일정 최대 2건, 지난 미완료 일정 건수, 남은 할 일 최대 3건, 미확인 질문 최대 2건, 최근 확정 변경 최대 3건을 동일 조회 시점 기준으로 반환한다.

## 상세 업무 계약

- [AI 제안 반영과 잠금](proposal-decisions.md) · [구성원 변경 요청](member-change-requests.md)
- [항목 근거·이력](care-item-provenance.md) · [공유 링크](share-links.md)
- [공간 전체 삭제](care-space-deletion.md) · [회원 탈퇴](member-withdrawal.md)
