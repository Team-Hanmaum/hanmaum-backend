<p align="center">
  <img src="https://raw.githubusercontent.com/Team-Hanmaum/hanmaum-frontend/dev/public/icons/pwa-192.png" width="96" alt="흰 배경의 한마음 로고" />
</p>

<h1 align="center">한마음 · Backend</h1>

<p align="center">
  <strong>흩어진 소식은 하나로, 돌봄은 한마음으로</strong><br />
  가족이 남긴 소식을 AI가 정리하고, 확인한 일정과 할 일을 함께 관리하는 모바일 웹 서비스입니다.
</p>

<p align="center">
  <a href="https://hanmaum.site">서비스 바로가기</a> ·
  <a href="https://github.com/Team-Hanmaum/hanmaum-frontend">Frontend</a> ·
  <a href="https://github.com/Team-Hanmaum/hanmaum-backend">Backend</a> ·
  <a href="https://github.com/Team-Hanmaum/hanmaum-ai">AI</a> ·
  <a href="https://github.com/Team-Hanmaum/hanmaum-docs">Docs</a>
</p>

---

**목차**

- [프로젝트 소개](#프로젝트-소개)
- [서비스 흐름](#서비스-흐름)
- [시스템 구성](#시스템-구성)
- [기술 스택](#기술-스택)
- [인증·권한과 데이터 저장](#인증-권한과-데이터-저장)
- [폴더 구조](#폴더-구조)
- [API 계약과 AI 분석](#api-계약과-ai-분석)
- [로컬 실행](#로컬-실행)
- [개발 워크플로우](#개발-워크플로우)
- [검증과 배포](#검증과-배포)

<a id="프로젝트-소개"></a>

## 🌿 프로젝트 소개

한마음 백엔드는 가족의 돌봄 공간과 참여 권한을 관리합니다. 소식 원문, AI 제안, 가족이 확인한 일정과 할 일을 저장하고 웹 화면에 API로 제공합니다.

<a id="서비스-흐름"></a>

## 🧭 서비스 흐름

```text
로그인 → 공간 생성 또는 초대 참여
  → 소식 작성 → AI 분석 → 제안·근거 검토
  → 소유자 반영 → 일정·할 일 확인
```

구성원은 제안을 검토하고 소유자에게 변경을 요청할 수 있습니다. [백엔드 구조·권한 정책](docs/architecture.md)

<a id="시스템-구성"></a>

## 🏗️ 시스템 구성

![한마음 시스템 아키텍처](https://raw.githubusercontent.com/Team-Hanmaum/hanmaum-docs/main/assets/development/system-architecture.png)

| 저장소 | 역할 |
| --- | --- |
| [Frontend](https://github.com/Team-Hanmaum/hanmaum-frontend) | Vercel에서 제공하는 모바일 웹과 BE API 연동 |
| [Backend](https://github.com/Team-Hanmaum/hanmaum-backend) | 인증·세션·권한, 돌봄 API, PostgreSQL 저장, AI 분석 워커 |
| [AI](https://github.com/Team-Hanmaum/hanmaum-ai) | FastAPI 기반 소식 분석과 구조화된 돌봄 제안 생성 |

Nginx가 `https://api.hanmaum.site`의 요청을 BE로 전달합니다. BE·AI는 같은 Lightsail 인스턴스에서 별도 컨테이너로 실행되며 내부 HTTPS로 통신합니다.

<a id="기술-스택"></a>

## 🛠️ 기술 스택

| 영역 | 기술 | 사용 목적 |
| --- | --- | --- |
| 언어·빌드 | Java 21 · Gradle Wrapper 9.7.1 | 애플리케이션 빌드와 검사 |
| 웹·보안 | Spring Boot 4.1.1 · Spring MVC · Spring Security · OAuth 2.0 · Spring Session JDBC | REST API, 소셜 로그인, 세션과 CSRF |
| 데이터 | PostgreSQL 17.11 · Spring Data JPA · JDBC · Flyway | 업무 데이터·세션 저장, 스키마 마이그레이션 |
| API 문서·상태 | springdoc-openapi 3.1.1 · Swagger UI · Spring Boot Actuator | OpenAPI 문서와 health 확인 |
| 검사 | Spotless · Google Java Format · JUnit · Testcontainers | 포맷, 단위·통합 테스트 |
| 배포 | GitHub Actions · GHCR · Docker Compose · Nginx · AWS Lightsail · S3 | 이미지 게시, 서비스 실행, HTTPS와 배포 시점 DB 백업 |

<a id="인증-권한과-데이터-저장"></a>

## 🔐 인증·권한과 데이터 저장

| 영역 | 처리 방식 |
| --- | --- |
| 인증·세션 | Google·카카오 OAuth 로그인 후 `HANMAUM_SESSION` 쿠키 발급. 세션은 PostgreSQL에 저장 |
| 권한 | 요청마다 대상 공간의 현재 참여 상태와 소유자·구성원 권한 확인 |
| 데이터 | 소식 원문·분석 실행·AI 제안·확정 항목과 근거·이력을 구분해 저장. Flyway로 스키마 관리 |
| 동시 변경·재전송 | 필요한 요청에 버전·편집 잠금 검사, `clientRequestId`로 중복 실행 방지 |

운영 세션 쿠키는 BE 호스트 전용이며 `HttpOnly`, `Secure`, `SameSite=Lax`를 적용합니다. [로그인·세션 연결 설정](docs/deployment.md#운영-fe-로그인세션-연결)

<a id="폴더-구조"></a>

## 📁 폴더 구조

```text
hanmaum-backend/
├── .github/
│   ├── ISSUE_TEMPLATE/                   # 이슈 양식
│   └── workflows/                        # Backend CI와 Deploy backend
├── .githooks/                            # 커밋 전 Spotless 포맷 검사
├── deploy/                               # 운영 Compose와 릴리스·백업·검증 스크립트
│   ├── aws/                              # 배포 IAM 정책과 OIDC 신뢰 설정
│   ├── nginx/                            # 공개 HTTP·HTTPS 프록시 설정
│   ├── postgres/                         # PostgreSQL 앱 역할 초기 설정
│   ├── systemd/                          # 인증서 갱신 유닛과 타이머
│   └── tests/                            # 배포 설정 및 기동 검사
├── docs/                                 # API·업무 규칙·AI 계약·배포 절차
├── gradle/
│   └── wrapper/                          # Gradle 실행 버전과 Wrapper
└── src/
    ├── main/
    │   ├── java/com/hanmaum/backend/     # Spring 애플리케이션과 도메인 패키지
    │   │   ├── ai/                       # 내부 FastAPI 클라이언트와 계약 DTO
    │   │   ├── analysis/                 # 분석 접수·상태·실행 워커
    │   │   ├── auth/                     # 로그인·세션·CSRF API
    │   │   ├── careitem/                 # 돌봄 항목·답변·잠금·근거·이력
    │   │   ├── carespace/                # 공간·초대·구성원·소유권
    │   │   ├── dashboard/                # 돌봄 현황판
    │   │   ├── global/                   # 공통 보안·응답·예외·OpenAPI
    │   │   ├── proposal/                 # 제안 검토·반영과 변경 요청
    │   │   ├── record/                   # 소식 원본 등록·조회·삭제
    │   │   ├── sharing/                  # 공유 링크 발급과 조회
    │   │   └── user/                     # 회원·내 정보·탈퇴
    │   └── resources/                    # Spring 프로필과 환경 설정
    │       └── db/migration/             # Flyway SQL
    └── test/
        ├── java/                         # 단위·통합 테스트
        └── resources/                    # 테스트 프로필과 AI 계약 fixture
```

<a id="api-계약과-ai-분석"></a>

## 🔌 API 계약과 AI 분석

| 영역 | 핵심 계약 |
| --- | --- |
| 요청 | 인증이 필요한 요청에 세션 쿠키 포함. 변경 요청에는 `GET /api/auth/csrf` 토큰을 지정된 헤더에 담아 전달 |
| 응답 | 일반 JSON은 `success`, `code`, `message`, `data`, `errors`, `timestamp` 사용. OAuth·CSRF 발급·로그아웃은 별도 형식 |
| AI 분석 | 소식 저장 시 분석을 접수하고 워커가 내부 AI API 호출. 운영은 v2 계약을 사용하며 결과를 검토용 제안으로 저장 |

[운영 Swagger](https://api.hanmaum.site/swagger-ui/index.html) · [노션 API 명세](https://app.notion.com/p/3ec0f25ea138807cad15e07696472145) · [공통 API 규격](docs/api-conventions.md) · [AI v2 실행 계약](docs/ai-contract-v2-execution.md)

<a id="로컬-실행"></a>

## 🚀 로컬 실행

JDK 21과 Docker Compose v2가 필요합니다. Windows에서는 Docker Desktop을 Linux 컨테이너 모드로 실행합니다.

```sh
git clone https://github.com/Team-Hanmaum/hanmaum-backend.git
cd hanmaum-backend
git config core.hooksPath .githooks
```

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

Git Bash·macOS·Linux에서는 `test -f .env || cp .env.example .env`와 `./gradlew bootRun`을 사용합니다. Compose는 PostgreSQL을 실행하고 BE는 `local` 프로필로 시작합니다. [.env.example](.env.example)

- 실행 확인은 [서버 상태](http://localhost:8080/actuator/health)와 [로컬 Swagger](http://localhost:8080/swagger-ui/index.html)에서 할 수 있습니다.
- 종료할 때는 BE 터미널에서 `Ctrl+C`를 누른 뒤 `docker compose down`을 실행합니다.

로컬 OAuth 테스트에는 `.env`의 공급자별 자격 증명과 `http://localhost:8080/login/oauth2/code/{provider}` 콜백 등록이 필요합니다. `{provider}`는 `google` 또는 `kakao`이며, 실행 명령에 `--args='--spring.profiles.active=local,oauth'`를 추가합니다. [로컬 HTTPS FE와 배포 BE 연결](docs/deployment.md#로컬-https-fe에서-배포-be-사용)

기본 로컬 설정에서는 분석 작업이 대기 상태로 남습니다. [AI 통신 계약](docs/ai-contract.md) · [워커 설정](docs/deployment.md#분석-워커-활성화비활성화)

<a id="개발-워크플로우"></a>

## 🤝 개발 워크플로우

1. 이슈 생성
2. 최신 `dev`에서 작업 브랜치 생성
3. 구현·검증·커밋
4. `dev` 대상 PR 생성
5. 코드 리뷰·필수 검사
6. `dev`에 머지
7. Production 배포

| 항목   | 규칙                                  | 예시                              |
| ------ | ------------------------------------- | --------------------------------- |
| 이슈   | `[Type] 작업 내용` · 담당자·라벨 지정 | `[Feat] 소식 작성 연동`           |
| 브랜치 | `type/이슈번호-영문-작업명`           | `feat/123-record-create`          |
| 커밋   | `type: 작업 내용 (#이슈번호)`         | `feat: 소식 작성 API 연동 (#123)` |
| PR     | `Type(#이슈번호): 핵심 작업 내용`     | `Feat(#123): 소식 작성 API 연동`  |

작업 타입은 `feat`, `fix`, `chore`, `docs`, `refactor`, `style`을 사용합니다. `dev`·`main`에 직접 커밋하지 않고, 검증을 마친 의미 있는 단위로 커밋합니다. PR은 [템플릿](.github/pull_request_template.md)에 관련 이슈·변경 내용·검증 결과를 기록하며, `dev` 머지는 팀원 1명 이상의 승인과 필수 검사를 기준으로 합니다.

스택 PR의 base는 합의한 부모 브랜치를 사용합니다. 파일 명명·협업 규칙은 [AGENTS.md](AGENTS.md), CI 운영 방법은 [배포 문서](docs/deployment.md#구성)를 참고하세요.

<a id="검증과-배포"></a>

## ✅ 검증과 배포

PowerShell에서 다음 명령을 실행합니다. Git Bash·macOS·Linux에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용합니다.

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check bootJar --no-daemon
```

통합 테스트에는 Docker가 필요합니다. `bootJar`는 `build/libs/hanmaum-backend.jar`를 생성합니다. 배포 설정 검사 명령은 [CI 워크플로우](.github/workflows/ci.yml)에서 확인할 수 있습니다.

![한마음 CI/CD 파이프라인](https://raw.githubusercontent.com/Team-Hanmaum/hanmaum-docs/main/assets/development/ci-cd.png)

| 시점 | 실행 내용 |
| --- | --- |
| PR 또는 `dev`·`main` push | Backend CI에서 포맷·테스트·JAR 빌드·배포 설정 검사 |
| `dev` push의 CI 성공 | 같은 커밋의 이미지를 GHCR에 게시하고 Lightsail에 자동 배포 |
| 수동 배포 | [Deploy backend](.github/workflows/deploy.yml)의 **Run workflow**에서 `dev` 선택 |

배포는 최신 `dev` 커밋만 대상으로 하며, S3 백업 확인 후 컨테이너를 교체합니다.
