# 한마음 백엔드

가족이 현재 유효한 돌봄 정보를 함께 관리하는 한마음의 Spring Boot API 서버입니다.

## 개발 기준

- 개발 기준 브랜치: `dev`
- 배포 기준 브랜치: `main`
- 작업 브랜치: `타입/이슈번호-기능명`
- 커밋: `타입: 변경 내용 (#이슈번호)` 및 상세 본문
- PR: `Type(#이슈번호): 핵심 작업 내용` 형식, `dev` 대상으로 생성하고 팀원 1명 이상의 승인 및 저장소 필수 검사 충족 후 병합
- `dev`·`main` 직접 커밋·푸시 금지. 상세 개발 규칙은 [AGENTS.md](AGENTS.md) 참고

## 기술 스택

Java 21 · Spring Boot 4.1.1 · Gradle 9.7.1 · Spring MVC · JPA · PostgreSQL 17.11 · Flyway

Spring Security OAuth2 Client · Spring Session JDBC · Validation · Springdoc · Actuator · Spotless · Testcontainers

AI 처리는 별도 `hanmaum-ai`의 Python/FastAPI에서 담당합니다. 이 서버는 로그인·권한·원본 기록·사용자 확정·데이터 변경과 이력을 담당합니다.

## 코드 구조

`src/main/java/com/hanmaum/backend` 아래 도메인 중심으로 구성합니다.

```text
backend/
├── auth/              로그인·로그아웃·CSRF (기존 기반)
├── user/              회원·소셜 계정 매핑·탈퇴
├── carespace/         공간·참여·초대·소유권
├── record/            원본 소식 작성·조회·삭제
├── analysis/          분석 실행·상태·재시도
├── proposal/          AI 제안·구성원 변경 요청·최종 반영
├── careitem/          관리 항목·잠금·근거·이력
├── dashboard/         현재 유효한 돌봄 현황 조회
├── sharing/           공유 링크·접근 권한 확인
├── ai/                내부 FastAPI 통신 어댑터 (기존 기반)
│   ├── client/
│   └── dto/
└── global/            공통 개발 기반
    ├── code/          ErrorCode 인터페이스·CommonErrorCode·SuccessCode
    ├── config/
    ├── openapi/       공통·도메인 오류의 Swagger 응답 등록
    ├── security/
    ├── response/
    └── exception/
```

회원·공간·기록·분석·제안·관리 항목·현황판·공유의 8개 도메인 폴더를 미리 준비했습니다. 회원 도메인은 OAuth 회원 매핑·내 정보 조회를, 공간 도메인은 공간 생성·조회·가족 초대·참여·구성원 목록·소유권 이전·참여 종료를 구현했습니다. 기록 도메인은 원본 저장·커서 목록·상세 조회, 분석 도메인은 DB 작업 접수·실행·상태 조회·실패 재시도를 구현했습니다. 제안 도메인은 CREATE 제안·근거 저장과 목록·상세 조회를 구현했으며 수정·최종 반영과 원본 삭제 API는 후속 작업입니다. 패키지별 책임은 [설계 기준](docs/architecture.md)을 따릅니다.

`auth`와 위 8개 업무 도메인은 아래 공통 하위 구조까지 준비했습니다. 빈 폴더에는 `.gitkeep`을 두어 같은 구조를 Git으로 공유합니다. 실제 파일이 있는 폴더에는 `.gitkeep`을 추가하지 않습니다.

```text
각 업무 도메인/
├── code/              해당 도메인의 오류 코드
├── controller/
├── service/
├── repository/
├── entity/
└── dto/
```

담당자는 실제 파일을 추가할 때 해당 폴더의 `.gitkeep`을 제거하고, 기능에 필요 없는 계층은 삭제하거나 조정할 수 있습니다. 예를 들어 `dashboard/entity`는 구조를 맞춰 둔 빈 폴더이며 별도의 현황판 테이블이 필요하다는 의미는 아닙니다. 기존 `ai`와 `global`은 각자의 통신·공통 기반 구조를 유지합니다.

공간 API는 `carespace/controller`, 공간 업무 로직은 `carespace/service`에 둡니다. 초대는 `carespace`에 포함하고, 공개 분석 업무인 `analysis`는 내부 통신을 맡은 `ai`를 사용합니다.

오류 코드의 공통 인터페이스는 `global/code/ErrorCode.java`, 공통 오류 enum은 `CommonErrorCode.java`입니다. 도메인 오류 enum도 같은 인터페이스를 구현하며 `ApiException`과 `ApiResponse`를 함께 사용합니다. 현재 `AuthErrorCode`의 OAuth 실패, `CareSpaceErrorCode`의 호칭 중복·초대 사용 불가·만료, `AnalysisErrorCode`의 재시도 상태 충돌, `CareItemErrorCode`의 항목 잠금, `ProposalErrorCode`의 제안 묶음 충돌을 분리했습니다. `AnalysisFailureCode`는 비동기 작업의 실패 사유로 HTTP 오류와 구분합니다.

