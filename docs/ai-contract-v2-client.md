# AI v2 통신 어댑터

[고정된 AI 계약](https://github.com/Team-Hanmaum/hanmaum-ai/blob/97972f9ff9980646646947571a8bddfce2df5d9e/docs/ai-contract-v2.md)의 v2-20261021.1을 사용한다. 내부 `POST /v2/analyses`에 `X-Internal-Api-Key`와 본문 `requestId`에 대응하는 `X-Request-Id`를 보낸다.

## 요청·응답 검증

| 대상 | 상한 |
| --- | --- |
| 원시 UTF-8 요청 / 응답 | 1,048,576 / 524,288바이트 |
| 원문 / 근거 텍스트 | 각각 10,000 / 20,000 UTF-16 코드 단위 |
| 요청 후보 / 후보별 근거 / 전체 근거 | 100 / 20 / 500개 |
| 제안 수 | 30개 |
| 요청 / 응답 전체 문자열 값 합계 | 100,000 / 50,000 UTF-16 코드 단위 |

세부 필드의 상한과 허용 조합은 고정 계약의 13·16절을 따른다. `AiV2Json`·`AiV2SizeRules`·`AiV2Contract`에서 인코딩, JSON 구조, 크기, 후보·근거 소속을 검사한다. 입력을 잘라 보내거나 부분 응답을 저장하지 않는다.

## 설정과 HTTP 기한

`AI_V2_MAX_REQUEST_BYTES`와 `AI_V2_MAX_RESPONSE_BYTES`는 local에서 계약 상한이 기본이며 기타 프로필은 명시한다. 계약보다 큰 값은 상한을 늘리지 못한다. `HanmaumAiV2Client`는 HTTP/1.1, 연결 3초, HTTP 전송부터 본문 수신 완료까지 30초 기한으로 한 번 호출한다.

## 실패 매핑

`AiV2ErrorMapper`는 AI의 HTTP 상태와 오류 코드를 함께 검사한다. 요청 계약 위반은 `AI_INTEGRATION_ERROR`, 응답 계약 위반은 `AI_INVALID_RESPONSE`, 기한 초과는 `AI_TIMEOUT`으로 기록한다. 구체적인 중복·저장소·서비스 오류 매핑은 코드와 [공통 fixture](ai-contract-v2-fixtures.md)를 따른다. 내부 원문·제공자 오류 메시지는 공개 응답에 포함하지 않는다.
