# 운영 분석·제안 반영 점검

테스트 계정과 전용 공간에서 합성 소식 1건을 사용한다. 분석은 실제 모델을 호출할 수 있으므로 호출 범위를 먼저 정한다. 쿠키·인증 토큰·실제 돌봄 원문은 검증 자료에 남기지 않는다.

## 배포·인증 사전 점검

1. 배포 SHA의 Actions 결과, [health](https://api.hanmaum.site/actuator/health), [운영 Swagger](https://api.hanmaum.site/swagger-ui/index.html)를 확인한다.
2. 같은 브라우저로 로그인하고 `GET /api/users/me`를 확인한다. `GET /api/auth/csrf`의 토큰을 Swagger **Authorize → CsrfToken**에 입력한다.
3. 적용된 릴리스의 AI readiness와 워커 활성화를 확인한다. [워커 설정](deployment.md#분석-워커-활성화비활성화)

## 합성 시나리오

아래 경로의 앞부분은 `/api/spaces/{spaceId}`다. 각 변경 작업에는 별도 `clientRequestId`를 사용한다.

| 순서 | 요청 | 확인 |
| --- | --- | --- |
| 1 | `POST /api/spaces` | 테스트 공간과 소유자 참여 생성 |
| 2 | `POST /source-records` | `201`과 `recordId`·`analysis.analysisId` |
| 3 | `GET /source-records/{recordId}/analyses/{analysisId}` | `COMPLETED`와 `proposalIds`. 실패 시 `failure` 기록 |
| 4 | `GET /proposals/{proposalId}` | 원본·근거·예상 제안 값과 `reviewToken` 확인 |
| 5 | `POST /proposal-applications` | 소유자가 검토한 값 반영, 결과 `itemId`·`itemVersion` 확인 |
| 6 | `GET /items/{itemId}`·`/evidence`·`/history` 및 `GET /dashboard` | 확정 값·근거·이력·현황판 일치 확인 |

새 일정 제안의 반영 본문은 [일괄 반영 계약](proposal-decisions.md#일괄-반영)을 따른다. 제안이 0개인 분석도 완료될 수 있으며, 이 경우 항목 반영까지 통과한 것으로 기록하지 않는다. 결과와 실패 원인은 관련 이슈나 PR에 남긴다.
