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

한마음은 가족이 함께 부모님의 돌봄을 이어갈 수 있도록, 흩어진 소식과 확인한 돌봄 정보를 한 공간에 모읍니다. 가족이 남긴 소식을 AI가 분석해 변경 사항을 제안하고, 소유자가 검토한 내용을 일정·할 일·질문 등에 반영합니다.

이 저장소는 로그인·세션·공간 권한, 돌봄 업무 API와 데이터 저장, AI 분석 작업 실행을 담당합니다.

| 주요 기능 | 백엔드 역할 |
| --- | --- |
| 함께 돌보는 공간 | Google·카카오 로그인, 공간 생성·초대·참여, 구성원과 소유권 관리 |
| 소식과 AI 제안 | 원문 보존, 비동기 분석 접수·상태 조회·실패 재시도, 제안과 근거 저장 |
| 돌봄 관리 | 일정·할 일·질문 등 조회·직접 변경, 편집 잠금·버전 검사, 근거와 이력 조회 |
| 검토와 반영 | 소유자의 반영·미적용, 구성원의 변경 요청, 원본 삭제에 따른 파생 내용 정리 |
| 현황판과 공유 | 돌봄 현황판 조회, 공유 링크 발급, 현재 참여 권한에 따른 최신 정보 제공 |

<a id="서비스-흐름"></a>

## 🧭 서비스 흐름

```text
로그인 → 공간 생성 또는 초대 참여 → 홈
  └─ 소식 작성 → 원본 저장·분석 접수 → AI 분석 → 제안·근거 검토
       ├─ 소유자: 최종 반영 또는 미적용
       └─ 구성원: 변경 요청 → 소유자 검토

최종 반영 → 홈·돌봄 목록·상세에서 변경된 정보 확인
```

소식 저장은 분석이 끝나기 전에 응답합니다. 분석 워커가 대기 중인 작업을 가져와 AI에 요청하고, BE는 반환된 결과를 검토용 제안으로 저장합니다. 구성원의 직접 변경 허용 범위와 역할별 검토 규칙은 [백엔드 구조·권한 정책](docs/architecture.md)을 참고하세요.

<a id="시스템-구성"></a>

## 🏗️ 시스템 구성