성공은 `SuccessCode`의 `SUCCESS`·`ACCEPTED`를 재사용합니다. HTTP 상태는 Controller에서 지정하고 204는 본문 없이 반환합니다. 오류 코드 추가와 Swagger 문서화 방법은 [공통 규격](docs/api-conventions.md)을 따릅니다.

## 빠른 시작

필수 환경은 **JDK 21**과 **실행 중인 Docker Desktop(Linux 컨테이너)**입니다. Gradle은 저장소의 Wrapper를 사용합니다.

PowerShell에서 저장소 루트를 기준으로 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up -d --wait
.\gradlew.bat bootRun
```

기존 `.env`가 있다면 복사하지 않고 필요한 값을 확인합니다. 기본 프로필은 `local`입니다.

- 상태 확인: `http://localhost:8080/actuator/health`
- API 문서: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- CSRF 토큰: `http://localhost:8080/api/auth/csrf`

Git Bash/macOS/Linux에서는 `cp .env.example .env`, `./gradlew bootRun`을 사용합니다.

`.env`는 Spring의 `.properties` 문법으로 읽으므로 값에 따옴표를 붙이지 않습니다. 로컬 기본 DB 비밀번호는 개발 전용입니다. 포트가 이미 사용 중이면 `.env`의 `POSTGRES_PORT` 또는 `SERVER_PORT`를 변경합니다.

DB를 종료할 때는 `docker compose down`을 사용합니다. `docker compose down -v`는 저장된 데이터를 삭제하므로 일반 종료에 사용하지 않습니다.

## 포맷·테스트·빌드

```powershell
git config core.hooksPath .githooks
.\gradlew.bat spotlessApply
.\gradlew.bat check bootJar
```

- Java 포맷은 Google Java Format으로 통일합니다. VS Code의 `Java: format` 작업으로도 실행할 수 있습니다.
- Git 훅은 관련 파일 커밋 시 `spotlessCheck`를 실행합니다. 각 팀원은 클론 후 훅 경로를 한 번 설정합니다.
- 전체 테스트는 Docker가 필요합니다. Testcontainers가 격리된 PostgreSQL을 생성하므로 로컬 개발 DB를 변경하지 않습니다.
- 테스트는 실제 소셜 로그인이나 유료 LLM API를 호출하지 않습니다.
- 테스트 결과: `build/reports/tests/test/index.html`
- 실행 파일: `build/libs/hanmaum-backend.jar`

Docker 없이 AI HTTP 계약 테스트만 실행할 때는 다음 명령을 사용합니다.

```powershell
.\gradlew.bat test --tests '*HanmaumAiClientTests'
```

## CI

[Backend CI](.github/workflows/ci.yml)는 GitHub Actions에서 아래 조건으로 실행합니다.

| 이벤트 | 실행 조건 |
| --- | --- |
| PR | 대상 브랜치 제한 없이 `opened`, `synchronize`, `reopened` 실행. 스택 PR 포함 |
| Push | `dev`, `main` 브랜치만 실행. 머지 후 최종 코드 재검증 |

`Build and test` 작업은 Ubuntu 24.04·JDK 21에서 저장소의 Gradle Wrapper로 `./gradlew check bootJar --no-daemon`을 실행합니다. Spotless 포맷, 전체 테스트, 실행 JAR 생성을 검사하며 최대 실행 시간은 20분입니다. 같은 PR이나 브랜치의 새 실행이 시작되면 이전 실행을 취소합니다.

- Testcontainers가 PostgreSQL을 실행하므로 별도 DB 서비스 설정이나 실제 OAuth·AI API 키가 필요하지 않습니다.
- Gradle 캐시는 PR에서 읽기만 하며, `dev`·`main` push에서 갱신합니다. 공식 Action은 전체 커밋 SHA로 고정합니다.
- 테스트 보고서가 생성되면 성공·실패 실행 모두 `backend-test-reports` 아티팩트로 7일간 보관합니다. 취소된 실행은 업로드를 생략합니다.
- 실패 시 GitHub의 **Actions → Backend CI → 해당 실행 → Build and test** 로그와 테스트 보고서를 확인합니다. 로컬에서는 Docker를 실행한 뒤 `.\gradlew.bat check bootJar --no-daemon`으로 확인합니다.
- 워크플로우 YAML도 Spotless의 공통 파일 포맷 검사 대상에 포함합니다.

