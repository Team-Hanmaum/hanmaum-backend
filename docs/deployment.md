# Lightsail 운영 배포

## 구성

FE는 `https://hanmaum.site`(Vercel), BE는 `https://api.hanmaum.site`(Lightsail Sydney)다. [운영 Compose](../deploy/compose.prod.yml)는 Nginx·BE·PostgreSQL을 실행하며, AI는 같은 서버에 별도로 배포한다.

[Backend CI](../.github/workflows/ci.yml)가 최신 `dev` push를 통과하면 [Deploy backend](../.github/workflows/deploy.yml)가 같은 커밋을 검증하고 GHCR의 이미지 digest와 배포 설정을 적용한다. 수동 실행도 최신 `dev`를 검증한다. 배포는 직렬화되며 서비스 변경 전에 S3 백업을 확인하고 교체 후 HTTPS·Swagger·인증 응답을 검사한다.

## 운영 FE 로그인·세션 연결

- FE는 OAuth 시작 주소 `/oauth2/authorization/{provider}`로 브라우저를 이동한다. Google·카카오 공급자 콜백은 `https://api.hanmaum.site/login/oauth2/code/{provider}`다.
- 기본 성공 복귀는 `https://hanmaum.site/auth/callback`이다. 실패는 `401 OAUTH_LOGIN_FAILED` JSON이다.
- 운영 쿠키는 `HANMAUM_SESSION`, BE 호스트 전용, `HttpOnly`·`Secure`·`SameSite=Lax`다. FE는 credentials를 포함하고 변경 요청에 CSRF 토큰을 보낸다. [인증 API 규격](api-conventions.md#인증과-적용-예외)
- 카카오 프로필 사진 지원을 배포하기 전에 앱 관리의 **카카오 로그인 → 동의항목 → 개인정보 → 프로필 사진(`profile_image`)**에서 **선택 동의**를 설정한다. 카카오가 유효한 HTTPS 주소를 반환하면 기본 이미지도 저장한다. 유효한 주소를 받지 못하면 `profileImageUrl`은 `null`이다. [카카오 동의항목 설정](https://developers.kakao.com/docs/latest/ko/kakaologin/prerequisite#consent-item)

### 로컬 HTTPS FE에서 배포 BE 사용

`https://local.hanmaum.site:5173`에서 같은 운영 BE를 사용한다. 로그인 시작에 `?frontend=local`을 한 번 붙이면 로컬 `/auth/callback`으로 복귀한다. 다른 값·빈 값·중복 옵션은 거절한다. CORS는 운영·로컬 HTTPS FE의 정확한 Origin에 credentials를 허용한다.

OAuth 요청은 같은 BE 세션에서 state별로 분리하며 10분간 최대 10개를 보관한다. 초과한 새 시작은 거절한다. 최초 세션 쿠키가 없는 탭이 동시에 요청하면 세션이 분리될 수 있다.

## BE와 배포된 AI 연결

BE는 `hanmaum-ai-link` 네트워크의 `https://hanmaum-ai:8443`에 접속한다. AI를 먼저 배포하고 수신 저장소·내부 TLS·CA와 공유 내부 키를 준비한다. BE 시작 시 인증된 v2 readiness를 확인한다. 신규 분석은 v2 계약으로 접수하며 이전 실행의 계약 버전은 고정된다. [AI 실행 계약](ai-contract-v2-execution.md)

### 분석 워커 활성화·비활성화

GitHub BE 저장소 **Settings → Environments → production → Environment variables**에서 다음을 설정하고 최신 `dev`를 배포한다.

| Variable | 운용 예시 |
| --- | --- |
| `ANALYSIS_WORKER_ENABLED` | `true` |
| `ANALYSIS_WORKER_CONCURRENCY` | `1` |
| `ANALYSIS_WORKER_POLL_INTERVAL` | `2s` |
| `ANALYSIS_WORKER_LEASE_DURATION` | `120s` |

변수 없이 배포하면 워커 기본값은 `false`다. 활성화에는 네 값이 필요하며 주기·lease는 양의 초 단위, lease는 AI 연결·응답 기한 합계 33초보다 길어야 한다. 가동 상태는 Actions의 **Transfer secrets and activate release** 로그에서 readiness 결과와 워커 활성화 여부를 확인한다. `false` 재배포는 새 작업 점유를 멈추고 대기 작업을 보존한다. [배포 환경 연동 점검](deployed-analysis-verification.md)

## 1. AWS 계정·네트워크 확인

- Lightsail `hanmaum-server-01`, `ap-southeast-2`, x86_64 Ubuntu에 Docker Engine·Compose·Python 3·curl을 준비한다. DNS `api` A 레코드는 인스턴스 고정 IPv4를 가리킨다.
- 공개 HTTP/HTTPS는 80·443을 사용한다. SSH는 관리 IP/32와 필요한 브라우저 SSH만 허용한다. CD는 러너 IPv4/32를 일시 허용한다. DB·앱·AI 포트를 공개하지 않는다.
- IAM 설정과 인스턴스 ARN은 Lightsail이 속한 프로젝트 계정에서 확인한다.

## 2. 비공개 S3 백업과 AWS OIDC 배포 역할

1. Sydney의 일반 용도 S3 버킷에 퍼블릭 액세스 차단, 버전 관리, SSE-S3를 적용한다.
2. IAM OIDC 공급자 `https://token.actions.githubusercontent.com`에 audience `sts.amazonaws.com`을 설정한다.
3. [권한 정책 예시](../deploy/aws/permissions.json)의 인스턴스 ARN과 버킷 이름을 치환한다. [신뢰 정책 예시](../deploy/aws/trust.json)의 프로젝트 계정 ID를 치환해 역할에 연결한다. GitHub subject는 저장소의 `production` 환경으로 제한한다.
4. 역할 ARN을 GitHub `AWS_DEPLOY_ROLE_ARN`에 등록한다.

역할은 지정 인스턴스의 방화벽과 지정 버킷의 백업 경로를 사용한다. CD가 만료되는 백업 업로드·검증 URL을 발급하며, S3 객체 삭제는 배포 역할에 위임하지 않는다.

## 3. 배포 전용 SSH 키

배포 전용 Ed25519 키를 만들고 Ubuntu 사용자의 `authorized_keys`에 공개키를 `restrict` 옵션과 함께 등록한다. `sudo -n true`를 확인한다. 신뢰하는 Lightsail 콘솔에서 `/etc/ssh/ssh_host_ed25519_key.pub`를 읽어 배포 IP와 함께 known_hosts 한 줄을 작성한다. 배포 키와 호스트 공개키 정보는 아래 GitHub 환경에 등록한다.

## 4. GitHub production 환경과 값 등록

`production` 환경의 배포 브랜치를 `dev`로 제한한다.

| 종류 | 이름 |
| --- | --- |
| SSH Secrets | `DEPLOY_SSH_PRIVATE_KEY`, `DEPLOY_SSH_KNOWN_HOSTS` |
| DB Secrets | `POSTGRES_ADMIN_PASSWORD`, `DB_PASSWORD` |
| OAuth Secrets | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` |
| 앱 Secrets | `COMMAND_DIGEST_KEY`, `INVITATION_ENCRYPTION_KEY`, `AI_INTERNAL_API_KEY` |
| 배포 Variables | `AWS_DEPLOY_ROLE_ARN`, `DEPLOY_HOST`, `ACME_EMAIL`, `BACKUP_S3_BUCKET` |

DB 관리자·앱 비밀번호를 분리한다. 명령 키는 최소 32바이트, 초대 키는 난수 32바이트의 Base64, AI 내부 키는 두 서비스의 공유 값이다. 운영 데이터가 생긴 뒤 값 교체에는 별도 이관이 필요하다. OpenAI 키는 AI에서 관리한다.

Actions가 서버 릴리스의 `.env.prod`를 만든다. [운영 환경 예시](../deploy/.env.prod.example)는 Compose 검증용이다.

## 5. Actions·GHCR와 코드 반영

GitHub Actions를 활성화하고 GHCR 패키지에 이 저장소의 읽기·쓰기 접근을 허용한다. `dev` 머지 후 CI가 성공하면 운영 자동 배포가 시작된다.

## 6. 첫 배포·Swagger

Actions → **Deploy backend** → **Run workflow**에서 `dev`를 고른다. 최초 인증서 발급에만 [Let’s Encrypt 약관](https://letsencrypt.org/repository/)을 확인한 후 `bootstrap_https`를 선택한다. AI 준비 상태를 먼저 확인한다.

`prepare → build → deploy`와 배포 SHA를 확인한 뒤 [health](https://api.hanmaum.site/actuator/health), [Swagger](https://api.hanmaum.site/swagger-ui/index.html), [OpenAPI](https://api.hanmaum.site/v3/api-docs)를 확인한다. 업무 검증은 [운영 분석·제안 점검](deployed-analysis-verification.md)을 따른다.

## 서버 상태와 장애 처리

```bash
sudo bash /opt/hanmaum/current/deploy/compose.sh ps
sudo cat /opt/hanmaum/deployment-state.json
sudo systemctl list-timers hanmaum-cert-renew.timer
```

- `current`는 적용 대상, `last-successful`은 마지막 성공 릴리스다. 상태가 `failed`·`applying`이면 컨테이너와 실제 적용 경로를 확인한다.
- 실패 시 DB 마이그레이션을 역적용하지 않는다. DB 호환성을 확인한 수정 또는 revert PR을 최신 `dev`에서 배포한다.
- 러너 장애로 SSH 규칙이 남으면 Actions 로그의 해당 러너 /32만 제거한다. 실제 비밀값이나 전체 환경 파일을 로그에 출력하지 않는다.

## 백업·복구

배포마다 앱 교체·Flyway 실행 전에 DB 덤프를 비공개 S3에 복제한다. 업로드나 검증 실패 시 앱 교체를 중단하고 로컬 덤프를 보존한다. 백업에는 앱 비밀값이 포함되지 않으므로 별도 보관한다.

복구는 호환 이미지·설정·키를 확인한 격리 PostgreSQL에서 `pg_restore --no-owner --no-acl --exit-on-error`로 연습한 후 진행한다. 배포 시점 백업과 별도로 자동 백업 주기 및 보관 정책을 정한다.
