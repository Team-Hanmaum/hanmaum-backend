# Lightsail 운영 배포 — GitHub Secrets와 Actions

대상은 Sydney `ap-southeast-2`의 `hanmaum-server-01`(Ubuntu 24.04 amd64, 2 vCPU·4GB)이다. FE는 `https://hanmaum.site`(Vercel), API는 `https://api.hanmaum.site`, 고정 IPv4는 `16.178.232.151`이다. 이 문서는 이전 PC 이미지 save/load·서버 환경변수 수동 입력 절차를 대체한다.

**작성·로컬 검사와 실제 배포 완료는 다르다.** 아래 외부 설정과 코드의 dev 반영 후 Actions를 실행해야 HTTPS·Swagger가 열린다. 실제 OAuth 로그인은 브라우저에서 별도로 확인한다.

## 구성

`dev push의 Backend CI 성공 또는 Run workflow → 배포 대상 커밋 선택 → 같은 커밋 검증 → GHCR 게시 → AWS OIDC 임시 인증 → 러너 IPv4/32만 SSH 허용 → 최신 dev 확인·Secrets 전송 → S3 백업 확인 → 서버 교체·health/Swagger 검사 → 임시 SSH 규칙 제거`.

`prepare`는 읽기 전용 권한으로 이벤트·커밋을 확인하고, `build`가 검증·이미지 게시를 마쳐야 `deploy`가 실행된다. 자동 시작은 이 저장소의 `dev` push에 대한 `Backend CI`가 성공한 경우만 허용한다. PR·다른 브랜치·다른 저장소·실패·취소된 CI는 배포하지 않는다. 수동 실행은 CI 완료 여부와 무관하게 선택한 최신 dev 커밋을 기존처럼 다시 검사한다.

자동 실행의 배포 SHA는 `workflow_run.head_sha`, 수동 실행은 선택한 `github.sha`다. `workflow_run`의 기본 `github.sha`를 배포 SHA로 사용하지 않는다. 선택한 `RELEASE_SHA`를 두 job의 checkout·이미지 태그/라벨·전송 파일·릴리스 정보·S3 백업 식별자에 공통 적용한다. CI 완료 후 dev가 바뀌었으면 prepare에서 build/deploy를 건너뛰며, 새 dev를 대신 배포하지 않는다. 빌드 중이나 대기 중 dev가 바뀐 경우에는 기존 서버 잠금 안의 최신 dev 검사가 오래된 배포를 차단한다.

GitHub Secrets는 관리 원본, 서버 `.env.prod`는 실행용 사본이다. 이미지에 비밀값을 넣지 않는다. AWS 장기 Access Key·서버용 장기 GHCR PAT·Tailscale 계정은 이 구성에 필요하지 않다. GHCR은 해당 workflow의 `GITHUB_TOKEN`으로 인증하고 임시 인증 파일을 정리한다.

PostgreSQL 17.11은 관리자·앱 역할을 분리하고 전용 영속 볼륨을 쓴다. BE는 `prod,oauth`, `hanmaum_app` 역할로 실행한다. DB 5432·BE 8080은 미공개이며 Nginx 공식 이미지에 설정을 연결하여 80·443만 공개한다. Certbot 인증서·개인키는 서버의 전용 볼륨에 유지한다. 메모리 상한은 BE 1536MiB·DB 768MiB·Nginx 128MiB·일시 실행 Certbot 256MiB다. 이는 부하 검증 결과가 아닌 초기 설정이다. 단일 서버 구성이라 **배포 중 짧은 중단**이 있다. 기존 로컬 `docker-compose.yml`은 유지한다.

**AI는 첫 배포에 포함하지 않는다.** worker는 `false`, AI 주소는 예약된 `https://ai.invalid`다. 분석은 QUEUED에 머문다. FE 실제 API 연결·AI 운영 연결도 별도 작업이다.

## 1. AWS 계정·네트워크 확인