CI 파일 추가만으로 머지 제한이 설정되지는 않습니다. 첫 GitHub 실행을 확인한 뒤 `dev`·`main` 대상 Ruleset에서 `Build and test`를 필수 상태 검사로 등록하고 **Require branches to be up to date before merging**을 활성화해야 합니다. 이 저장소 설정은 별도 적용 대상이며, 스택 PR의 중간 대상 브랜치까지 자동으로 보호하지는 않습니다.

CI에는 배포나 머지 자동 취소를 포함하지 않습니다. 머지 후 검사 실패는 로그를 확인해 수정 PR 또는 revert PR로 처리합니다.

## 환경 설정

| 프로필 | 용도 |
| --- | --- |
| `local` | `.env` 읽기, localhost DB, 개발용 Swagger와 CORS |
| `test` | Testcontainers DB, 외부 API 모킹 |
| `prod` | 환경변수 필수, HTTPS 쿠키, Swagger 비활성화 |
| `oauth` | 구글·카카오 OAuth 제공자 설정 추가 |

운영 필수 환경변수는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FRONTEND_URL`, `AI_BASE_URL`, `AI_INTERNAL_API_KEY`, `COMMAND_DIGEST_KEY`, `INVITATION_ENCRYPTION_KEY`, `INVITATION_URL_PREFIX`입니다. 예를 들어 DB URL은 `jdbc:postgresql://postgres:5432/hanmaum` 형태입니다.

`COMMAND_DIGEST_KEY`는 반복 요청 본문 비교용 HMAC 비밀키입니다. 운영에서는 충분한 난수로 생성한 32바이트 이상의 값을 주입하고 서버 재시작·여러 인스턴스에서 유지합니다. 키를 단순 교체하면 기존 요청 영수증과 비교할 수 없으므로 이관 계획이 필요합니다. local/test에는 개발 전용 기본값이 있어 기존 `.env`로도 실행할 수 있으며, 이를 운영에서 사용하지 않습니다.

`INVITATION_ENCRYPTION_KEY`는 같은 발급 요청에 원래 초대 URL을 다시 반환하기 위한 암호화 키입니다. 운영에서는 암호학적 난수 32바이트를 Base64로 인코딩한 값을 비밀 관리 환경에서 주입합니다. `COMMAND_DIGEST_KEY`와 별개이며 모든 인스턴스·재시작에서 유지합니다. 단순 교체하면 이전 발급 URL을 복원할 수 없으므로 키 이관이 필요합니다. local/test는 개발 전용 기본값을 사용하여 기존 `.env`로 실행할 수 있습니다.

`INVITATION_URL_PREFIX`는 토큰 앞에 붙일 FE 주소이며 `/`로 끝나고 query·fragment·인증 정보를 포함하지 않는 절대 HTTP(S) URL입니다. local 기본값은 `http://localhost:5173/invitations/`이고 운영은 실제 HTTPS 주소를 설정합니다. FE 경로와 로그인 후 초대로 돌아오는 흐름은 아직 합의 전입니다. 이 기본값은 로컬 검증용이며 해당 FE 화면 구현을 뜻하지 않습니다. 기존 로그인 성공 URL은 유지합니다.

Flyway가 세션 테이블을 포함한 스키마 변경을 담당하고, Hibernate는 `validate`로 검증합니다. 적용된 마이그레이션은 수정하지 않고 다음 버전의 SQL을 추가합니다. 운영 세션에는 `HttpOnly`, `Secure`, `SameSite=Lax`를 적용합니다.

## 소셜 로그인 연동 준비

개발자 콘솔에서 다음 Redirect URI를 등록합니다. 운영에서는 실제 HTTPS 도메인으로 교체합니다.

- Google: `http://localhost:8080/login/oauth2/code/google`
- Kakao: `http://localhost:8080/login/oauth2/code/kakao`

