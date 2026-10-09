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

회원·공간·기록·분석·제안·관리 항목·현황판·공유의 8개 도메인 폴더를 미리 준비했습니다. 회원 도메인은 `app_user`·`social_account` 저장 구조와 OAuth 로그인 시 회원 생성·조회를 구현했으며 세션의 서비스 사용자 ID 연결과 내 정보 조회는 후속 구현입니다. 나머지 도메인은 `package-info.java`에 책임을 기록한 상태입니다. 패키지별 책임은 [설계 기준](docs/architecture.md)을 따릅니다.

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

오류 코드의 공통 인터페이스는 `global/code/ErrorCode.java`, 공통 오류 enum은 `CommonErrorCode.java`입니다. 도메인 오류 enum도 같은 인터페이스를 구현하며 `ApiException`과 `ApiResponse`를 함께 사용합니다. 현재 `AuthErrorCode`의 OAuth 실패, `CareItemErrorCode`의 항목 잠금, `ProposalErrorCode`의 제안 묶음 충돌을 분리했습니다. 나머지 도메인의 `code`는 기능 구현 시 확정된 오류를 추가할 수 있도록 비워뒀습니다.

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

운영 필수 환경변수는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FRONTEND_URL`, `AI_BASE_URL`, `AI_INTERNAL_API_KEY`입니다. 예를 들어 DB URL은 `jdbc:postgresql://postgres:5432/hanmaum` 형태입니다.

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

현재는 **OAuth 로그인 시 회원 생성·조회까지 구현한 상태**입니다. Flyway V2는 `app_user`·`social_account`를 추가하고, `(provider, provider_user_id)` 유일성 및 사용자 외래 키를 검사합니다. ID는 JPA에서 UUID로 생성하고 생성·수정 시각은 서버에서 기록합니다.

- Google은 검증된 ID Token의 `sub`, Kakao는 사용자 정보의 `id`를 제공자와 함께 회원 식별에 사용합니다. 이메일 일치만으로 계정을 병합하지 않습니다.
- 표시 이름은 최초 가입 때 Google의 `name` 또는 Kakao의 `kakao_account.profile.nickname`으로 저장합니다. 값이 없거나 비어 있으면 null이며 재로그인 때 기존 이름과 프로필 수정 시각을 덮어쓰지 않습니다. 이 동작은 2026-10-09 사용자 합의이며 별도 이름 수정 API의 입력 제한은 미정입니다.
- 제공자 통신 후 회원·소셜 연결을 하나의 DB 트랜잭션에서 생성합니다. 같은 제공자 식별자의 동시 로그인은 PostgreSQL 트랜잭션 잠금으로 직렬화하며 저장 실패 시 함께 롤백합니다.
- 기존 콜백 주소·로그인 성공 리다이렉트·`OAUTH_LOGIN_FAILED` 응답을 유지합니다. 자동 검증은 로컬 가짜 제공자와 격리 PostgreSQL을 사용하며 실제 Google·Kakao 회원 매핑은 별도로 확인해야 합니다.

세션 principal은 아직 기존 제공자 사용자 형태입니다. 세션의 한마음 사용자 ID 연결과 `GET /api/users/me`는 다음 단계이며, 기존 로그인 세션을 회원으로 자동 변환하지 않습니다. 계정 연결과 회원 탈퇴 업무도 아직 구현하지 않았습니다. 사용자 행 삭제 시 소셜 연결을 제거하는 FK가 회원 탈퇴 전체 정책을 구현한 것은 아닙니다.

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

Swagger에는 실제 구현된 CSRF·로그아웃 경로와 공통 스키마를 제공합니다. `GET /api/auth/csrf`를 실행한 뒤 반환된 `token`을 **Authorize → CsrfToken**에 입력하면 같은 브라우저 세션의 변경 요청을 확인할 수 있습니다. HttpOnly 세션 쿠키는 브라우저가 전송하며 Swagger의 쿠키 입력만으로 로그인되지 않습니다. 로그인·로그아웃 후 토큰을 다시 발급·설정합니다. 미구현 도메인 API는 노션 초안에서 관리합니다.

## AI 연동

`HanmaumAiClient`는 내부 FastAPI의 `POST /v1/analyses`를 호출합니다. 연결 제한은 3초, 응답 대기는 30초이며 자동 재시도는 하지 않습니다. 외부 오류 본문은 API 오류나 로그에 그대로 노출하지 않습니다.

[AI 계약](docs/ai-contract.md)과 [설계 기준](docs/architecture.md)을 참고합니다. 실제 FastAPI 구현과 모델 품질 평가는 별도 레포에서 진행합니다.

## 컨테이너

```powershell
docker build -t hanmaum-backend:local .
```

Dockerfile은 Java 21로 빌드한 후 JRE 이미지에서 일반 사용자로 실행합니다. `.env`, Git 메타데이터, 로컬 빌드 결과는 이미지 빌드 컨텍스트에서 제외합니다. 컨테이너 실행 시 `SPRING_PROFILES_ACTIVE=prod`와 운영 환경변수를 주입합니다.

첫 배포는 Lightsail 서울 리전의 2 vCPU·4GB급 Linux 서버와 Docker Compose를 기준으로 계획합니다. Nginx·Spring·FastAPI·PostgreSQL을 별도 컨테이너로 실행하고, DB 및 AI 포트는 외부에 공개하지 않습니다. HTTPS, 외부 DB 백업과 복원 검증, 자원 사용량 확인은 실제 배포 단계에서 구성합니다. 현재 `docker-compose.yml`은 **로컬 DB용**이며 클라우드 리소스를 생성하지 않습니다.

## 구현 범위

- 준비: 빌드·포맷·세션/회원 DB 마이그레이션·회원 엔티티/Repository·OAuth 회원 생성/조회·보안 기반·공통 응답/오류·Swagger·상태 확인·AI HTTP 계약·통합 테스트
- 후속: 세션의 서비스 사용자 ID 연결·내 정보 조회 및 공간 기능, 초대, 기록, 제안 저장·확정, 항목 변경·이력, 현황판, 카카오톡 공유
- 개발 규칙은 [AGENTS.md](AGENTS.md)에서 관리합니다. GitHub Actions CI 설정을 포함하며 운영 배포는 별도 작업입니다.