현재 배포 계정은 AWS 새 가입 방식에서 OIDC 목록 조회가 조직 정책(SCP)의 명시적 Deny로 차단된 것을 확인한 뒤, 사용자가 유료 플랜 전환과 고급 기능 활성화를 진행했다. 기존 `Hanmaum` 계정에서 Lightsail과 배포용 IAM 리소스를 계속 관리한다. 자동 추가된 `Identity Delegated Admin`은 사용자 접근 관리용 계정이다.

새 계정에 이 절차를 적용한다면 **IAM 서비스 지원 여부와 OIDC 기능 권한을 별도로 확인**한다. IAM의 **ID 제공업체(Identity providers)**와 역할 생성이 허용되어야 한다. SCP의 명시적 Deny는 IAM Allow 정책 추가로 해제되지 않는다. 고급 기능 활성화는 유료 플랜 전환을 선행하며 되돌릴 수 없고 지출 한도가 제거되므로, 조직 정책·과금 조건을 확인한 후 계정 소유자가 결정한다. 기존 정책을 일괄 삭제하지 않는다. 전환 후에는 비용 알림과 크레딧 잔액·만료일을 확인한다.

- `uname -m`은 `x86_64`여야 하며 Docker Engine·Compose·Python 3·curl이 있어야 한다.
- Lightsail 방화벽: HTTP 80·HTTPS 443 공개, SSH 22는 본인 IP/32 + 필요 시 브라우저 SSH. SSH에 `0.0.0.0/0` 또는 `::/0`가 있으면 CD가 거부한다. 5432·8080·8000은 열지 않는다.
- 가비아 `api` A 레코드와 고정 IP 연결을 확인한다. 사용하지 않는 AAAA가 다른 서버를 가리키지 않아야 한다.
- Vercel `hanmaum.site`는 Production에 직접 연결한다. `www`를 유지한다면 `www → hanmaum.site`로 이동시킨다. 최종 주소는 CORS와 같아야 한다.
- 호스트에 Nginx를 중복 설치하지 않는다. 컨테이너가 80·443을 사용한다.

프로젝트 계정 CloudShell에서 계정 ID와 인스턴스 ARN을 조회한다. AWS Settings 관리 계정 ID와 혼동하지 않는다.

```bash
aws sts get-caller-identity --query Account --output text
aws lightsail get-instance --region ap-southeast-2 \
  --instance-name hanmaum-server-01 --query instance.arn --output text
```

## 2. 비공개 S3 백업과 AWS OIDC 배포 역할

이미 IAM·GitHub 설정을 완료했다면 이번 변경으로 **S3 버킷 1개, 기존 역할의 S3 권한 추가, GitHub Variable 1개**가 추가로 필요하다. 아래 코드는 리소스를 자동 생성하지 않는다.

S3 → 버킷 만들기에서 일반 용도 버킷을 Sydney `ap-southeast-2`에 생성한다. 전역에서 고유한 소문자·숫자·하이픈 이름을 사용하고 점은 넣지 않는다. 객체 소유권은 Bucket owner enforced, 퍼블릭 액세스 차단은 네 항목 모두 활성화, 버전 관리는 활성화, 기본 암호화는 SSE-S3를 선택한다. 기존 버킷이면 동일 설정을 확인한다. 저장 공간과 요청은 별도 과금되며 백업이 AWS 크레딧으로 무제한 보장되는 의미가 아니다.

배포 역할에는 백업 prefix의 업로드·확인 권한만 부여하며 삭제 권한은 주지 않는다. 버킷 삭제·버전 삭제는 별도 관리 권한으로만 수행한다. 원격 백업의 보관 기간과 삭제 정책은 운영 데이터 정책에 맞춰 별도 합의하며, 이 코드가 S3 객체를 자동 삭제하지 않는다.