`.env`에 각 제공자의 Client ID와 Client Secret을 설정한 뒤 실행합니다. Kakao는 REST API 키를 Client ID로 사용하고, Client Secret 및 `profile_nickname` 동의 항목을 제공자 설정과 맞춥니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local,oauth'
```

로그인 시작 주소는 `/oauth2/authorization/google`, `/oauth2/authorization/kakao`입니다.

OAuth 제공자가 설정된 환경에서는 Swagger **Auth → GET /oauth2/authorization/{provider}**에도 로그인 시작 경로를 표시합니다. `provider`는 현재 설정된 `google`·`kakao` 중 선택하며 로그인 세션·CSRF 토큰은 필요하지 않습니다. 실제 로그인은 설명의 **Google/Kakao 로그인 시작** 링크를 브라우저에서 열어 진행합니다. 정상 응답은 JSON 대신 `302 Location`으로 제공자 화면을 안내합니다. Swagger의 Try it out으로 로그인 화면 이동을 테스트하지 않습니다.

현재는 **OAuth 로그인 시 회원 생성·조회, 회원 세션과 내 정보 조회까지 구현한 상태**입니다. Flyway V2는 `app_user`·`social_account`를 추가하고, `(provider, provider_user_id)` 유일성 및 사용자 외래 키를 검사합니다. ID는 JPA에서 UUID로 생성하고 생성·수정 시각은 서버에서 기록합니다.

- Google은 검증된 ID Token의 `sub`, Kakao는 사용자 정보의 `id`를 제공자와 함께 회원 식별에 사용합니다. 이메일 일치만으로 계정을 병합하지 않습니다.
- 표시 이름은 최초 가입 때 Google의 `name` 또는 Kakao의 `kakao_account.profile.nickname`으로 저장합니다. 값이 없거나 비어 있으면 null이며 재로그인 때 기존 이름과 프로필 수정 시각을 덮어쓰지 않습니다. 이 동작은 2026-10-09 사용자 합의이며 별도 이름 수정 API의 입력 제한은 미정입니다.
- 제공자 통신 후 회원·소셜 연결을 하나의 DB 트랜잭션에서 생성합니다. 같은 제공자 식별자의 동시 로그인은 PostgreSQL 트랜잭션 잠금으로 직렬화하며 저장 실패 시 함께 롤백합니다.
- 기존 콜백 주소·로그인 성공 리다이렉트·`OAUTH_LOGIN_FAILED` 응답을 유지합니다. 자동 검증은 로컬 가짜 제공자와 격리 PostgreSQL을 사용하며 실제 Google·Kakao 회원 매핑은 별도로 확인해야 합니다.

로그인 성공 시 세션 principal에 한마음 사용자 UUID를 연결하며 인증 이름과 JDBC 세션의 `principal_name`도 이 UUID를 사용합니다. Google은 OIDC 사용자 형식을 유지합니다. 이전 버전의 제공자 전용 세션은 자동 변환하지 않으므로 새 버전 적용 후 다시 로그인해야 합니다.

`GET /api/users/me`는 세션의 본인만 조회합니다. 요청 본문·사용자 ID 입력·CSRF 토큰은 필요 없으며 공통 성공 응답의 `data`에 `userId`, `displayName`(null 가능), `providers`(`GOOGLE`/`KAKAO`)를 반환합니다. 표시 이름과 로그인 방식은 현재 DB에서 조회하고 제공자 식별자·이메일·토큰은 응답에 포함하지 않습니다. 세션 없음·만료·이전 형식 또는 회원/소셜 연결 무효는 `401 UNAUTHENTICATED`입니다. 공통 성공 메시지는 `요청이 완료되었습니다.`를 사용합니다.

다른 도메인의 로그인 필수 업무 API도 `@CurrentUser AuthenticatedUser`로 검증된 현재 회원을 받고 `currentUser.userId()`를 Service에 전달합니다. 회원·소셜 연결의 DB 검증은 공통 처리하며 공간 참여·소유권 검사는 각 업무에서 수행합니다. 사용 예시와 적용 규칙은 [현재 회원 사용](docs/api-conventions.md#controller에서-현재-회원-사용)을 따릅니다.

로컬 확인은 같은 브라우저에서 소셜 로그인 후 `http://localhost:8080/api/users/me`에 접속하거나 Swagger의 **User → GET /api/users/me → Try it out → Execute**로 진행합니다. 로그인한 브라우저 세션의 회원을 현재 서버에 설정된 DB에서 조회합니다. 기본 `local` 설정에서는 Docker PostgreSQL 개발 DB이며 Swagger 주소 자체가 DB를 선택하지는 않습니다.

계정 연결과 회원 탈퇴 업무는 아직 구현하지 않았습니다. 사용자 행 삭제 시 소셜 연결을 제거하는 FK와 내 정보 조회의 유효성 검사가 회원 탈퇴 전체 정책이나 모든 세션의 즉시 종료를 구현한 것은 아닙니다.

로그인 실패 응답은 `401 OAUTH_LOGIN_FAILED`를 유지합니다. 서버의 `OAuth login failed` 로그에는 제공자·허용된 원인 분류·세션/필수 파라미터 존재 여부만 남깁니다. `AUTHORIZATION_REQUEST_MISSING`은 콜백과 대응하는 저장된 요청을 찾지 못한 경우이며, 토큰 교환·ID Token 검증·회원 저장 실패와 구분합니다. 로그에 인증 코드·토큰·쿠키·제공자 원문·예외 메시지를 추가하지 않습니다.

