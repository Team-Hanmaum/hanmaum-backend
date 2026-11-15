# 공간 전체 삭제

현재 소유자가 삭제 영향을 확인한 뒤 공간과 그 공간의 서비스 데이터를 한 트랜잭션으로 제거한다. 관련 분석·제안·항목·공유 링크·종료 참여를 포함하며 다른 공간과 회원 계정은 유지한다.

## 영향 확인

`GET /api/spaces/{spaceId}/deletion-impact`에서 공간 버전, 삭제 대상 건수와 `impactToken`을 조회한다. 집계에는 삭제·무효 상태도 포함한다.

## 삭제 실행

`POST /api/spaces/{spaceId}/deletions`에 세션·CSRF와 `expectedSpaceVersion`(정수 문자열), `impactToken`, `clientRequestId`(UUID)를 보낸다. 확인 토큰은 64자리 소문자 hex다. 원시 본문 상한은 1 MiB다.

서버는 소유권·버전·영향 범위를 다시 확인하고 완료 후 `200 SUCCESS`를 반환한다. 버전이 바뀌면 `409 VERSION_CONFLICT`, 영향이 바뀌면 `409 DELETION_IMPACT_CHANGED`다. 둘 다 영향 조회부터 다시 진행한다.

## 재전송·동시 실행·AI 결과

- 삭제 완료 이후 같은 요청도 `404 RESOURCE_NOT_FOUND`다. 최소 성공 영수증을 유지해 이전 작업 재실행을 막는다.
- 공간 잠금으로 다른 쓰기와 삭제를 직렬화한다. 실패하면 전체를 롤백한다.
- 삭제 후 도착한 AI 결과는 저장하지 않는다. 외부 보관 데이터나 백업 삭제는 별도 운영 정책이다.

FE는 삭제한 공간의 캐시를 제거하고 참여 공간 목록을 갱신한다. [공통 오류](api-conventions.md#오류-처리)