1. IAM **ID 제공업체(Identity providers) → 공급자 추가(Add provider) → OpenID Connect**. URL은 `https://token.actions.githubusercontent.com`, Audience는 `sts.amazonaws.com`. 동일 provider는 재사용한다.
2. `deploy/aws/permissions.json`의 `REPLACE_WITH_LIGHTSAIL_INSTANCE_ARN`과 `REPLACE_WITH_BACKUP_BUCKET`을 실제 값으로 치환하여 고객 관리 정책 `HanmaumDeployFirewall`을 만든다. 이미 만들었다면 기존 인스턴스 권한을 유지하고 S3 세 statement를 추가한 새 정책 버전을 기본값으로 지정한다.
3. `deploy/aws/trust.json`의 `REPLACE_WITH_PROJECT_ACCOUNT_ID`를 프로젝트 계정 ID로 치환한다. **Roles → Create role → Custom trust policy**에 넣고 위 정책을 연결하여 `HanmaumGitHubDeploy` 역할을 만든다.
4. 역할 ARN을 다음 단계의 `AWS_DEPLOY_ROLE_ARN`에 등록한다.

`GetInstancePortStates`는 리소스 범위를 지원하지 않아 `Resource: *` + 시드니 조건을 쓴다. Lightsail 수정 권한은 지정 인스턴스의 Open/CloseInstancePublicPorts 두 개뿐이다. IAM은 인스턴스까지 제한하며 **포트 22·러너 /32 제한은 코드가 적용**한다. S3는 지정 버킷의 위치·공개 차단·버전 관리 조회, `hanmaum/postgres/*`의 암호화 업로드와 HEAD 검증용 GetObject 권한으로 제한한다.

Actions가 OIDC 단기 자격 증명으로 해당 덤프 하나의 PUT·HEAD URL을 생성한다. 서버에는 1시간 이내의 URL만 전송하고 AWS Access Key는 보내지 않는다. 서버는 TLS로 업로드 후 크기·SSE-S3·버전 ID를 확인하고 로컬 SHA-256과 함께 영수증을 기록한다. 업로드 또는 검증 실패 시 앱 교체를 중단하고 로컬 덤프를 보존한다. AWS SDK는 러너의 임시 가상환경에만 설치한다.

이 저장소는 2026-09-26 생성이며 trust 예시는 GitHub immutable subject를 사용한다.

```text
repo:Team-Hanmaum@334170065/hanmaum-backend@1389141115:environment:production
```

OIDC subject를 별도 사용자화했다면 실제 형식과 일치시킨다. 인증 오류를 와일드카드 허용으로 해결하지 않는다. Environment 토큰에는 브랜치 대신 환경 이름이 들어가므로 아래 **production의 dev 전용 제한**이 필요하다.

## 3. 배포 전용 SSH 키

PC PowerShell에서 기존 개인 키와 별개로 생성한다. 기존 파일은 덮어쓰지 않는다. passphrase에는 두 번 Enter를 입력한다.

```powershell
$deployKey = Join-Path $env:USERPROFILE '.ssh\hanmaum-actions'
ssh-keygen -t ed25519 -C 'hanmaum-actions' -f $deployKey
```

신뢰하는 Lightsail 브라우저 SSH에서 Ubuntu 사용자의 `~/.ssh/authorized_keys`에 `.pub` 공개키 한 줄을 추가한다. 기존 키는 유지하고 앞에 `restrict `를 붙인다.

```text
restrict ssh-ed25519 실제_공개키_내용 hanmaum-actions
```

`.ssh`는 700, `authorized_keys`는 600이어야 한다. `sudo -n true` 성공을 확인한다. CD는 Ubuntu의 비밀번호 없는 sudo로 서버를 변경하므로 배포 개인키를 production Secret으로 보관한다. 같은 신뢰하는 콘솔에서 **호스트 공개키**를 확인한다.

```bash
awk '{print "16.178.232.151", $1, $2}' /etc/ssh/ssh_host_ed25519_key.pub
```

