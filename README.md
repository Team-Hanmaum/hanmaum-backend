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
auth/controller       CSRF API, 로그인·로그아웃은 Security 필터 처리
ai/client             내부 FastAPI HTTP 클라이언트와 연결 설정
ai/dto                내부 AI 요청·응답 DTO
global/config         공통 설정과 OpenAPI
global/security       세션·CSRF·OAuth·CORS
global/response       ApiResponse, ApiFieldError, ErrorCode
global/exception      ApiException, 공통 예외 변환
```

회원·공간·기록·분석·제안·관리 항목·현황판·공유 도메인은 기능 구현 시 필요한 패키지를 추가합니다. 예정 패키지와 책임은 [설계 기준](docs/architecture.md)을 따릅니다. ERD·노션 명세 초안의 존재가 도메인 구현 완료를 뜻하지 않습니다.

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

현재는 **OAuth 설정과 세션 기반만 준비된 상태**입니다. 한마음 회원 생성, `(provider, providerUserId)` 매핑, 계정 연결, 공간별 권한 검증은 후속 기능에서 구현해야 합니다. 실제 제공자 로그인은 발급받은 키로 별도 검증해야 합니다. 이메일 일치만으로 소셜 계정을 자동 병합하지 않습니다.

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

- 준비: 빌드·포맷·세션 DB 마이그레이션·보안 기반·OAuth 설정·공통 응답/오류·Swagger·상태 확인·AI HTTP 계약·통합 테스트
- 후속: 회원 및 공간 기능, 초대, 기록, 제안 저장·확정, 항목 변경·이력, 현황판, 카카오톡 공유
- 개발 규칙은 [AGENTS.md](AGENTS.md)에서 관리합니다. GitHub Actions workflows와 운영 배포는 별도 작업입니다.