![한마음 시스템 아키텍처](https://raw.githubusercontent.com/Team-Hanmaum/hanmaum-docs/main/assets/development/system-architecture.png)

| 저장소 | 역할 |
| --- | --- |
| [Frontend](https://github.com/Team-Hanmaum/hanmaum-frontend) | Vercel에서 제공하는 모바일 웹과 BE API 연동 |
| [Backend](https://github.com/Team-Hanmaum/hanmaum-backend) | 인증·세션·권한, 돌봄 API, PostgreSQL 저장, AI 분석 워커 |
| [AI](https://github.com/Team-Hanmaum/hanmaum-ai) | FastAPI 기반 소식 분석과 구조화된 돌봄 제안 생성 |

FE는 `https://api.hanmaum.site`로 요청합니다. 운영 BE와 AI는 AWS Lightsail 시드니 리전의 같은 인스턴스에서 별도 컨테이너로 실행됩니다. Nginx가 공개 HTTPS를 처리하고, BE는 전용 Docker 네트워크의 내부 HTTPS로 AI에 요청합니다. DB와 AI 서비스는 내부에서 접근합니다.

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

- **인증과 세션.** Google·카카오 OAuth 로그인 후 `HANMAUM_SESSION` 쿠키를 발급하고, 세션은 PostgreSQL에 저장합니다. 운영 쿠키는 BE 호스트 범위의 `HttpOnly`, `Secure`, `SameSite=Lax`입니다.
- **요청 권한.** BE는 유효한 세션의 회원과 소셜 연결을 확인합니다. 각 업무 서비스는 대상 공간의 현재 참여 상태와 소유자·구성원 권한을 확인합니다.
- **데이터 저장.** 소식 원문, 분석 실행, AI 제안, 확정 돌봄 항목과 근거·이력을 구분해 관리합니다. Flyway가 스키마를 적용하고 Hibernate가 검증합니다. 스키마 변경은 새 마이그레이션으로 추가합니다.
- **동시 변경과 재전송.** 계약에 따라 대상 버전·편집 잠금을 확인하고, `clientRequestId`를 받는 API는 같은 논리적 요청의 중복 실행을 방지합니다. 원본 삭제 시 해당 내용에서 파생된 제안·근거 등을 함께 정리합니다.

세션·CSRF 요청 형식은 [공개 API 공통 규격](docs/api-conventions.md), Origin과 OAuth 복귀 주소는 [운영 및 로컬 HTTPS FE 연결 설정](docs/deployment.md#운영-fe-로그인세션-연결)을 참고하세요.

<a id="폴더-구조"></a>

## 📁 폴더 구조

주요 디렉터리를 기준으로 정리했습니다.

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

도메인은 Controller에서 HTTP 요청·응답을 연결하고, Service에서 업무 규칙과 트랜잭션을 처리하며, Repository에서 데이터를 읽고 씁니다. 도메인별 경계는 [백엔드 아키텍처](docs/architecture.md)를 참고하세요.

<a id="api-계약과-ai-분석"></a>

## 🔌 API 계약과 AI 분석

업무 API는 `/api` 아래에 있고, OAuth 로그인 시작과 공급자 콜백은 Spring Security가 처리합니다. 전체 경로·요청·응답 스키마는 [운영 Swagger](https://api.hanmaum.site/swagger-ui/index.html)와 [노션 API 명세서](https://app.notion.com/p/3ec0f25ea138807cad15e07696472145)에서 확인할 수 있습니다.

| 영역 | 대표 요청 |
| --- | --- |
| 인증과 내 정보 | `GET /oauth2/authorization/{provider}`, `GET /api/users/me`, `GET /api/auth/csrf`, `POST /api/auth/logout` |
| 공간과 참여 | `GET /api/spaces`, `POST /api/spaces`, `POST /api/invitations/{invitationToken}/join` |
| 소식·분석·제안 | `POST /api/spaces/{spaceId}/source-records`, `GET /api/spaces/{spaceId}/source-records/{recordId}/analyses/{analysisId}`, `GET /api/spaces/{spaceId}/proposals` |
| 항목과 공유 | `GET /api/spaces/{spaceId}/items`, `GET /api/spaces/{spaceId}/dashboard`, `GET /api/share-links/{token}` |

일반 JSON 응답은 `success`, `code`, `message`, `data`, `errors`, `timestamp`로 구성합니다. FE는 쿠키를 포함해 요청하고, CSRF가 필요한 변경 요청에는 `GET /api/auth/csrf`에서 받은 `{headerName, token}`을 헤더로 전달합니다. CSRF 발급 성공은 이 객체 자체를 반환하고, OAuth는 리다이렉트를 사용하며, 로그아웃 성공은 본문 없는 `204`입니다. [공개 API 공통 규격](docs/api-conventions.md)

운영에서는 신규 분석을 v2 계약으로 접수합니다. BE는 원문과 같은 공간의 후보·근거를 구성하고, DB 트랜잭션 밖에서 내부 AI API를 호출합니다. 응답을 저장하기 전에 현재 입력·권한을 재검증합니다. 소식 작성 성공과 분석 성공은 별개이며, FE는 BE에서 분석 상태와 제안을 조회합니다. 실제 항목은 권한에 맞는 반영 요청을 거쳐 확정합니다. [AI v2 실행 계약](docs/ai-contract-v2-execution.md) · [제안 반영 규칙](docs/proposal-decisions.md)

<a id="로컬-실행"></a>

## 🚀 로컬 실행

**필수 환경: JDK 21 · Docker Engine(Linux 컨테이너) · Docker Compose v2.** Windows에서는 Docker Desktop의 엔진을 먼저 실행합니다. Gradle은 저장소의 Wrapper를 사용합니다.

```sh
git clone https://github.com/Team-Hanmaum/hanmaum-backend.git
cd hanmaum-backend
git config core.hooksPath .githooks
```

저장소 루트에서 다음 명령을 실행합니다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

Git Bash·macOS·Linux에서는 복사 명령에 `test -f .env || cp .env.example .env`, 실행 명령에 `./gradlew bootRun`을 사용합니다.

루트 [docker-compose.yml](docker-compose.yml)은 로컬 PostgreSQL만 실행합니다. BE는 기본 `local` 프로필로 실행되며, [.env.example](.env.example)의 개발 DB 설정을 사용합니다. 포트가 겹치면 `POSTGRES_PORT` 또는 `SERVER_PORT`를 조정하세요. `.env`는 Spring의 properties 형식이므로 값에 따옴표를 붙이지 않습니다.

- 상태: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- API 문서: [로컬 Swagger](http://localhost:8080/swagger-ui/index.html) · [OpenAPI JSON](http://localhost:8080/v3/api-docs)
- 종료: BE 터미널에서 `Ctrl+C`, DB는 `docker compose down`으로 중지하면 볼륨 데이터가 유지됩니다.

로컬 BE에서 실제 소셜 로그인을 확인하려면 개발자 콘솔에 `http://localhost:8080/login/oauth2/code/google`과 `http://localhost:8080/login/oauth2/code/kakao`를 각 공급자별로 등록하고, `.env`의 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`을 채웁니다. BE를 `.\gradlew.bat bootRun --args='--spring.profiles.active=local,oauth'`로 실행하세요. 배포 BE와 연동하는 로컬 HTTPS FE는 [별도 연결 절차](docs/deployment.md#로컬-https-fe에서-배포-be-사용)를 따릅니다.

기본 로컬 설정에서 분석 워커는 꺼져 있어 소식의 분석은 대기 상태로 남습니다. AI까지 연동할 때는 로컬 AI 서버, 같은 내부 API 키, 계약 버전과 워커 설정을 맞추세요. [AI 통신 계약](docs/ai-contract.md) · [워커 설정](docs/deployment.md#분석-워커-활성화비활성화)

<a id="개발-워크플로우"></a>

## 🤝 개발 워크플로우

1. 관련 이슈를 확인하고 필요하면 [이슈 템플릿](.github/ISSUE_TEMPLATE/feature_request.md)으로 등록합니다. 담당자와 라벨을 지정합니다.
2. 최신 `dev`에서 이슈 단위 작업 브랜치를 만듭니다.
3. 의미 있는 단위로 변경하고 검증한 뒤 커밋합니다. 커밋 훅은 `spotlessCheck`를 실행합니다.
4. 리뷰 가능한 PR을 `dev` 대상으로 생성하고 [PR 템플릿](.github/pull_request_template.md)에 이슈·변경 내용·검증 결과를 기록합니다. 스택 PR은 합의한 부모 브랜치를 대상으로 합니다.
5. 팀원 1명 이상의 승인과 저장소 필수 검사 통과를 확인한 뒤 `dev`에 머지합니다.
6. 머지 커밋의 `Backend CI`가 성공하면 `Deploy backend`에서 운영 배포 결과를 확인합니다.

| 항목 | 형식 | 예시 |
| --- | --- | --- |
| 이슈 | `[Type] 작업 내용` · 담당자·라벨 지정 | `[Fix] 소식 조회 응답 수정` |
| 브랜치 | `type/이슈번호-영문-작업명` | `fix/123-source-record-response` |
| 커밋 | `type: 핵심 변경 내용 (#이슈번호)` | `fix: 소식 조회 응답 수정 (#123)` |
| PR | `Type(#이슈번호): 핵심 작업 내용` | `Fix(#123): 소식 조회 응답 수정` |

커밋 메시지는 **한 줄만** 작성하고 변경 내용은 명사형으로 끝냅니다. 작업 타입은 `feat`, `fix`, `chore`, `docs`, `refactor`, `style`을 사용하며, `dev`·`main`에는 직접 커밋하거나 푸시하지 않습니다. 세부 규칙은 [AGENTS.md](AGENTS.md)를 참고하세요.

<a id="검증과-배포"></a>

## ✅ 검증과 배포

PowerShell에서 다음 명령을 실행합니다. Git Bash·macOS·Linux에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용합니다.

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat check bootJar --no-daemon
```

`check`는 포맷과 테스트를 검증하고, `bootJar`는 `build/libs/hanmaum-backend.jar`를 생성합니다. 전체 검증에는 Docker가 필요합니다. 통합 테스트는 Testcontainers의 별도 PostgreSQL을 사용하며, 테스트 보고서는 `build/reports/tests/test/index.html`에 생성됩니다.

배포 설정을 변경했다면 CI와 같은 Bash 환경에서 Python 3와 Docker를 준비하고 다음을 실행합니다.

```bash
for script in deploy/*.sh; do bash -n "$script" || exit 1; done
python3 -m unittest discover -s deploy/tests -v
docker run --rm --network none \
  --mount "type=bind,src=$PWD,dst=/src,readonly" \
  python:3.13-slim-bookworm python /src/deploy/tests/activation_smoke.py
python3 deploy/validate.py --nginx --postgres
python3 deploy/tests/ai_tls_smoke.py
```

![한마음 CI/CD 파이프라인](https://raw.githubusercontent.com/Team-Hanmaum/hanmaum-docs/main/assets/development/ci-cd.png)

| 시점 | 실행 내용 |
| --- | --- |
| PR 생성·새 커밋·다시 열기 | [Backend CI](.github/workflows/ci.yml)가 대상 브랜치 제한 없이 포맷·테스트·JAR 빌드와 배포 설정을 검사 |
| `dev`·`main` push | Backend CI가 해당 브랜치의 커밋을 검사 |
| `dev` push의 Backend CI 성공 | [Deploy backend](.github/workflows/deploy.yml)가 같은 커밋을 다시 검증하고 GHCR에 이미지를 게시한 뒤 Lightsail에 배포 |
| 수동 재배포·최초 HTTPS 설정 | GitHub Actions의 **Deploy backend → Run workflow**에서 최신 `dev` 선택. 최초 인증서 발급 때 `bootstrap_https` 선택 |

배포는 최신 `dev` 커밋을 대상으로 한 번에 하나씩 진행합니다. 배포 작업은 AWS OIDC로 임시 권한을 받아 서버에 접속하고, S3 백업을 확인한 뒤 컨테이너를 교체합니다. Actions 결과와 함께 [운영 health](https://api.hanmaum.site/actuator/health)와 [운영 Swagger](https://api.hanmaum.site/swagger-ui/index.html)를 확인하세요. Secrets·Variables, 초기 설정과 장애 대응은 [운영 배포 안내](docs/deployment.md), 실제 소식 분석과 제안 반영 점검은 [배포 환경 연동 점검](docs/deployed-analysis-verification.md)을 따릅니다.
