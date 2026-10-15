# 공개 API 공통 규격

[노션 공통 규격](https://app.notion.com/p/3ec0f25ea138807cad15e07696472145)과 [상세 규격](https://app.notion.com/p/3ee0f25ea138800996a9f0e9d712fde5)의 백엔드 적용 기준입니다. 도메인 API 상세 초안과 실제 구현 상태는 구분합니다.

## 응답

공개 JSON 응답은 `ApiResponse<T>`를 명시적으로 반환합니다. 모든 응답을 자동으로 감싸지 않습니다. 상태 코드는 `ResponseEntity` 또는 Controller의 HTTP 설정으로 지정합니다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 완료되었습니다.",
  "data": { "items": [] },
  "errors": [],
  "timestamp": "2026-10-07T08:00:00Z"
}
```

- 여섯 필드를 항상 포함합니다. 오류는 `success: false`, `data: null`, 필드 오류가 없으면 `errors: []`입니다.
- `errors` 원소는 `field`와 `reason`입니다. 중첩 경로 예시는 `entries[0].expectedItemVersion`이며 기존 `fieldErrors`는 반환하지 않습니다.
- 200·201은 `SUCCESS`, 202는 `ACCEPTED`, 204는 본문 없음입니다. 접수 응답이 업무 완료를 뜻하지 않습니다.
- 성공 코드·기본 메시지는 `global.code.SuccessCode`에서 관리하고 `ApiResponse.success(data)`·`accepted(data)`로 생성합니다. HTTP 상태는 Controller에서 지정하며 204용 응답 코드는 만들지 않습니다.
- `success`는 이번 HTTP 요청의 결과입니다. 분석 상태 조회가 성공하면 분석 결과가 실패여도 `success: true`입니다.
- `timestamp`는 응답 생성 시각이며 업무 생성·수정 시각은 필요한 API의 `data`에 별도로 정의합니다.
- FE는 HTTP 상태와 `code`로 분기하고 메시지 문자열을 파싱하지 않습니다.
- 목록은 `data.items`, 서비스 ID는 UUID 문자열, 공개 도메인 버전은 십진 정수 문자열, 시각은 UTC ISO 8601입니다. 공개 DTO에서 타입을 명시하며 내부 AI의 숫자 버전까지 전역 변환하지 않습니다.

## 오류 처리

`global.code.ErrorCode` 인터페이스는 `status()`, `code()`, `message()`를 정의합니다. 공통 오류는 `CommonErrorCode`, 특정 업무 오류는 각 도메인의 `code` 패키지에 있는 enum에서 관리합니다. `ApiException`과 `ApiResponse.error`는 이 인터페이스를 받으므로 MVC와 Security가 같은 공통 응답을 사용합니다.

| 관리 위치 | 기존 오류 코드 |
| --- | --- |
| `global/code/CommonErrorCode` | 입력·인증 필요·권한·대상 없음·버전·반복 요청·삭제 영향·서버·AI 서비스 오류 |
| `auth/code/AuthErrorCode` | `OAUTH_LOGIN_FAILED` |
| `carespace/code/CareSpaceErrorCode` | `SPACE_LABEL_DUPLICATED`, `INVITATION_UNAVAILABLE`, `INVITATION_EXPIRED` |
| `careitem/code/CareItemErrorCode` | `ITEM_LOCKED` |
| `proposal/code/ProposalErrorCode` | `PROPOSAL_BATCH_CONFLICT` |

기존 공통 오류로 충분하면 재사용합니다. FE가 별도로 구분할 업무 오류만 해당 도메인에서 추가하고, `code()`가 반환하는 문자열은 서비스 전체에서 중복되지 않도록 관리합니다. 현재 enum은 `name()`을 반환하므로 상수 이름을 바꾸면 외부 API 코드도 바뀐다는 점에 주의합니다.

```java
// Controller: 생성 성공도 HTTP 상태는 명시적으로 지정합니다.
return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));

// Service: 알려진 실패는 HTTP 상태와 안전한 안내가 정의된 코드를 사용합니다.
throw new ApiException(CommonErrorCode.VERSION_CONFLICT);

// 도메인 전용 코드도 같은 예외로 처리합니다.
throw new ApiException(ProposalErrorCode.PROPOSAL_BATCH_CONFLICT);
```

필드 오류가 필요하면 `ApiException(code, List<ApiFieldError>)`를 사용합니다. Validation 메시지와 `reason`에는 원문·제출 값·인증 정보를 끼워 넣지 않습니다.

| HTTP 상태 | 코드 |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 401 | `UNAUTHENTICATED`, `OAUTH_LOGIN_FAILED` |
| 403 | `FORBIDDEN` |
| 404 | `RESOURCE_NOT_FOUND`, `INVITATION_UNAVAILABLE` |
| 409 | `VERSION_CONFLICT`, `ITEM_LOCKED`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`, `SPACE_LABEL_DUPLICATED`, `PROPOSAL_BATCH_CONFLICT`, `DELETION_IMPACT_CHANGED` |
| 410 | `INVITATION_EXPIRED` |
| 500 | `INTERNAL_ERROR` |
| 502 | `AI_SERVICE_UNAVAILABLE` |

- 잘못된 JSON·필수 파라미터·입력 형식·본문 검증 등 MVC 400은 `INVALID_REQUEST`, MVC 404는 `RESOURCE_NOT_FOUND`입니다.
- 보호 경로는 보안 검사가 먼저 적용되므로 비로그인 요청은 401 또는 CSRF 검사에 따른 403일 수 있습니다.
- 별도 합의하지 않은 프레임워크 상태는 실제 HTTP 상태와 `HTTP_<상태>` 및 일반 안내를 사용합니다. 예: 405 `HTTP_405`, 415 `HTTP_415`. `Allow` 등 프레임워크 헤더를 보존합니다.
- 예상하지 못한 예외는 500이며 예외 메시지·제출 값·AI 원문을 노출하지 않습니다. 반환 값 검증 실패도 입력 오류로 바꾸지 않습니다.
- 추가 도메인 코드는 해당 기능 구현 시 합의합니다. 코드 상수의 존재는 잠금·승인·삭제 업무 구현을 뜻하지 않습니다.

## 인증과 적용 예외

