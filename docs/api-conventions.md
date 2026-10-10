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
| `carespace/code/CareSpaceErrorCode` | `SPACE_LABEL_DUPLICATED` |
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
| 404 | `RESOURCE_NOT_FOUND` |
| 409 | `VERSION_CONFLICT`, `ITEM_LOCKED`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`, `SPACE_LABEL_DUPLICATED`, `PROPOSAL_BATCH_CONFLICT`, `DELETION_IMPACT_CHANGED` |
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

## 문서화와 적용 범위

Swagger에 공통 응답·오류 스키마, 재사용 가능한 오류 응답, 세션 쿠키·CSRF 보안 스키마를 둡니다. 공개 경로에 인증을 잘못 표시하지 않도록 전역 보안 요구를 일괄 적용하지 않고 각 API에 필요한 조건과 실제 오류만 명시합니다.

각 Controller의 OpenAPI 주석에서 `SessionCookie`, `CsrfToken` 보안 스키마와 `#/components/responses/코드명` 오류 응답을 참조합니다. 둘 다 필요하면 하나의 보안 요구에 묶어 AND 조건으로 표현합니다. 성공의 `data`는 해당 응답 DTO로 구체화합니다. 모든 예외 처리기의 응답을 모든 API에 자동 추가하는 Springdoc 옵션은 끄고 실제 발생 조건을 명시합니다.

`global.openapi.ErrorResponseDocumentation`은 Swagger가 활성화된 환경에서 `com.hanmaum.backend` 아래의 구체적인 `ErrorCode` 구현 enum을 탐색해 재사용 응답을 등록합니다. 새 도메인 오류 enum 추가 시 공통 enum 목록이나 설정 파일을 수정할 필요가 없습니다. 중복 코드가 발견되면 문서 구성을 실패시켜 기존 예시를 덮어쓰지 않습니다. 이 등록은 각 API의 발생 오류를 자동으로 결정하지 않으므로 Controller의 응답 참조와 명세·테스트도 함께 작성합니다.

CSRF·로그아웃 등 존재하는 경로만 문서화하며 미구현 도메인 API를 문서용 Controller로 만들지 않습니다. Swagger는 브라우저 로그인 세션을 사용하며 임의 쿠키 입력이 실제 인증을 대신하지 않습니다.

공통 handoff와 노션의 최종 동기화는 사용자가 지정한 시점에 진행합니다. FE의 오류 파싱도 새 규격에 맞춰야 합니다. 입력 길이·페이지 크기·잠금 TTL·반복 요청 보관 기간은 이 공통 설정에서 확정하지 않습니다.