이 한 줄이 `DEPLOY_SSH_KNOWN_HOSTS`다. 호스트 개인키 파일은 읽거나 복사하지 않는다. Actions는 `ssh-keyscan`으로 즉석 신뢰하지 않고 이 값을 엄격히 검증한다. 서버 재생성 후에는 재확인한다.

## 4. GitHub production 환경과 값 등록

저장소 **Settings → Environments → New environment → production**. **Deployment branches and tags → Selected branches and tags → Branch dev만** 등록한다. 모든 브랜치를 허용하지 않는다. 일반 PR 검사에는 운영 Secrets를 전달하지 않는다.

이 환경의 **Environment secrets**에 11개를 등록한다. Repository Secrets에 미리 넣었다면 같은 이름의 환경 Secret으로 옮겨 관리 위치를 통일한다.

| Secret | 값 |
| --- | --- |
| `DEPLOY_SSH_PRIVATE_KEY` | PC hanmaum-actions 개인키 전체, BEGIN/END 포함. `.pub` 아님 |
| `DEPLOY_SSH_KNOWN_HOSTS` | 위 서버 IP + 호스트 공개키 한 줄 |
| `POSTGRES_ADMIN_PASSWORD` | 새 DB 관리자 난수 비밀번호 |
| `DB_PASSWORD` | 위 값과 다른 앱 DB 비밀번호 |
| `GOOGLE_CLIENT_ID` | 웹 OAuth 클라이언트 ID |
| `GOOGLE_CLIENT_SECRET` | 같은 Google 클라이언트 Secret |
| `KAKAO_CLIENT_ID` | 카카오 REST API 키, JavaScript/Admin 키 아님 |
| `KAKAO_CLIENT_SECRET` | 같은 카카오 로그인 앱 Client Secret |
| `COMMAND_DIGEST_KEY` | 별도 난수 키, 최소 32바이트 |
| `INVITATION_ENCRYPTION_KEY` | 별도 난수 정확히 32바이트의 Base64 |
| `AI_INTERNAL_API_KEY` | 별도 내부 인증키, OpenAI API 키 아님 |

DB 비밀번호 두 개와 내부 키 세 개는 `openssl rand -base64 32`를 각각 별도로 총 다섯 번 실행해 생성할 수 있다. 출력을 채팅·이슈에 공유하지 않는다. Secret에는 값 자체만 넣고 dotenv용 따옴표를 붙이지 않는다. SSH 개인키 외 앱 값에는 줄바꿈을 넣지 않는다.

**Environment variables**에는 아래 네 개를 등록한다.

| Variable | 값 |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | 2단계의 역할 ARN |
| `DEPLOY_HOST` | `16.178.232.151` |
| `ACME_EMAIL` | 인증서 계정에 사용할 실제 이메일 |
| `BACKUP_S3_BUCKET` | 위에서 생성한 비공개·버전 관리 S3 버킷 이름 |

`BACKEND_IMAGE`는 Actions가 계산하므로 등록하지 않는다. 리전·인스턴스 이름·SSH 사용자는 workflow에 명시된다. PC `.env`는 로컬 개발용으로 유지한다. `.env.prod.example`은 비밀값 없는 목록이다. **서버 환경파일을 먼저 만들 필요가 없다.** 이전 수동 안내로 만든 파일은 CD가 사용하지 않는다. 이미 DB까지 실행했다면 기존 비밀번호·키와 일치시키는 작업부터 한다.

Google/Kakao 리디렉션은 각각 `https://api.hanmaum.site/login/oauth2/code/google`, `https://api.hanmaum.site/login/oauth2/code/kakao`다.

## 5. Actions·GHCR와 코드 반영