- `HANMAUM_SESSION`은 HttpOnly 세션 쿠키입니다. FE는 `credentials: 'include'`를 사용하고 쿠키를 직접 읽거나 헤더를 만들지 않습니다.
- `GET /api/auth/csrf`는 로그인 없이 호출할 수 있고 `{headerName, token}`을 반환합니다. 변경 요청은 반환된 헤더 이름과 토큰을 사용합니다.
- 로그인·로그아웃 후 다음 변경 요청 전에 CSRF 토큰을 다시 받습니다.
- OAuth 시작·콜백 경로와 리다이렉트를 유지하며 OAuth 실패 JSON은 공통 오류 형식입니다.
- `POST /api/auth/logout` 성공은 204입니다. 기존 필터의 세션·쿠키 무효화를 유지합니다.
- BE↔AI 내부 응답, Actuator 상태 확인, OpenAPI 문서·Swagger 리소스는 업무 응답으로 감싸지 않습니다. CORS preflight는 별도의 브라우저 접근 검사입니다.

### OAuth 로그인 시작 문서

- `GET /oauth2/authorization/{provider}`는 Spring Security가 처리하는 공개 경로이며 세션 인증·CSRF 토큰·요청 본문이 필요하지 않습니다. Swagger에서는 `Auth`로 분류하고 현재 등록된 `google`·`kakao`만 provider 선택값으로 제공합니다. 제공자가 설정되지 않은 환경에서는 이 경로를 표시하지 않습니다.
- 정상 응답은 `302`와 제공자 인증 URL인 `Location` 헤더입니다. 공통 성공 JSON은 반환하지 않습니다. 설명의 로그인 링크는 현재 BE 호스트를 기준으로 하며 localhost 주소를 고정하지 않습니다.
- 실제 로그인은 링크를 통한 브라우저 이동으로 진행합니다. Swagger의 Try it out은 브라우저 페이지 이동을 대신하지 않습니다. 제공자가 호출하는 `/login/oauth2/code/{provider}` 콜백과 기존 성공 FE 리다이렉트는 그대로 유지합니다. 콜백 실패의 `401 OAUTH_LOGIN_FAILED`를 로그인 시작의 응답으로 혼동하지 않습니다.
- 로그인 후 같은 브라우저의 Swagger에서 `/api/users/me`를 확인할 수 있습니다. 변경 API를 호출하기 전 CSRF를 다시 발급하고 Authorize에 설정합니다. 이 문서 등록은 OAuth 처리 Controller나 새로운 인증 방식을 추가하지 않습니다.

### Controller에서 현재 회원 사용

로그인한 회원이 필요한 업무 API는 `global.security`의 `@CurrentUser`와 `AuthenticatedUser`를 사용합니다. 기존 `GET /api/users/me`의 사용 예시는 다음과 같습니다. 보안 요구·성공 및 오류 응답 주석은 실제 `UserController`를 참고합니다.

```java
@GetMapping(value = "/api/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<ApiResponse<MyProfileResponse>> me(
    @CurrentUser AuthenticatedUser currentUser) {
  var profile = profiles.getMe(currentUser.userId());
  return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(ApiResponse.success(profile));
}
```

- `AuthenticatedUser`에는 한마음 사용자 UUID만 들어갑니다. FE가 보내는 인자가 아니며, `@CurrentUser`는 해당 인자를 Swagger의 요청 파라미터·본문에서 숨깁니다. 이 타입에는 반드시 `@CurrentUser`를 사용하고 요청 바인딩 어노테이션을 함께 붙이지 않습니다.
- 공통 처리기는 Spring Security가 복원한 인증 정보를 읽고, `MemberIdentityService`가 사용자 UUID·제공자·제공자 사용자 ID에 대응하는 현재 DB 연결을 확인합니다. 검증은 요청마다 수행하며, DB 확인 없이 세션의 사용자 ID만 전달하지 않습니다.
- 미인증·만료·이전 형식의 세션, 삭제된 회원, 끊어진 소셜 연결, 사용자 ID 불일치는 `401 UNAUTHENTICATED`입니다. 검증에 실패하면 Controller의 업무 메서드를 실행하지 않습니다. DB 장애는 인증 실패로 바꾸지 않고 `500 INTERNAL_ERROR`로 처리합니다.
- Security 필터 검사는 먼저 적용됩니다. 변경 요청의 CSRF 검증이 실패하면 회원 검사에 앞서 `403 FORBIDDEN`이 반환될 수 있습니다. 이 공통 인자가 경로 접근 설정이나 CSRF를 대신하지 않습니다.
- 공개 API에는 이 인자를 일괄 추가하지 않습니다. 회원 인증을 통과했어도 공간 참여·소유권·대상별 권한은 해당 Service가 업무 처리 시점에 별도로 검사해야 합니다. 이 인자에 공간 역할을 고정해 저장하지 않습니다.

## 돌봄 공간 API

2026-10-10 합의에 따른 구현 계약입니다. 세 API 모두 로그인 세션을 사용하며 POST에는 CSRF가 추가로 필요합니다. 요청자 ID는 입력받지 않고 `@CurrentUser`를 사용합니다.

| API | 요청 | 성공 data |
| --- | --- | --- |
| `POST /api/spaces` | JSON `subjectLabel`, `clientRequestId` 모두 필수 | 201 `spaceId`, `membershipId` |
| `GET /api/spaces` | 없음, 페이지네이션 없음 | 200 `items` 배열 |
| `GET /api/spaces/{spaceId}` | UUID path parameter | 200 공간 상세 |

- 생성은 최초 참여자를 소유자로 연결합니다. 호칭은 앞뒤 Unicode 공백 제거 후 1~30 Unicode code point, 한 줄이며 내부 공백을 유지합니다. FE는 UTF-16 `string.length` 대신 정규화한 값의 `Array.from(value).length`와 일치시키고, 줄바꿈은 trim 전에 거부합니다. 대소문자/Unicode 정규화로 호칭을 임의로 합치지 않습니다. 저장할 수 없는 NUL·깨진 surrogate는 400입니다.
- 현재 같은 소유자의 같은 호칭은 금지합니다. 다른 사람이 소유한 같은 호칭의 공간에 참여하는 것은 가능하며 소유권 이전 후에는 이전 소유자가 같은 호칭으로 새 공간을 만들 수 있습니다. 사용자 전체에 대한 호칭 유일성이 아닙니다.
- 목록 항목은 `spaceId`, `subjectLabel`, `membershipId`, `role`(OWNER/MEMBER), `memberCount`입니다. 상세는 여기에 `version`(문자열), `createdAt`, `updatedAt`을 포함합니다. 호칭은 null이 아니며 참여 ID와 역할은 요청자 기준입니다. 개별 응답용 성공 코드를 추가하지 않습니다.
- 목록은 현재 참여하는 ACTIVE 공간만 포함하고 참여 시각 → 공간 ID 오름차순입니다. 결과가 없으면 `items: []`입니다. 인원수는 소유자·본인을 포함한 현재 참여자 수이며 종료/탈퇴 이력이나 참여하지 않은 돌봄 대상은 세지 않습니다.
- 초기 버전은 `"1"`, 생성·수정 시각은 서버가 기록한 동일한 UTC 시각입니다. 성공 응답은 `Cache-Control: no-store`, 생성은 `Location: /api/spaces/{spaceId}`도 제공합니다.