## 프론트엔드의 세션·CSRF 사용

로컬에서 프론트와 백엔드 모두 `localhost`를 사용합니다. 요청에는 `credentials: 'include'`를 설정합니다.

```javascript
const csrf = await fetch('http://localhost:8080/api/auth/csrf', {
  credentials: 'include',
}).then((response) => response.json());

// POST / PUT / PATCH / DELETE 요청의 headers에 추가합니다.
const headers = {
  'Content-Type': 'application/json',
  [csrf.headerName]: csrf.token,
};
```

로그인 성공·로그아웃 후에는 CSRF 토큰을 다시 받습니다. 로그아웃은 토큰을 포함한 `POST /api/auth/logout`입니다. 운영에서는 Nginx가 프론트와 백엔드를 같은 도메인으로 제공하도록 구성합니다.

## API 공통 규격

공개 JSON 응답은 `success`, `code`, `message`, `data`, `errors`, `timestamp` 여섯 필드를 사용합니다. 오류는 `data: null`, 필드 오류가 없으면 `errors: []`이며 기존 `fieldErrors`는 사용하지 않습니다.

- 200·201은 `SUCCESS`, 비동기 접수 202는 `ACCEPTED`, 204는 본문 없음입니다.
- MVC·Security·OAuth 실패는 같은 오류 응답을 사용합니다.
- CSRF 발급 성공은 기존 `{headerName, token}`, OAuth 성공은 리다이렉트, 로그아웃 성공은 204를 유지합니다. 내부 AI 계약·Actuator·OpenAPI는 공통 응답으로 감싸지 않습니다.
- Controller의 반환 타입, 예외 처리, UUID·버전·시각 표기는 [공통 규격](docs/api-conventions.md)을 따릅니다.

Swagger에는 실제 구현된 OAuth 로그인 시작·CSRF·로그아웃·내 정보·돌봄 공간 생성/조회·초대·참여·구성원 목록·소유권 이전·나가기/내보내기·소식 저장/조회·분석 상태/재시도 경로와 공통 스키마를 제공합니다. OAuth 시작 경로는 제공자가 설정된 환경에서만 표시합니다. `GET /api/auth/csrf`를 실행한 뒤 반환된 `token`을 **Authorize → CsrfToken**에 입력하면 같은 브라우저 세션의 변경 요청을 확인할 수 있습니다. HttpOnly 세션 쿠키는 브라우저가 전송하며 Swagger의 쿠키 입력만으로 로그인되지 않습니다. 로그인·로그아웃 후 토큰을 다시 발급·설정합니다. 미구현 도메인 API는 노션 초안에서 관리합니다.

## 돌봄 공간 생성·조회

로그인 후 `POST /api/spaces`, `GET /api/spaces`, `GET /api/spaces/{spaceId}`를 사용할 수 있습니다. 생성 본문은 `subjectLabel`과 `clientRequestId`(UUID)이며 생성 요청에는 CSRF가 필요합니다. 호칭은 앞뒤 공백 제거 후 한 줄·최대 30자로 제한하고, 현재 소유한 공간끼리 같은 호칭을 금지합니다.