- Settings → Actions → General에서 Actions 사용을 허용한다. 조직 제한이 있으면 workflow의 `actions/*`, `gradle/actions/*`, `docker/*`, `aws-actions/*` 고정 버전을 허용한다.
- 빌드 job은 `packages: write`, 배포 job은 `packages: read`, `id-token: write`를 명시한다. 일반 CI 권한을 일괄 확장하지 않는다.
- GHCR source label로 이 저장소를 연결한다. 동일 이름의 기존 패키지가 있다면 패키지 Settings → Manage Actions access에서 이 저장소의 읽기·쓰기를 허용한다. 인증 문제를 해결하려고 이미지를 공개로 바꾸지 않는다.
- 코드 리뷰·검증·커밋 후 팀원 승인과 필수 검사를 거쳐 PR을 dev에 반영한다. **로컬 파일만 있으면 Actions에 나타나지 않는다.**
- 기본 브랜치는 dev이며 CD도 dev에서만 실행된다. workflow_run은 기본 브랜치의 워크플로우로 실행되므로 기본 브랜치·production 브랜치 제한을 바꿀 때 함께 검토한다.
- 이 변경이 dev에 반영된 뒤 해당 커밋의 Backend CI가 성공하면 자동 배포가 시작된다. 새로운 AWS 리소스·Secrets는 필요하지 않지만 기존 외부 설정과 최초 인증서가 준비되어 있어야 한다. dev 머지가 실제 `api.hanmaum.site` 변경으로 이어진다는 점을 팀에서 공유한다.

## 6. 첫 배포·Swagger

새 서버의 최초 인증서 발급은 아래 수동 절차를 사용한다. 자동 실행은 `bootstrap_https=false`로 고정하고 인증서 발급 약관에 대신 동의하지 않는다. 인증서가 준비된 이후에는 dev CI 성공 후 자동 배포된다.