```json
{
  "subjectLabel": "엄마",
  "clientRequestId": "92a91504-7765-4266-a006-ad7a37b4c192"
}
```

| HTTP | Code | 조건 / FE 처리 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수값, UUID 형식, 호칭 길이·한 줄 검증 실패. errors의 field/reason으로 안내 |
| 401 | `UNAUTHENTICATED` | 세션 또는 현재 회원 연결 무효 |
| 403 | `FORBIDDEN` | 현재 참여자가 아니거나 CSRF 실패. 성공 재요청에서 원래 참여가 종료된 경우 포함 |
| 404 | `RESOURCE_NOT_FOUND` | 공간 없음/삭제 진행 중. 성공 요청의 재응답에서 대상이 없어진 경우 포함 |
| 409 | `SPACE_LABEL_DUPLICATED` | 현재 소유한 공간과 호칭 중복. 호칭 수정 후 새 생성 시도로 요청 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 이미 성공한 요청 ID를 다른 정규화된 내용·업무·범위에 사용 |
| 409 | `REQUEST_IN_PROGRESS` | 동일 요청 처리 중. 원래 요청이 실패했다는 뜻이 아님. 같은 ID/내용으로 재시도 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 처리 실패 |

- 같은 사용자·같은 요청 ID·같은 내용의 성공 재전송은 기존 ID로 `201 SUCCESS`를 반환합니다. timestamp는 새 응답 시각이므로 응답 전체 바이트까지 동일한 것은 아닙니다. 새 요청 ID + 같은 호칭은 반복 요청이 아닌 호칭 중복입니다.
- 타임아웃·응답 유실·결과 불명일 때 자동으로 새 ID를 발급하지 않습니다. 한 생성 시도에 같은 UUID를 유지하고, 사용자가 내용을 바꿔 새 작업을 시작하면 새 UUID를 사용합니다.
- 생성·참여·성공 영수증은 전체 커밋 또는 전체 롤백입니다. 현재 동기 생성에서는 성공 결과만 보관하고 실패/롤백은 공간과 영수증을 남기지 않습니다. 실패한 ID라도 FE는 다른 내용의 새 시도에 재사용하지 않습니다.
- 이번 동기 생성은 202나 `/api/commands` 폴링을 사용하지 않습니다. 영수증 자동 만료 정책은 추가하지 않습니다. 저장 구조·잠금·비밀키와 다른 도메인의 Service 사용법은 [공간 설계](architecture.md#돌봄-공간-생성조회)를 따릅니다.
- Swagger 409에는 세 코드의 예시를 모두 제공합니다. 생성 재요청의 권한/대상 오류도 실제 발생 조건으로 문서화합니다. GET 목록에는 400·403·404를 임의로 붙이지 않습니다.

## 가족 초대·참여·구성원 API

2026-10-11 합의에 따른 구현 계약입니다. 네 API 모두 로그인 세션이 필요하며 POST는 CSRF와 필수 UUID `clientRequestId`를 JSON 본문으로 받습니다. 경로의 `spaceId`는 UUID, `invitationToken`은 발급 URL의 마지막 토큰입니다. 공통 응답 형식과 `Cache-Control: no-store`를 사용합니다.

| API | 성공 HTTP | 성공 data |
| --- | --- | --- |
| `POST /api/spaces/{spaceId}/invitations` | 201 | `invitationId`, `spaceId`, `invitationUrl`, `issuedAt`, `expiresAt` |
| `GET /api/invitations/{invitationToken}` | 200 | `invitationId`, `spaceId`, `expiresAt`, `isMember` |
| `POST /api/invitations/{invitationToken}/join` | 신규 201 / 기존 참여 200 | `spaceId`, `membershipId`, `role`, `joinedAt` |
| `GET /api/spaces/{spaceId}/members` | 200 | `items: [{membershipId, displayName, role, isMe}]` |

- 소유자와 현재 구성원 모두 초대 링크를 발급할 수 있습니다. 링크는 발급 시각부터 24시간 동안 여러 사람이 사용할 수 있고 별도 인원·사용 횟수 제한은 추가하지 않습니다. 현재 시각이 `expiresAt` 이상이면 만료입니다.
- 같은 발급 요청은 원래 링크·시각을 201로 반환하며 만료를 연장하지 않습니다. 새 요청 ID로 발급하면 새 링크를 만들고 기존 링크도 원래 만료까지 유지합니다. 설정 주소를 바꾸어도 과거 발급의 재응답 URL은 그대로입니다.
- 링크 확인은 가입 전 이름·호칭·돌봄 내용을 노출하지 않으며 참여를 생성하지 않습니다. 없는 토큰, 삭제 중인 공간, 발급 당시 참여가 종료된 링크는 사용할 수 없습니다. 발급자가 재참여해도 예전 링크는 복구되지 않습니다.
- 첫 참여 시 현재 유효한 링크를 검증하고 새 참여·공간 버전 증가·수정 시각·성공 기록을 하나의 트랜잭션으로 저장합니다. 이미 참여 중이면 같은 참여 ID를 200으로 반환하며 소유자의 OWNER 역할도 유지합니다. **새 요청은 기존 참여자여도 링크 유효성을 검사합니다.**
- 성공한 참여 요청의 같은 ID 재전송은 최초 201/200을 유지합니다. 이후 링크가 만료되거나 발급자가 나갔어도 동일한 현재 참여가 유지되면 응답할 수 있습니다. 역할은 현재 기준입니다. 요청자의 원래 참여가 끝났다면 403이며 재참여해도 과거 요청이 새 참여를 대신하지 않습니다.
- 나가기·내보내기 이력이 있는 사용자는 가장 최근 `endedAt`보다 **엄격히 나중에 발급된 링크**와 새 요청 ID로 재참여합니다. 새 참여 ID를 만들고 이전 이력을 복원하지 않습니다. 참여 종료는 아래 API에서 처리합니다.
- 구성원 목록은 현재 참여자 전체이며 소유자 우선 → 참여 시각 → 참여 UUID 오름차순입니다. `displayName`은 null 가능, 역할은 OWNER/MEMBER, `isMe`는 본인 여부입니다. 페이지네이션과 아직 구현하지 않은 담당 할 일 개수는 포함하지 않습니다.
- 초대 발급·확인·목록·기존 참여 확인·성공 재전송은 공간 버전과 수정 시각을 바꾸지 않습니다. 새 참여만 한 번 증가시킵니다. 클라이언트가 `expectedVersion`을 보내는 API는 아닙니다.

| HTTP | Code | 발생 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 요청 ID 누락/UUID 형식, spaceId 형식, 잘못된 JSON |
| 401 | `UNAUTHENTICATED` | 세션 또는 현재 회원 연결 무효 |
| 403 | `FORBIDDEN` | CSRF 실패, 발급·구성원 조회의 현재 참여 없음, 성공 재요청의 원래 참여 종료 |
| 404 | `RESOURCE_NOT_FOUND` | 발급·구성원 조회 대상 공간 없음/DELETING |
| 404 | `INVITATION_UNAVAILABLE` | 토큰 형식/존재, 공간 상태, 발급 참여 유효성 또는 재참여 링크 조건 실패 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 사용자·요청 ID를 다른 업무/공간/초대에 재사용 |
| 409 | `REQUEST_IN_PROGRESS` | 동일 사용자·요청 ID를 처리하는 트랜잭션 진행 중 |
| 410 | `INVITATION_EXPIRED` | 유효성을 확인한 링크의 만료 시각 도달 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 처리 실패 |

오류는 각 API에 실제 해당하는 경우만 적용합니다. 링크 자체가 무효이면 만료보다 404를 우선합니다. 자동 재시도는 원래 ID·내용을 유지하고 409 처리 중을 최종 실패로 취급하지 않습니다. 성공 기록에 TTL은 추가하지 않습니다.

FE는 초대 경로·비로그인 사용자의 로그인 후 복귀를 합의해야 합니다. 현재 URL은 `INVITATION_URL_PREFIX`로 분리했고 기존 OAuth 리다이렉트는 변경하지 않았습니다. FE는 발급 URL을 그대로 복사하고 토큰을 추출해 확인/참여 API를 호출하며, 세션·CSRF 및 404/410 안내를 연결합니다. URL 토큰을 분석 이벤트·로그에 남기지 않습니다. AI 의존성은 없습니다.

## 소유권 이전·공간 나가기·구성원 내보내기 API

2026-10-11 합의. 세 API는 로그인 세션·CSRF·JSON 본문이 필요하고 `200 SUCCESS`, 공통 6필드 응답 및 `Cache-Control: no-store`를 사용합니다.

| API | 추가 대상 입력 | 권한 | 성공 `data` |
| --- | --- | --- | --- |
| `POST /api/spaces/{spaceId}/ownership-transfers` | 본문의 `targetMembershipId`(UUID) | 현재 소유자 | `spaceId`, `ownerMembershipId`, `myRole`, `spaceVersion` |
| `POST /api/spaces/{spaceId}/departures` | 없음. 서버가 본인의 현재 참여 식별 | 현재 MEMBER | `membershipId`, `endedAt` |
| `POST /api/spaces/{spaceId}/memberships/{membershipId}/removals` | 경로의 `membershipId`(UUID) | 현재 소유자 | `membershipId`, `endedAt`, `spaceVersion` |

공통 본문은 다음과 같습니다. 이전 요청에는 `targetMembershipId`를 추가합니다.

```json
{
  "expectedSpaceVersion": "3",
  "clientRequestId": "832cbe35-9970-4f99-a219-8f019315b3ab"
}
```

- 두 필드는 필수입니다. `expectedSpaceVersion`은 공간 상세의 `version`이며 1~9223372036854775807의 십진 정수 **문자열**입니다. JSON 숫자·부호·공백·소수·선행 0은 허용하지 않습니다. 저장된 BIGINT 표현 범위이며 제품상의 사용 횟수 제한이 아닙니다.
- 새 의도에는 새 `clientRequestId`, 응답 유실·처리 중 재시도에는 동일 ID·대상·버전을 포함한 동일 본문을 사용합니다. 버전 충돌 뒤 최신 내용을 확인해 변경하는 요청은 새 ID를 사용합니다.
- 권한 확인 뒤 버전을 검사합니다. 소유자의 나가기는 `OWNER_TRANSFER_REQUIRED`를 우선합니다. 허용된 사용자가 요청한 대상이 없거나 다른 공간 소속이면 404, 현재 참여가 종료됐거나 본인/소유자를 이전·내보내기 대상으로 지정하면 `MEMBERSHIP_STATE_CONFLICT`입니다.
- 소유권은 별도 수락 없이 즉시 이전합니다. 현재 다른 참여자 한 명에게 소유자 참여 ID·사용자 ID를 함께 옮기고 기존 소유자는 MEMBER가 됩니다. 새 소유자의 동일 호칭 소유 충돌은 전체 롤백합니다. 역할만 바뀌면 참여 종료가 아니므로 기존 초대는 만료·발급 참여 기준으로 유지합니다.
- 나가기는 `LEFT`, 내보내기는 `REMOVED`로 참여 행의 종료 사유와 시각을 기록합니다. 회원·소셜 연결·다른 공간 참여를 삭제하지 않습니다. 이후 해당 공간의 조회/변경과 종료된 참여에서 발급한 링크 사용을 거절합니다.
- 실제 변경·공간 버전 1 증가·수정 시각 갱신·성공 기록은 한 트랜잭션입니다. 실패·조회·같은 성공 요청 재전송에는 갱신하지 않습니다. 시각은 서버 UTC, PostgreSQL과 같은 마이크로초 정밀도입니다.
- 성공 결과의 버전과 `myRole`은 **최초 성공 당시 결과**입니다. 이전의 `myRole`은 MEMBER이며 재전송도 같은 결과를 반환합니다. 현재 역할·소유권·구성원은 별도 조회합니다.

### 성공 재전송의 권한

| API | 재전송 조건 |
| --- | --- |
| 소유권 이전 | 원래 요청자의 동일 참여가 현재도 유지되어야 합니다. 이미 MEMBER가 된 것은 허용하지만 종료 후 재참여한 새 참여는 403입니다. 없음/DELETING 공간은 404입니다. |
| 본인 나가기 | **예외:** 로그인한 동일 회원의 동일 성공 요청이면 원래 종료 참여 ID·시각만 반환합니다. 공간 삭제 진행이나 이후 재참여 여부에 관계없이 새로운 참여를 변경하지 않습니다. 공간 정보·현재 버전·다른 사람 정보는 반환하지 않습니다. 회원 인증이 무효이면 401입니다. |
| 구성원 내보내기 | 원래 요청자의 동일 현재 참여 및 현재 소유권을 요구합니다. 대상이 재참여했어도 원래 종료 결과만 반환하며 새 참여를 종료하지 않습니다. |

| HTTP | Code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 필수값·UUID·버전 문자열·JSON 형식 오류 |
| 401 | `UNAUTHENTICATED` | 세션 또는 현재 회원 연결 무효 |
| 403 | `FORBIDDEN` | 현재 참여/소유권 또는 CSRF 실패, 재전송 권한 없음 |
| 404 | `RESOURCE_NOT_FOUND` | 공간 없음/DELETING 또는 대상 참여 없음/다른 공간 |
| 409 | `VERSION_CONFLICT` | 검토한 버전과 현재 버전 불일치 |
| 409 | `SPACE_LABEL_DUPLICATED` | 이전받는 사용자가 같은 호칭의 다른 공간을 현재 소유 |
| 409 | `OWNER_TRANSFER_REQUIRED` | 현재 소유자가 나가기 요청 |
| 409 | `MEMBERSHIP_STATE_CONFLICT` | 종료된 참여 또는 본인/소유자를 이전·내보내기 대상으로 요청 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 성공한 ID를 다른 작업·공간·대상·버전에 재사용 |
| 409 | `REQUEST_IN_PROGRESS` | 같은 회원·요청 ID 처리 중 |
| 500 | `INTERNAL_ERROR` | 내부 처리 실패 |

추가한 두 상태 오류는 `CareSpaceErrorCode`에서 관리합니다. 기존 노션 초안의 포괄적인 `STATE_CONFLICT` 대신 `MEMBERSHIP_STATE_CONFLICT`를 사용합니다. 오류는 실제 적용되는 API에만 명시합니다.

### FE 연동과 후속 도메인 계약

- 이전·내보내기 성공 시 역할·구성원·공간 상세를 재조회합니다. 나가기 성공 시 해당 공간 캐시를 제거하고 참여 공간 목록으로 이동합니다. 대상 사용자의 다른 브라우저는 다음 API의 403/404에서 캐시·화면을 정리해야 하며, 서버가 이미 표시된 내용을 회수하거나 실시간 알림을 보내지는 않습니다.
- VERSION_CONFLICT는 최신 내용 조회 후 사용자 재확인이 필요합니다. 버전만 자동 교체해 재실행하지 않습니다. 재전송 결과의 과거 버전·역할로 현재 캐시를 덮어쓰지 않습니다.
- CareItem은 미구현입니다. **미완료 할 일 담당 해제·종료된 참여의 편집 잠금 무효화는 아직 동작하지 않습니다.** 실제 담당 해제 집계가 없어 `unassignedTaskCount`를 응답에서 제외합니다. 할 일 기능 도입 전에 참여 종료와 같은 트랜잭션으로 해당 Service를 연결하고 영향 항목 버전·이력을 갱신해야 합니다. 다른 편집 잠금이 권한 종료를 지연시키면 안 됩니다. 완료 상태·기존 공동 기록은 보존합니다.
- Proposal의 변경 요청·최종 반영은 미구현입니다. 합의한 후속 계약은 소유권 이전 후 새 소유자가 대기 요청을 검토하고, 종료된 참여자가 제출한 미처리 요청은 반영을 막되 이력을 보존하는 것입니다. 최종 반영 시 현재 소유권·요청자의 원래 참여 상태를 다시 검사해야 합니다.
- 공유 링크의 접근 종료도 Sharing 구현 시 현재 참여를 검사해야 합니다. 이번 세 API를 구현한 것이 CareItem·Proposal·Sharing까지 포함한 전체 제품 정책 완료를 뜻하지 않습니다. AI 호출은 없습니다.

## 소식 원본 저장·조회 API

2026-10-12 합의 기준입니다. 저장·조회 및 최초 분석 접수를 구현합니다. 실제 분석은 외부 설정으로 활성화한 실행기가 처리합니다. 이 절은 BE 구현 계약이며 노션의 기존 상세 페이지 템플릿을 변경하지 않습니다.

| API | 입력 | 성공 |
| --- | --- | --- |
| `POST /api/spaces/{spaceId}/source-records` | 본문 `content`, `clientRequestId` | `201 SUCCESS`, `recordId`, `version`, `recordedAt`, `analysis` |
| `GET /api/spaces/{spaceId}/source-records` | 선택 query `cursor`, `size` | `200 SUCCESS`, `items`, `nextCursor`, `hasNext` |
| `GET /api/spaces/{spaceId}/source-records/{recordId}` | 공간·소식 UUID | `200 SUCCESS`, 원본 상세 |

### 입력·저장 계약

```json
{
  "content": "오늘 병원에서 다음 달 15일에 다시 오라고 했어요.\n약은 그대로 복용합니다.",
  "clientRequestId": "4880377e-81b6-4ab2-bcff-5dfb804583dd"
}
```

- 소유자와 현재 구성원 모두 작성·조회할 수 있습니다. 세션이 필요하며 POST에는 CSRF가 필요합니다. 응답은 공통 여섯 필드와 `Cache-Control: no-store`를 사용합니다. POST `Location`은 생성한 소식의 상세 경로입니다.
- `content`는 문자열 필수이며 빈 문자열·Unicode 공백만 있는 값은 거절합니다. 최대 10,000 UTF-16 코드 단위로 Java String.length 및 FE JavaScript 문자열 length와 같습니다. 일반 한글은 1, 보충 평면의 이모지 등은 2입니다. NUL·짝이 맞지 않는 surrogate는 저장할 수 없어 400으로 거절합니다.
- 유효 원문의 앞뒤 공백·줄바꿈(CRLF 포함)·Unicode 표현을 그대로 저장합니다. FE는 일반 텍스트로 표시하고 HTML로 해석하지 않습니다. 이 길이는 이번 Record 공개 입력 합의이며 기존 AI 내부 제한과 맞춥니다.
- 작성자 참여·UTC 등록 시각은 서버가 정합니다. 사용자 지정 작성 시각·제목·분류·수정 기능은 추가하지 않습니다. 기록 시각은 마이크로초 정밀도로 고정하고 향후 AI 상대 날짜 해석에서 `Asia/Seoul`과 함께 사용합니다.
- 초기 원본 `version`은 문자열 `"1"`입니다. 원본 작성에는 `expectedSpaceVersion`을 요구하지 않고 공간 버전·수정 시각을 변경하지 않습니다. 실제 권한은 트랜잭션 안에서 현재 상태로 검사합니다.
- 같은 사용자·요청 ID·업무·공간·원문의 성공 재전송은 최초 원본 ID·시각을 201로 반환합니다. 원문이 공백/줄바꿈 하나라도 달라지면 다른 내용입니다. 같은 원문이라도 새 요청 ID면 별도 소식입니다. 성공 기록에는 원문·전체 응답을 저장하지 않습니다.
- 재전송에서도 원래 참여가 현재까지 유지되어야 합니다. 나갔다가 재참여한 경우 과거 요청은 403이며 새 요청 ID로 작성합니다. 공간이 없거나 삭제 중이거나 원본이 삭제됐으면 404이고 원본을 복구하지 않습니다. 보관 TTL은 추가하지 않습니다.

### 목록·상세 계약

- 목록은 `recordedAt DESC, recordId DESC`입니다. `size`는 기본 20, 1~50이며 첫 조회에는 cursor를 생략합니다. 빈 커서·형식 오류·다른 공간 커서는 400입니다.
- `data.items` 원소는 `recordId`, `content`(원문 전체), `author`, `recordedAt`, `isMine`, `latestAnalysis`입니다. 마지막 페이지 또는 빈 목록은 `nextCursor: null`, `hasNext: false`입니다. 나머지는 반환된 커서를 그대로 다음 요청에 전달합니다.
- 커서는 마지막 반환 원본의 시각·ID 위치이며 당시 전체 목록을 동결하지 않습니다. 이후 새로 들어온 최신 소식은 첫 목록 재조회로 확인합니다. 중간 삭제를 반영하며 커서 기준 원본이 삭제돼도 다음 위치를 조회할 수 있습니다. 매 페이지 현재 권한을 다시 검사하고 총 개수·검색·필터는 추가하지 않습니다.
- 상세는 `recordId`, `spaceId`, `content`, `author`, `recordedAt`, `version`, `isMine`, `latestAnalysis`, `proposalIds`, `canDelete`입니다. 다른 공간의 소식 ID·삭제 원본은 404입니다.
- `author`는 작성 당시 `membershipId`, 현재 표시 이름 `displayName`, `withdrawn`입니다. 이름 미설정은 null이며 회원 연결이 제거된 경우 ID·이름도 null, withdrawn=true입니다. 공간에서 나가기/내보내기만으로 withdrawn=true가 되거나 원본이 삭제되지 않습니다. 실제 회원 탈퇴 처리는 별도 후속 작업입니다.
- `isMine`은 같은 회원 기준으로 재참여 후에도 true일 수 있습니다. `canDelete`는 현재 소유자 또는 본인 원본의 권한 안내입니다. 삭제 API는 아직 없으며 FE 삭제 버튼 연결은 후속 삭제 구현 때 진행합니다.
- 새 소식 저장의 `analysis`는 `{analysisId, status: "QUEUED"}`이며 원본과 같은 트랜잭션에서 생성한 실제 접수 정보입니다. 생성 재전송에도 최초 ID·QUEUED를 반환합니다. 조회의 `latestAnalysis`는 `{analysisId, status}`로 최신 실행의 현재 상태입니다. 분석 도입 전 원본에는 null을 유지하고 자동 일괄 분석하지 않습니다.
- 상세의 `proposalIds`는 최신 실행이 COMPLETED인 경우의 유효한 저장 제안 ID이며 나머지는 빈 배열입니다. 원본 삭제 API와 전체 파생 내용 제거는 후속 작업입니다.

| HTTP | Code | 발생 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 본문·UUID·요청 ID·size·cursor 검증 실패 |
| 401 | `UNAUTHENTICATED` | 세션 또는 현재 회원 연결 무효 |
| 403 | `FORBIDDEN` | 현재 참여 없음, POST CSRF 실패, 재전송의 원래 참여 종료 |
| 404 | `RESOURCE_NOT_FOUND` | 공간 없음/DELETING, 원본 없음/ERASED/다른 공간 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 동일 사용자·요청 ID를 다른 업무·공간·본문에 사용 |
| 409 | `REQUEST_IN_PROGRESS` | 동일 사용자·요청 ID 트랜잭션 처리 중 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |

별도 Record 오류 enum은 만들지 않습니다. FE는 POST마다 새 의도에 UUID를 발급하고 네트워크 재전송에는 같은 값을 유지합니다. AI는 고정된 원문·recordedAt·Asia/Seoul을 사용하며 HTTP 호출 동안 DB 트랜잭션을 유지하지 않습니다.

## 소식 분석 상태·실패 재시도

현재 공간 참여자(소유자·구성원)가 사용합니다. 공통 세션 인증과 여섯 필드 응답·no-store를 사용하고 POST에는 CSRF가 필요합니다. 다른 공간의 원본·분석 ID를 사용할 수 없습니다.

| API | 입력 | 성공 |
| --- | --- | --- |
| `GET /api/spaces/{spaceId}/source-records/{recordId}/analyses/{analysisId}` | 경로 UUID | `200 SUCCESS`, 분석 상태 |
| `POST /api/spaces/{spaceId}/source-records/{recordId}/analyses` | 아래 JSON | `202 ACCEPTED`, `{recordId, analysisId, status: "QUEUED"}` |

```json
{
  "failedAnalysisId": "183c05e8-03f3-4692-9d9c-30f1c6ea6772",
  "expectedRecordVersion": "1",
  "clientRequestId": "e371364c-5681-46ef-bd89-4e1a23f6c06d"
}
```

세 필드 모두 필수입니다. 버전은 양의 정수를 담은 문자열이며 JSON 숫자는 거절합니다. POST Location은 접수한 분석의 GET 경로입니다. 재시도는 최신 FAILED 실행만 가능하며 성공 재분석·QUEUED/RUNNING의 중복 접수·서버 자동 재시도는 지원하지 않습니다. 원본·공간 버전은 분석 실행으로 증가하지 않습니다.

GET의 `data`:

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `analysisId`, `recordId` | UUID 문자열 | 분석과 원본 식별자 |
| `status` | 문자열 | QUEUED / RUNNING / COMPLETED / FAILED / DISCARDED |
| `proposalIds` | UUID 배열 | 완료한 실행의 유효 제안 ID, 없으면 빈 배열 |
| `failure` | 객체 또는 null | FAILED일 때 `{code, message}`, 그 외 null |
| `createdAt` | UTC 시각 | 분석 접수 시각 |
| `startedAt` | UTC 시각 또는 null | 실제 AI 실행을 시작한 시각. 시작 전 실패·폐기도 null 가능 |
| `finishedAt` | UTC 시각 또는 null | 완료·실패·폐기 시각, 대기·진행 중에는 null |
| `canRetry` | Boolean | 현재 조회자가 접근 가능하고 이 실행이 최신 FAILED일 때 true |

기존 초안에서 등록 시각으로 표현했던 startedAt은 실제 실행 시작 시각으로 구분하고 createdAt을 제공합니다. COMPLETED는 제안 저장 완료이며 소유자의 최종 반영이나 관리 항목 생성 완료를 뜻하지 않습니다. 제안 0개도 완료입니다.

| HTTP | Code | 발생 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | UUID·필수 입력·버전 문자열 검증 실패 |
| 401 | `UNAUTHENTICATED` | 세션 또는 현재 회원 무효 |
| 403 | `FORBIDDEN` | 현재 참여 없음, POST CSRF 실패, 재전송의 원래 참여 종료 |
| 404 | `RESOURCE_NOT_FOUND` | 공간/원본/분석 없음·다른 공간, 삭제 중 공간·삭제 원본, 폐기된 재시도 접수 재전송 |
| 409 | `VERSION_CONFLICT` | 요청 버전과 현재 원본 버전 불일치 |
| 409 | `ANALYSIS_STATE_CONFLICT` | 지정 실행이 최신 FAILED가 아님 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 사용자·요청 ID를 다른 업무/원본/실패 실행/버전에 재사용 |
| 409 | `REQUEST_IN_PROGRESS` | 같은 사용자·요청 ID의 접수 트랜잭션 처리 중 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 API 처리 오류 |

GET에는 재시도 전용 409를 적용하지 않습니다. 비동기 실행 실패는 HTTP 오류가 아닙니다. GET `200 SUCCESS`의 `data.failure`에는 다음 안전한 코드·메시지만 노출하며 AI 응답 원문·내부 오류를 저장하거나 반환하지 않습니다.

| 작업 실패 코드 | 의미 |
| --- | --- |
| `AI_SERVICE_UNAVAILABLE` | AI 연결·응답 실패 또는 계약에 맞지 않는 결과 |
| `ANALYSIS_INTERRUPTED` | 실행기의 생존 갱신 중단·lease 만료 등 실행 중단 |
| `ANALYSIS_REQUESTER_INACTIVE` | 분석을 요청한 당시 참여가 종료됨 |
| `ANALYSIS_DEPENDENCY_UNAVAILABLE` | CareItem 후보·결과 저장 연결이 준비되지 않음 |
| `ANALYSIS_STORAGE_FAILED` | 결과 저장 트랜잭션 실패 |

- 동일 사용자·clientRequestId·내용의 성공 접수 재전송은 원래 analysisId와 접수 당시 QUEUED를 반환합니다. 이후 작업 상태는 GET으로 확인합니다. 완료·실패 후 재전송해도 재실행하지 않습니다. 나갔다가 재참여하면 과거 요청 재전송은 403입니다.
- 같은 실패를 다른 요청 ID로 동시에 재시도하면 하나만 접수됩니다. 이미 후속 실행이 있다면 409입니다. 새 재시도마다 새 analysisId/requestId를 사용하지만 최초 원본 recordedAt과 Asia/Seoul은 유지합니다.
- 소식·분석 접수는 원자적이며 결과 제안·문서·근거·COMPLETED도 원자적입니다. 분석 실패로 원본을 제거하지 않습니다. 원본이 삭제되거나 입력 버전이 달라지면 결과를 폐기합니다. 전체 삭제 API는 후속이며 삭제 연결점은 관련 원본과 같은 트랜잭션에서 호출해야 합니다.
- FE는 저장 응답의 analysisId 또는 최신 상태를 사용해 진행 상황을 조회하고 canRetry일 때 명시적인 재시도 요청을 보냅니다. FE 조회 간격·횟수·사용량 제한은 미정이며 임의의 자동 재시도 루프를 추가하지 않습니다.
- 실행기 기본 비활성 환경에서는 QUEUED에 머뭅니다. 활성화와 필수 외부 설정은 README를 따릅니다. 실행기 수치는 운영 미정이며 자동 테스트의 수치를 운영 기본값으로 사용하지 않습니다.
- AI는 같은 공간의 CareItem 후보·입력 의존성과 CREATE 제안 저장까지 연결합니다. UPDATE/RESOLVE 저장은 후속입니다. 현재 stub/가짜 응답 검증이 실제 모델 품질 검증을 대신하지 않습니다.

## 문서화와 적용 범위

Swagger에 공통 응답·오류 스키마, 재사용 가능한 오류 응답, 세션 쿠키·CSRF 보안 스키마를 둡니다. 공개 경로에 인증을 잘못 표시하지 않도록 전역 보안 요구를 일괄 적용하지 않고 각 API에 필요한 조건과 실제 오류만 명시합니다.

각 Controller의 OpenAPI 주석에서 `SessionCookie`, `CsrfToken` 보안 스키마와 `#/components/responses/코드명` 오류 응답을 참조합니다. 둘 다 필요하면 하나의 보안 요구에 묶어 AND 조건으로 표현합니다. 성공의 `data`는 해당 응답 DTO로 구체화합니다. 모든 예외 처리기의 응답을 모든 API에 자동 추가하는 Springdoc 옵션은 끄고 실제 발생 조건을 명시합니다.

`global.openapi.ErrorResponseDocumentation`은 Swagger가 활성화된 환경에서 `com.hanmaum.backend` 아래의 구체적인 `ErrorCode` 구현 enum을 탐색해 재사용 응답을 등록합니다. 새 도메인 오류 enum 추가 시 공통 enum 목록이나 설정 파일을 수정할 필요가 없습니다. 중복 코드가 발견되면 문서 구성을 실패시켜 기존 예시를 덮어쓰지 않습니다. 이 등록은 각 API의 발생 오류를 자동으로 결정하지 않으므로 Controller의 응답 참조와 명세·테스트도 함께 작성합니다.

CSRF·로그아웃 등 존재하는 경로만 문서화하며 미구현 도메인 API를 문서용 Controller로 만들지 않습니다. Swagger는 브라우저 로그인 세션을 사용하며 임의 쿠키 입력이 실제 인증을 대신하지 않습니다.

공통 handoff와 노션의 최종 동기화는 사용자가 지정한 시점에 진행합니다. FE의 오류 파싱도 새 규격에 맞춰야 합니다. 입력 길이·페이지 크기·잠금 TTL·반복 요청 보관 기간은 이 공통 설정에서 확정하지 않습니다.

## 일정 직접 등록·관리 항목 조회 (#22)

2026-10-14 사용자 요청 범위입니다. 신규 일정 등록과 항목 목록/상세 세 API만 제공하며, 제안 조회는 팀원 #21의 독립 작업입니다. 기반 경로는 `/api/spaces/{spaceId}/items`입니다.

### 등록

`POST /api/spaces/{spaceId}/items`는 현재 소유자·세션·CSRF를 요구하고 `201 SUCCESS`와 Location을 반환합니다.

```json
{
  "itemType": "SCHEDULE",
  "title": "내과 재진",
  "description": null,
  "fields": {
    "scheduledDate": "2026-10-15",
    "scheduledTime": "10:00",
    "location": null
  },
  "clientRequestId": "832cbe35-9970-4f99-a219-8f019315b3ab"
}
```

- `itemType`, 비어 있지 않은 `title`, 객체 `fields`, UUID `clientRequestId`는 필수입니다. 현재 `SCHEDULE`만 등록합니다. 구성원은 직접 등록할 수 없으며 승인 요청은 후속입니다.
- 텍스트는 문자열/null만 받으며 숫자·불리언을 문자열로 변환하지 않습니다. 제목 공백·줄바꿈은 보존하고 길이 제한을 추가하지 않습니다. NUL·잘못된 surrogate는 저장 기술 제약으로 거절합니다.
- `description`, `scheduledDate`, `scheduledTime`, `location`은 생략/null을 같은 없음으로 해석합니다. 날짜는 실제 존재하는 `YYYY-MM-DD`, 시각은 `HH:mm`이며 Asia/Seoul 기준입니다. 날짜나 시각을 추측하지 않으며 00:00 기본값을 넣지 않습니다.
- 요청에 없는 업무 상태·작성자·버전·시각은 서버가 정합니다. 지원 외 필드는 거절합니다. 초기 상태는 `SCHEDULED`, 버전은 문자열 `"1"`입니다.
- 성공 `data`는 `itemId`, `itemType`, `version`, `createdAt`입니다. 일정·직접 입력 문서/근거·최초 이력·영수증은 하나의 트랜잭션에서 저장하며 공간 버전은 바꾸지 않습니다.
- 같은 사용자·요청 ID·작업·공간·내용이면 최초 결과를 반환합니다. 내용/대상이 다르면 `409 IDEMPOTENCY_KEY_REUSED`, 실행 중이면 `409 REQUEST_IN_PROGRESS`입니다. 재전송도 현재 소유자 및 최초 동일 참여를 요구하며 삭제/제거된 문서의 항목은 404입니다. 영수증에 제목·원문·필드를 복제하지 않습니다.

### 목록·상세

- `GET /api/spaces/{spaceId}/items`는 현재 참여자가 전체 조회합니다. `data.items`에 ID·유형·문자열 버전·제목·상태·재확인/상충 표시·유형별 fields·nullable 담당자 표시 이름을 제공합니다. 현재 생성 시각·ID 내림차순이며 별도 페이지 크기·상한은 없습니다.
- `itemType`은 다섯 명세 유형을 받습니다. 다른 유형의 등록은 아직 없어 목록이 비어 있을 수 있습니다. 상태 없이 조회하면 모든 업무 상태를 포함하며 일반 삭제만 제외합니다. `status`는 `itemType`을 함께 지정하고 해당 유형의 상태를 사용합니다.
- 예정/지난 필터는 사용자 선택으로 후속입니다. `scheduleView`를 전달하면 `400 INVALID_REQUEST`이며 무시하지 않습니다. 날짜 경과로 일정을 자동 완료하지 않습니다.
- `GET /api/spaces/{spaceId}/items/{itemId}`는 현재값·상태·버전·시각과 재확인/상충/삭제 표시를 제공합니다. 편집 잠금과 질문 답변은 후속이라 `editLock: null`, `answers: []`입니다.
- 권한과 목록/상세는 같은 SQL 스냅샷에서 조회합니다. 현재 비구성원은 403, 다른 공간의 항목이나 삭제 중인 공간은 404입니다. 조회는 업무 데이터를 변경하지 않으며 응답은 `Cache-Control: no-store`입니다.
- 문서 내용이 제거됐으면 제목·설명/fields를 노출하지 않고 재확인 표시와 `redactedFields`를 반환합니다. 일반 삭제 상세는 내용 없는 삭제 표시입니다. 삭제 API·전체 삭제 전파·근거/이력 조회 API의 완료를 뜻하지 않습니다.

### 저장·분석 연동과 후속

V8은 `care_item`, `care_item_basis`, `care_item_history`, `analysis_input_item`을 추가하고 기존 `content_document`에 DIRECT_INPUT 유형을 허용합니다. V1~V6는 수정하지 않고 V7은 #21용으로 남깁니다. V7/V8 배포 순서를 맞춰야 하며 V7이 이미 적용된 개발 DB는 V7 파일이 포함된 통합 checkout에서 실행해야 합니다.

후보는 같은 공간의 유효한 현재 문서와 근거만 사용합니다. 실행기가 분석을 시작할 때 항목 버전·문서와 원본 의존성을 함께 기록하고 완료 시 재검증합니다. V9는 그때 사용한 근거 ID를 추가로 보존하므로 근거 연결이 없어지거나 원본 버전 변경 없이 근거 내용만 제거되어도 늦은 결과를 폐기합니다. V8에서 근거 목록을 기록하지 않은 후보 실행은 완료를 허용하지 않습니다. 기존 내부 후보 50개·요약 2,000 UTF-16 코드 단위 한도를 초과하면 자동 축약/부분 선택하지 않고 분석 의존성 오류로 종료합니다. 공개 일정 입력 제한과 별개입니다. 현재 summary 표현과 AI 연동 경계는 [AI 계약](ai-contract.md#careitem-후보와-입력-근거-검증)을 따릅니다.

CREATE 제안 저장만 지원합니다. UPDATE/RESOLVE·승인·편집 잠금·항목 수정·TASK/QUESTION 등록·담당자·원본 삭제·현황판·공유는 후속입니다. 데이터 구조의 다른 유형/상태 준비는 해당 업무의 구현 완료를 뜻하지 않습니다.