같은 생성 시도를 재전송할 때는 같은 요청 ID와 내용을 유지합니다. 이미 성공했다면 기존 공간/참여 ID를 201로 반환하며, 새 ID로 같은 호칭을 생성하면 `SPACE_LABEL_DUPLICATED`입니다. 처리 중은 `REQUEST_IN_PROGRESS`, 기존 ID의 다른 내용 재사용은 `IDEMPOTENCY_KEY_REUSED`입니다. 입력·응답·오류는 [공간 API 계약](docs/api-conventions.md#돌봄-공간-api)을 참고합니다.

Flyway V3는 공간·참여·성공 요청 기록을, V4는 초대와 참여의 초대 연결을 추가합니다. 소유자가 같은 공간의 현재 참여자인지 DB 제약으로 검사하고 생성 전체를 하나의 트랜잭션에서 처리합니다. 다른 도메인의 쓰기 작업은 [공간 접근 Service 계약](docs/architecture.md#다른-도메인의-권한-확인)을 따릅니다. 초대 발급·확인·공간 참여·구성원 목록·소유권 이전·참여 종료도 구현되어 있으며, 공간 수정/삭제 API는 후속 작업입니다.

## 소유권 이전·나가기·내보내기

현재 소유자는 `POST /api/spaces/{spaceId}/ownership-transfers`로 현재 다른 구성원에게 즉시 소유권을 이전합니다. `targetMembershipId`는 구성원 목록에서, `expectedSpaceVersion`은 공간 상세의 `version`에서 가져옵니다. 대상 사용자가 동일 호칭의 공간을 소유하면 전체 이전을 거절합니다.

구성원 본인의 나가기는 `POST /api/spaces/{spaceId}/departures`, 소유자의 구성원 제외는 `POST /api/spaces/{spaceId}/memberships/{membershipId}/removals`입니다. 세 API 모두 `expectedSpaceVersion`(문자열)·`clientRequestId`(UUID)와 CSRF가 필요합니다. 나가기·내보내기는 참여 종료 이력을 남기고 접근을 차단하며 해당 참여에서 발급한 초대를 무효화합니다. 계정과 다른 공간 참여는 유지합니다.

같은 요청을 재전송하면 다시 변경하지 않습니다. 본인 나가기는 참여가 끝난 뒤에도 동일 성공 요청의 종료 참여 ID·시각만 재응답합니다. 과거 요청으로 새 참여를 종료하지 않습니다. 상세 입력·오류·FE 재조회 기준은 [참여 변경 계약](docs/api-conventions.md#소유권-이전공간-나가기구성원-내보내기-api)을 따릅니다.

이번 변경은 V3·V4의 기존 컬럼을 사용하므로 새 마이그레이션·환경변수는 없습니다. **CareItem 담당 할 일 해제·잠금 무효화, Proposal 대기 요청 처리는 해당 도메인 구현 시 필수로 연결할 후속 작업**입니다. 실제 담당 해제 집계가 없어 `unassignedTaskCount`는 반환하지 않습니다.

## AI 연동

`HanmaumAiClient`는 내부 FastAPI의 `POST /v1/analyses`를 호출합니다. 연결 제한은 3초, 응답 대기는 30초이며 자동 재시도는 하지 않습니다. 외부 오류 본문은 API 오류나 로그에 그대로 노출하지 않습니다.

v2 요청·응답 검증과 `HanmaumAiV2Client` 통신 어댑터도 준비되어 있습니다. 현재 분석 실행은 계속 v1이며 v2 저장·조회·활성 전환은 후속 범위입니다. 추가 환경 변수는 없고, 검증 범위와 오류 매핑은 [v2 통신 계약](docs/ai-contract-v2-client.md)을 참고합니다.

[AI 계약](docs/ai-contract.md)과 [설계 기준](docs/architecture.md)을 참고합니다. 실제 FastAPI 구현과 모델 품질 평가는 별도 레포에서 진행합니다.

## 소식 분석 실행

새 소식 저장 시 원본·최초 `QUEUED` 분석·성공 요청 기록을 함께 저장하고 `201 SUCCESS`를 반환합니다. 이후 실행기가 DB에서 작업을 가져와 AI를 호출합니다. 기존 V5 소식은 자동 일괄 분석하지 않습니다.

- 상태: `GET /api/spaces/{spaceId}/source-records/{recordId}/analyses/{analysisId}`
- 실패 재시도: `POST /api/spaces/{spaceId}/source-records/{recordId}/analyses` → `202 ACCEPTED`
- 재시도 입력: `failedAnalysisId`, `expectedRecordVersion`(문자열), `clientRequestId`. 현재 참여자만 최신 FAILED 분석을 재시도할 수 있습니다.
- 상태 조회는 분석이 FAILED여도 `200 SUCCESS`이며 `data.failure`로 실패 사유를 제공합니다. 상세 계약은 [API 규격](docs/api-conventions.md#소식-분석-상태·실패-재시도)을 참고합니다.

실행기는 기본적으로 꺼져 있습니다. 이 상태에서는 **소식 저장·분석 접수·조회는 가능하지만 분석은 QUEUED에 머뭅니다.** 실제 자동 분석을 켜려면 접근 가능한 AI 서버와 내부 키를 준비하고 다음 외부 설정을 모두 지정합니다. 숫자 기본값은 없으며 로컬 검증값도 운영 정책과 구분해 정합니다.

| 설정 | 의미 |
| --- | --- |
| `ANALYSIS_WORKER_ENABLED` | `true`일 때 DB의 대기 작업 처리 시작 |
| `ANALYSIS_WORKER_CONCURRENCY` | BE 인스턴스당 동시 실행 수, 양의 정수 |
| `ANALYSIS_WORKER_POLL_INTERVAL` | 대기·중단 작업 확인 간격, 예: Duration의 `s` 단위 사용 |
| `ANALYSIS_WORKER_LEASE_DURATION` | 실행기의 생존 갱신이 끊겼을 때 작업 소유권을 만료시키는 기간 |

lease는 기존 AI 연결·응답 타임아웃 합보다 길어야 하며 실행 중 주기적으로 갱신합니다. 설정이 빠지거나 유효하지 않으면 enabled 실행은 시작하지 않습니다. lease 만료 작업은 `FAILED / ANALYSIS_INTERRUPTED`로 정리하고 자동으로 다시 AI를 호출하지 않습니다. 실행기를 나중에 켜면 이미 접수된 QUEUED 작업도 처리합니다.

V6가 분석 실행·입력 원본 의존성·제안 문서·근거·CREATE 제안 테이블을 추가합니다. 제안은 저장·조회하며 관리 항목을 자동으로 변경하지 않습니다. V8의 CareItem 후보 조회는 같은 공간의 현재 문서와 근거를 사용하고 원본·항목 버전·문서 의존성을 함께 기록합니다. 내부 후보 50개·요약 2,000자 제한을 넘으면 내용을 자르거나 일부 후보를 조용히 생략하지 않고 `ANALYSIS_DEPENDENCY_UNAVAILABLE`로 실패합니다. 이는 공개 등록 길이 제한이 아닙니다. UPDATE/RESOLVE와 제안 수정·최종 반영은 후속입니다.

AI 레포의 stub과 가짜 응답 테스트는 통신·처리 흐름을 검증하는 수단입니다. 실제 LLM 품질·비용·처리량 검증을 완료했다는 의미는 아닙니다. 동시 실행 수·확인 간격·lease·재시도 제한·사용량 제한·FE 조회 간격은 운영 미정입니다.

V9는 분석 시작 시 사용한 원본 근거 ID를 추가로 기록합니다. 완료할 때 해당 근거가 제거됐으면 원본·항목 버전이 같아도 결과를 폐기합니다. 이미 적용한 V8을 수정하지 않으며, 기존 항목·문서를 유지합니다. V8에서 근거를 기록하지 않은 후보 실행은 현재 근거로 추정하지 않고 완료 검증에서 폐기합니다.

V8/V9 적용 전에는 같은 DB에 연결된 구버전 분석 실행기가 새 작업을 가져가지 않도록 중지하고 실행 중인 작업을 정리해야 합니다. 이후 마이그레이션을 적용하고 호환되는 새 버전 실행기만 재개합니다. 구버전 실행기를 켠 채 스키마만 먼저 바꾸는 롤링 배포는 지원하지 않습니다. 중단된 작업의 기존 lease 복구·명시적 재시도 정책은 유지합니다. V7까지 적용된 개발 DB에는 통합 코드의 V8·V9가 순서대로 적용됩니다. V7 없이 V8/V9를 먼저 적용한 DB는 별도 순서 점검이 필요하며, outOfOrder를 공통 기본값으로 켜거나 적용된 파일을 수정하지 않습니다.

### V7 인덱스 적용 시 쓰기 중지

V7의 일반 `CREATE INDEX`는 빌드가 끝날 때까지 `ai_proposal` 쓰기를 차단합니다. 따라서 기존 데이터가 있는 DB의 최초 V7 적용은 제안 쓰기를 중지할 수 있는 유지보수 시간에 수행합니다. 이미 적용된 V7의 SQL·체크섬·실행 설정은 변경하지 않습니다. 쓰기 잠금의 동작과 `CONCURRENTLY`의 차이는 [PostgreSQL 공식 문서](https://www.postgresql.org/docs/17/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY)를 따릅니다.

1. 적용 이력과 DB 크기를 확인하고 제안 쓰기 중지 시간을 확보합니다. 현재 별도 쓰기 차단 스위치가 없으므로 같은 DB에 연결된 기존 BE 인스턴스·분석 실행기 및 제안 쓰기 배치를 중지합니다.
2. 진행 중인 분석 완료 처리와 쓰기 트랜잭션 종료를 확인한 뒤 마이그레이션을 시작합니다. 새 인스턴스만 worker 비활성화해도 기존 실행기의 쓰기는 중지되지 않습니다.
3. 마이그레이션용 BE는 `ANALYSIS_WORKER_ENABLED=false`로 시작하고 검증 완료 전에는 트래픽을 재개하지 않습니다. V7 적용 성공, Flyway 검증 및 `ai_proposal_space_page_ix`의 `indisvalid=true`를 확인합니다.
4. 검증 후 호환되는 BE와 분석 실행기를 기존 실행값으로 재개합니다. 중단된 실행은 기존 lease 복구·명시적 재시도 정책으로 처리하며 마이그레이션을 이유로 임의 재호출하지 않습니다.

쓰기 중지를 허용할 수 없는 환경은 이 절차의 적용 대상이 아닙니다. 그런 환경은 트랜잭션 밖의 동시 인덱스 생성과 실패·재시도 절차를 별도로 검토해야 하며, 적용된 V7을 `CREATE INDEX CONCURRENTLY`로 덮어쓰지 않습니다. V7 없이 V8/V9가 적용된 DB의 순서 점검도 별도로 필요합니다.

## AI 제안 조회

- 목록: `GET /api/spaces/{spaceId}/proposals`. 선택 필터 `recordId`, `status`, 커서 `cursor`, `size`(기본 20, 최대 50)를 사용합니다.
- 상세·근거: `GET /api/spaces/{spaceId}/proposals/{proposalId}`. 분석 상태/소식 상세의 `proposalIds` 또는 목록의 `proposalId`를 사용합니다.
- 같은 공간의 현재 참여자가 세션으로 조회합니다. GET에는 CSRF 토큰·clientRequestId가 필요하지 않습니다. 조회 자체는 AI 호출·제안 반영·상태 변경을 하지 않습니다.
- 기본은 `REVIEW_PENDING`이며 `INVALIDATED`를 명시하면 내용이 제거된 메타데이터만 조회합니다. 무효 상세는 `title`, `proposedValues`가 null이고 `evidence`는 빈 배열입니다.
- 유형별 제안 값은 기존 노션 CareItem 필드명으로 변환합니다. 미상 값은 null이며 원문이나 내부 `changes` 전체를 노출하지 않습니다. 현재 CREATE의 대상·현재 값·버전·reviewToken은 null, candidates는 빈 배열입니다. 관계·상충 판정도 미연결이므로 null입니다.
- V7은 공간별 제안 페이지 조회 인덱스만 추가합니다. 기존 데이터·V1~V6와 환경변수는 유지합니다. 상세 규격은 [API 규격](docs/api-conventions.md#ai-제안-목록·상세·근거-조회)을 참고합니다.

Swagger에서는 소식 분석이 COMPLETED가 된 뒤 위 GET을 실행합니다. 분석 결과가 0개면 `items: []`가 정상이며, 빈 응답 stub으로는 상세에 사용할 제안 ID가 생기지 않습니다. 비어 있지 않은 유형별 제안과 삭제 방어는 격리 DB의 합성 응답으로 테스트합니다.

## 컨테이너

```powershell
docker build -t hanmaum-backend:local .
```

Dockerfile은 Java 21로 빌드한 후 JRE 이미지에서 일반 사용자로 실행합니다. `.env`, Git 메타데이터, 로컬 빌드 결과는 이미지 빌드 컨텍스트에서 제외합니다. 컨테이너 실행 시 `SPRING_PROFILES_ACTIVE=prod`와 운영 환경변수를 주입합니다.

첫 배포는 Lightsail 서울 리전의 2 vCPU·4GB급 Linux 서버와 Docker Compose를 기준으로 계획합니다. Nginx·Spring·FastAPI·PostgreSQL을 별도 컨테이너로 실행하고, DB 및 AI 포트는 외부에 공개하지 않습니다. HTTPS, 외부 DB 백업과 복원 검증, 자원 사용량 확인은 실제 배포 단계에서 구성합니다. 현재 `docker-compose.yml`은 **로컬 DB용**이며 클라우드 리소스를 생성하지 않습니다.

## 구현 범위

- 준비: 빌드·포맷·세션/회원/공간/초대 DB 마이그레이션·회원 엔티티/Repository·OAuth 회원 생성/조회·회원 세션·현재 회원 공통 처리·내 정보 조회·공간 생성/조회·초대 발급/확인·공간 참여·구성원 목록·소유권 이전·나가기/내보내기·참여/소유자 권한 기반·반복 요청 처리·보안 기반·공통 응답/오류·Swagger·상태 확인·AI HTTP 계약·통합 테스트
- 추가 구현: 원본 소식 저장·커서 목록·상세 조회, 분석 접수·실행·상태 조회·실패 재시도, CREATE 제안·근거 저장 및 삭제 전파 연결점, 제안 목록·상세·근거 조회, 일정 직접 등록·항목 목록/상세·직접 입력 근거/최초 이력・CareItem 후보 의존성
- 후속: 계정 연결·회원 탈퇴, 공간 수정/삭제, 참여 종료의 담당 할 일·잠금·대기 요청 연동, 원본 삭제 API와 전체 파생 내용 제거, TASK/QUESTION 직접 등록·UPDATE/RESOLVE, 제안 검토·확정, 항목 수정·상태 변경・이력/근거 조회 API, 현황판, 카카오톡 공유
- 개발 규칙은 [AGENTS.md](AGENTS.md)에서 관리합니다. GitHub Actions CI 설정을 포함하며 운영 배포는 별도 작업입니다.