1. [Let’s Encrypt 약관](https://letsencrypt.org/repository/)을 확인한다. DNS·80/443·Secrets·IAM·SSH 설정이 끝나 있어야 한다.
2. Actions → Deploy backend → Run workflow → Branch: dev를 선택한다.
3. 인증서가 처음이라면 `bootstrap_https`를 선택한다. 설명대로 약관에 동의하는 실행이다. 이후에는 선택하지 않는다.
4. Build·Deploy job을 확인한다. 첫 실행에는 이미지 다운로드·DB 초기화·Flyway·인증서 발급이 포함된다.
5. `/actuator/health`의 UP, `/swagger-ui.html` 화면, `/v3/api-docs` JSON, 로그인 전 `/api/users/me`의 401을 확인한다. 모두 `https://api.hanmaum.site` 아래 경로다.

자동 배포 결과는 Actions → Deploy backend에서 `prepare → build → deploy`로 확인한다. CI 실패·취소 또는 오래된 커밋은 prepare의 사유와 build/deploy의 skipped 상태를 확인한다. 수동 재배포는 최신 dev의 Run workflow로 실행하며 최초 발급 체크는 끈다. CI 재실행도 성공한 커밋이 최신 dev인 경우에만 자동 배포 대상이 된다.

문서는 공개되지만 실제 API 세션·업무 권한·CSRF는 유지한다. 같은 브라우저로 Google/Kakao 로그인 후 Swagger로 돌아와 테스트한다. 변경 요청은 `GET /api/auth/csrf`의 token을 Authorize → CsrfToken에 입력하고 로그인·로그아웃 후 재발급한다. Swagger 쿠키 입력만으로 로그인되지 않는다.

CD는 실제 제공자 로그인이나 유료 AI를 호출하지 않는다. 실제 로그인·FE 연결은 사람이 확인한다. FE API base URL은 `https://api.hanmaum.site`이며 `/api`를 중복 추가하지 않는다. Mock 화면만으로 전체 연결을 판단하지 않는다.

서버는 배포 시작과 서비스 변경 직전에 GitHub의 최신 `dev` SHA를 확인한다. 빌드 도중 dev가 변경됐거나 과거 실행의 Re-run이면 중단될 수 있다. 이때 최신 dev에서 **새 Run workflow**를 시작한다. 옛 코드를 복구하려면 DB 스키마 호환성을 검토한 revert PR을 dev에 반영하고 새 실행으로 배포한다. 과거 실행 재실행이나 symlink 변경을 롤백 수단으로 사용하지 않는다.

## 서버 상태와 장애 처리

릴리스는 `/opt/hanmaum/releases/<git-sha>-<run-id>-<attempt>/`에 `.env.prod`(root 600)와 deploy/를 저장한다. `current`는 서비스 변경을 시도하는 **설정 대상**, `last-successful`은 마지막 성공, `previous`는 그 이전 성공 릴리스다. `deployment-state.json`의 `applying/failed/succeeded`가 완료 여부를 표시한다. 강제 종료 시 applying이 남을 수 있으므로 current만 보고 성공으로 판단하지 않는다. Compose 교체가 여러 단계이므로 실패 시 컨테이너별 실제 설정 경로도 확인한다.

`credential-baseline.env`는 DB healthy와 관리자·앱 계정의 TCP 인증을 확인한 뒤 저장하며 root 600이다. DB 초기화 전 실패한 빈 볼륨은 값 수정 후 재시도할 수 있다. 초기화가 일부 진행된 경우 이미 저장된 DB 암호는 그대로 필요하며, 정상 앱 역할 생성·접속이 확인될 때까지 기준 파일을 확정하지 않는다. 운영자가 출처를 알 수 없는 기존 DB를 발견하면 자동으로 받아들이지 않는다.

```bash
sudo bash /opt/hanmaum/current/deploy/compose.sh ps
sudo cat /opt/hanmaum/deployment-state.json
sudo docker ps -a --filter label=com.docker.compose.project=hanmaum-prod \
  --format '{{.Names}} {{.Label "com.docker.compose.project.config_files"}}'
sudo systemctl list-timers hanmaum-cert-renew.timer
sudo bash /opt/hanmaum/current/deploy/renew-certificates.sh --dry-run
```

timer는 하루 두 번 갱신하고 Nginx를 재로딩한다. Nginx는 원문 URI·query·쿠키·본문을 access log에 기록하지 않고 원문 URL이 붙을 수 있는 error log도 비활성화했다. 정제된 BE 로그·health·상태 코드·nginx -t로 진단한다. Compose 검사는 실제 비밀값이 출력되지 않는 `config --quiet`를 쓴다.

실패하면 설정 대상과 failed 상태를 남기며 마지막 성공 포인터는 유지한다. **DB 마이그레이션 역적용·자동 롤백은 하지 않는다.** incomplete 상태에서는 compose.sh의 조회(ps/logs/config) 외 수동 명령을 차단하고 인증서 갱신도 실행하지 않는다. 최신 dev의 수정 배포는 잠금 안에서 재시도 가능하다. 긴급 수동 복구는 컨테이너 경로·Flyway·DB 호환성을 확인한 뒤 `sudo env HANMAUM_RECOVERY=1 bash /opt/hanmaum/current/deploy/compose.sh ...`로 명시적으로 진행한다. 이전 앱을 무조건 실행하면 새 DB 스키마와 충돌할 수 있다.

필수 systemd unit 설치·daemon-reload·timer enable은 서비스 변경 전에 검증한다. 이 단계 실패는 서비스 교체나 성공 포인터 갱신 전에 중단된다. 최종 성공 표시는 HTTPS·문서·인증 확인 뒤에만 기록한다.

동시 배포는 deploy job의 Actions concurrency와 서버 flock으로 제한한다. 빌드는 병렬 실행될 수 있지만 자동·수동 서버 배포는 같은 그룹으로 직렬화하며 진행 중인 배포를 취소하지 않는다. `queue: max`로 대기 작업을 최대 100개까지 보존하며, 한도 초과 시 새로 들어오는 작업은 취소된다. 대기 순서와 커밋 순서는 다를 수 있으므로 기존 최신 dev 검사는 유지한다. 배포 대상이 아닌 CI 결과는 이 대기열에 들어가지 않는다. 일반 실패·취소에서는 임시 SSH 규칙 정리를 시도하지만 러너 장애·강제 종료·AWS 장애까지 보장하지 않는다. 이때 Lightsail Networking에서 로그의 `Deployment SSH source: x.x.x.x/32`만 제거하고 관리자 IP·브라우저 SSH는 유지한다. 임시 업로드 정리 경고가 있으면 표시된 `/tmp/hanmaum-cd.*`를 서버에서 확인 후 정리한다.

DB 비밀번호·명령 해시 키·초대 암호화 키·내부 공유키는 매 배포 재생성하지 않는다. 첫 DB 생성 이후 변경되면 CD가 거부한다. GitHub 값 변경만으로 기존 DB 역할 비밀번호가 바뀌지 않는다. 교체는 기존 데이터·AI 연결을 고려한 별도 작업이다.

## 백업·검증과 AI 후속

매 배포에서 DB 접속 확인 후, 앱 교체와 Flyway 실행 전에 custom-format pg_dump를 생성하고 비공개 S3로 복제한다. 첫 배포의 백업은 아직 업무 테이블이 없는 초기 DB일 수 있다. `.env.prod`와 암호화 키는 S3 덤프에 포함하지 않으며 별도로 보호해 보관한다. GitHub Secrets 원본 값을 다시 조회할 수 없으므로 복구용 키 보관도 필요하다.

현재 업로드는 단일 PUT으로 5 GiB 미만의 덤프만 지원한다. 이 크기를 넘으면 앱 교체 전에 중단되며 대용량 multipart 백업 경로가 필요하다.

로컬 캐시는 최근 릴리스 3개·덤프 3개를 기준으로 정리한다. current·last-successful·previous와 실제 컨테이너가 참조하는 릴리스는 개수와 관계없이 보존한다. 덤프는 S3 검증 영수증과 로컬 SHA-256이 일치하는 경우만 삭제한다. 업로드 실패본은 자동 삭제하지 않는다. 제거한 릴리스만 쓰던 BE image digest만 `docker image rm`으로 제거하며 강제 삭제·일괄 prune·볼륨 삭제는 하지 않는다. 최소 2 GiB 여유 공간과 덤프 생성 예상 공간이 없으면 배포를 중단한다. 보존 예외나 다른 서비스 데이터가 누적될 수 있으므로 디스크 모니터링은 계속 필요하다.

릴리스 복사 중 중단되어 `.env.prod`가 없거나 불완전한 폴더는 위 포인터와 컨테이너가 참조하지 않는 경우에만 다음 배포에서 정리한다. 보호 대상의 환경파일이 손상된 경우에는 자동 삭제하지 않고 정리를 중단하므로 해당 릴리스를 먼저 확인한다. 서버 활성화는 Python 바이트코드 캐시 생성을 끄고 실행하여, root가 만든 `__pycache__` 때문에 Ubuntu 사용자의 임시 업로드 폴더 삭제가 실패하지 않도록 한다.

이 구성은 **배포 시점 백업**이며 일일 예약 백업은 아직 없다. 운영 데이터 입력을 시작하기 전 별도 백업 주기를 설정하고 복원 연습을 수행한다. 수동 실행 역시 임시 AWS 인증 환경에서 `prepare_backup.py`로 만든 일회성 upload JSON이 필요하며 `sudo bash /opt/hanmaum/current/deploy/backup-db.sh <upload-json>`으로 실행한다. 만료된 URL은 재사용하지 않고 파일은 실행 후 삭제한다. 업로드 실패본의 별도 재전송은 새 URL 설정으로 `upload_backup.py <dump> <upload-json>`을 실행한다. URL JSON은 자격 증명이므로 로그·채팅에 출력하지 않는다.

복원 연습은 격리 PostgreSQL 17 또는 별도 DB에서 수행한다. 소유자는 hanmaum_app, `pg_restore --no-owner --no-acl --exit-on-error` 후 Flyway·합성 데이터를 확인한다. 실복구에는 호환 이미지·설정·키가 필요하다. down -v·운영 DB 초기화·일괄 volume prune은 배포 절차에 없다.

```powershell
.\gradlew.bat check bootJar
python -m unittest discover -s deploy/tests -p test_release.py -v
python deploy/validate.py --nginx --postgres
```

Linux/CI에서는 `python3 -m unittest discover -s deploy/tests -v`로 모든 Python 검사를 실행한다. Windows에서는 symlink·Linux 파일 권한 검사를 위해 `test_deployment_safety.py`와 `activation_smoke.py`를 CI와 같은 폐기 Python Linux 컨테이너에서 실행한다.

`test_release_selection.py`는 성공한 dev push·수동 실행의 SHA 선택, PR·실패·취소·외부 저장소 차단, 오래된 CI/조회 실패 시 배포 출력 차단, 선택한 SHA를 사용하는 S3 백업 식별자를 실제 GitHub·AWS 호출 없이 검사한다.

모든 deploy/*.sh의 bash -n도 검사한다. Docker Engine·OpenSSL이 필요하다. Nginx는 임시 인증서로 구문만 검사하며 DB는 네트워크·호스트 포트·영속 볼륨 없는 임시 컨테이너에서 합성 데이터만 검사한다. 방화벽 테스트는 AWS API 모의 응답이다. 실제 AWS·GHCR·인증서 발급 검증을 대신하지 않는다.

CI/CD는 `deploy/tests/activation_smoke.py`도 폐기되는 Python Linux 컨테이너에서 실행한다. Docker·GitHub/S3 HTTP·systemd를 모의하여 미완성 릴리스 정리 후 재시도, 과거 SHA 차단, timer 실패, DB 초기화 재시도, 영속 키 보호, 외부 백업 실패, 부분 교체 실패 상태와 성공 포인터 보존을 검사한다. 업로드 파일은 UID 1000 소유로 준비하고 root 활성화 후 같은 일반 사용자 권한으로 실제 삭제하여 임시 폴더 정리 권한도 검사한다. 이 파일은 실제 서버에서 직접 실행하지 않는다. 실제 S3 권한·서명 URL 수신 및 복구는 운영 준비 단계에서 별도 검증한다.

AI 운영 전에는 AI TLS, UID 10001로 쓸 SQLite 수신 기록 영속 볼륨·초기화 CLI를 준비하고 AI 저장소 docs/v2-integration.md를 따른다. OpenAI 키는 AI에만 전달한다. worker 동시 수·확인 간격·lease·비용과 호출 범위를 합의하고 기존 QUEUED 작업을 확인한다. 합성 데이터로 접수→AI→제안 저장·조회·중복 방지를 검증한 뒤 버전·worker·AI 주소를 명시적으로 전환한다.

## 공식 참고

- [AWS 새 가입 방식 지원 서비스](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html)
- [AWS 고급 기능 활성화와 계정 변경](https://docs.aws.amazon.com/accounts/latest/reference/activate-advanced-features.html)
- [GitHub OIDC와 immutable subject](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
- [Lightsail 방화벽 API](https://docs.aws.amazon.com/cli/latest/reference/lightsail/open-instance-public-ports.html)
- [GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GHCR 인증·Actions 접근](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Certbot webroot·갱신](https://eff-certbot.readthedocs.io/en/stable/using.html)
- [S3 일회성 URL 업로드](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html)
- [S3 SSE-S3 암호화](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingServerSideEncryption.html)
- [GitHub 재실행의 원래 SHA 유지](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs)
