# Spring ↔ FastAPI 분석 계약 v1

운영의 새 분석은 [v2](ai-contract-v2-execution.md)로 접수한다. v1은 로컬 기본값과 v1으로 접수된 실행에 사용한다. 실행마다 접수 시점의 버전을 고정한다.

## 요청

`POST /v1/analyses`에 JSON을 보낸다. `X-Internal-Api-Key`는 서비스 간 공유 키, `X-Request-Id`는 본문의 `requestId`와 같은 UUID다.

본문은 `requestId`, `careSpaceId`, `recordId`, `content`, `recordedAt`, `timezone`, `candidates`로 구성한다. `recordedAt`은 원본 등록 시각, `timezone`은 `Asia/Seoul`이다.

### CareItem 후보와 입력 근거 검증

후보는 같은 공간의 유효한 현재 항목이며 `itemId`, `version`, `type`, `summary`를 전달한다. `summary`는 현재 문서 JSON의 문자열 표현이다. 원문은 최대 10,000 UTF-16 코드 단위, 후보는 최대 50개, 요약은 각 2,000 코드 단위다. 한도 초과 시 일부 생략이나 임의 축약을 하지 않는다.

## 응답

`{requestId, suggestions}`를 받는다. 제안은 최대 30개이며 제안할 내용이 없으면 빈 배열이다. BE는 요청 ID, 유형, 후보 ID·버전과 근거 구절을 확인한다. v1 프로토콜에는 `CREATE`·`UPDATE`·`RESOLVE`가 있으며 BE의 v1 실행·저장은 `CREATE` 제안만 지원한다.

## BE 공개 조회로의 변환

`ProposalValueMapper`가 `changes`에서 허용된 필드만 공개 `proposedValues`로 옮긴다. 예를 들어 일정의 `date`·`time`은 `scheduledDate`·`scheduledTime`으로 제공한다. 공개 제안과 확정 항목의 쓰기 계약은 [공개 API](api-conventions.md#ai-제안-목록상세근거-조회)와 [제안 반영](proposal-decisions.md)을 따른다.

## 실패·재시도

실패는 원본 저장을 취소하지 않는다. 워커는 같은 요청을 자동 재전송하지 않는다. 사용자가 최신 실패 실행을 재시도하면 새 ID를 사용하고 원래 소식 시각은 유지한다. [분석 상태와 재시도](api-conventions.md#소식-분석-상태실패-재시도)
