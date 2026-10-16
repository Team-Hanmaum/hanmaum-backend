# AI v2 요청·응답 검증과 통신 어댑터

확정된 [AI v2 계약](https://github.com/Team-Hanmaum/hanmaum-ai/blob/af3abb3310f2f86c55fdf182f687da7491ef5fec/docs/ai-contract-v2.md)의 BE 통신 경계를 구현한다. 공통 fixture 기준은 `v2-20261016.2`이며 [출처·해시·연결 범위](ai-contract-v2-fixtures.md)를 유지한다. 공유 fixture 원문과 기대값은 변경하지 않는다.

## 현재 연결 범위

- `ai.dto.v2`: 다섯 유형의 `currentValues`, 두 종류 `basis`, 네 작업의 검토 제안 DTO.
- `ai.client.v2.AiV2Contract`: 원문 UTF-8 JSON 검증, 요청 구성 검증, 응답과 요청 간 검증.
- `ai.client.v2.HanmaumAiV2Client`: `POST /v2/analyses`, 내부 인증 헤더와 본문에 일치하는 `X-Request-Id`, 단일 HTTP 호출.
- `ai.client.v2.AiV2ErrorMapper`·`ai.code.AiFailureCode`: 허용된 HTTP/코드 조합과 안전한 실패 표현.

기존 `HanmaumAiClient`, v1 DTO·검증기·`AnalysisProcessor`는 그대로 사용한다. 새 어댑터는 준비된 Spring 빈이며 분석 실행 경로에는 아직 연결하지 않았다. 활성 버전 설정·DB 마이그레이션·실행별 버전 저장·v2 제안 저장·공개 조회·Swagger 변경은 이번 범위가 아니다. 아래 실패 enum도 아직 분석 실행에 저장되거나 공개 조회로 노출되지 않는다. 후속 실행 연결에서 `AnalysisFailureCode` 및 상태 저장과 명시적으로 매핑해야 한다.

공개 BE `/api/v2`나 `/v2/analyses` 엔드포인트를 추가하지 않는다. `/v2/analyses`를 제공하는 서버는 AI이며, BE는 그 서버의 클라이언트다.

## 검증과 보존

`parseRequest(byte[])`는 원문을 검증하고 `AiV2AnalysisRequest`를 반환한다. `encodeRequest(request)`는 직접 구성한 DTO도 전송 전에 재검증한다. `parseResponse(request, byte[])`는 전체 응답을 검증한 뒤에만 결과를 반환한다.

- 중복 JSON 키, 잘못된 UTF-8, 추가 JSON 값, 알 수 없는 필드, 누락된 필수/nullable 키, 타입 강제 변환을 거부한다. v1이나 전역 ObjectMapper 설정은 바꾸지 않는다.
- `recordedAt`은 초·오프셋·소수초 최대 6자리를 검증한 문자열로 보존한다. 초과 정밀도를 절삭하지 않고 원본 시각을 현재 시각으로 교체하지 않는다.
- UUID는 값으로 비교하고 버전은 JSON 정수로 다룬다. 내부 DTO는 `BigInteger`를 사용하여 검증 과정에서 정수 손실이나 임의 범위 축소를 하지 않는다. DB 버전의 구성·저장은 후속 범위다.
- 원본 문자열·유니코드·공백·배열 순서·직접 입력 근거 문자열을 유지한다. `DIRECT_INPUT.text`를 JSON으로 다시 해석하지 않는다.
- 유형별 상태·날짜·시각, 작업/관계/대상 조합, 변경/삭제 필드, 빈 UPDATE 예외, 질문 답변을 검증한다.
- 대상·후보의 ID/유형/버전, 원문 evidence, 참조 근거 소속·중복·필수 참조를 검증한다. 여러 제안 중 하나라도 무효이면 전체 결과를 거부한다.
- 제안 표시 제목과 `changes.title`을 별도로 보존한다. 모호한 대상·대상 없음은 계약을 만족하면 정상 제안으로 유지한다. 빈 suggestions도 정상 응답이다.
- 현재 DB 유효성·권한·버전 변화·근거 연결 해제는 이 검증기가 판단하지 않는다. 입력 스냅샷·완료/조회 재검증은 후속 실행 및 저장 구현 책임이다.
- 기존 v1의 원문/summary/후보/제안 개수 제한을 v2에 복사하지 않는다. 제품 필수값·삭제 허용 정책·운영 크기 제한을 새로 확정하지 않는다. JSON 라이브러리의 기본 파서 자원 보호 설정은 제품 제한과 별개다.

DTO의 진단 문자열과 예외는 원문·근거·답변·제공자 메시지를 포함하지 않는다. 내부 AI DTO는 공개 API 응답으로 직접 반환하지 않는다.

## 통신과 실패

기존 `hanmaumAiRestClient`의 base URL·내부 키·연결/응답 타임아웃 설정을 재사용한다. 새 키·환경 변수는 필요하지 않다. DB 트랜잭션 밖에서 호출하며 자동 재시도·v1 fallback·수신 기록 저장소·응답 캐시는 추가하지 않는다.

| AI HTTP | AI code | 어댑터 실패 코드 |
| --- | --- | --- |
| 400 | REQUEST_ID_MISMATCH | AI_INTEGRATION_ERROR |
| 401 | UNAUTHORIZED | AI_INTEGRATION_ERROR |
| 422 | INVALID_REQUEST | AI_INTEGRATION_ERROR |
| 409 | REQUEST_ALREADY_RECEIVED | AI_DUPLICATE_REQUEST |
| 409 | REQUEST_ID_REUSED | AI_REQUEST_ID_REUSED |
| 502 | INVALID_AI_RESPONSE | AI_INVALID_RESPONSE |
| 502 | AI_UNAVAILABLE | AI_SERVICE_UNAVAILABLE |
| 503 | AI_NOT_CONFIGURED | AI_INTEGRATION_ERROR |
| 503 | RECEIPT_STORE_UNAVAILABLE | AI_RECEIPT_STORE_UNAVAILABLE |
| 504 | AI_TIMEOUT | AI_TIMEOUT |

- 전송 전 요청 계약 위반: `AI_INTEGRATION_ERROR`, HTTP 호출 없음.
- HTTP 200의 결과 계약 위반: `AI_INVALID_RESPONSE`, 부분 결과 반환 없음.
- 잘못된 상태/코드 조합·미등록 코드·잘못된 오류 본문·예상 밖 상태: 합의한 일반 실패 `AI_SERVICE_UNAVAILABLE`.
- 로컬 연결/읽기 타임아웃: `AI_TIMEOUT`. 그 외 연결·전송 실패: `AI_SERVICE_UNAVAILABLE`.
- 오류는 고정된 안전한 메시지만 전달하며 원본 body·파서 오류·cause를 보관하지 않는다. 오류 본문의 code/message 형식만 확인하고 제공자 message는 버린다.

타임아웃이 AI 제공자의 처리나 과금 취소를 의미하지 않는다. 재시도 권한·횟수·운영 시간 제한은 이 어댑터가 결정하지 않는다.

## 실제로 실행하는 테스트

```powershell
.\gradlew.bat test --tests '*AiV2ContractTests' --tests '*HanmaumAiV2ClientTests' --tests '*HanmaumAiClientTests'
.\gradlew.bat check bootJar
```

첫 명령은 AI 서버·Docker·유료 모델 없이 실제 BE 파서/검증기와 `MockRestServiceServer`를 사용한다. 전체 검사는 기존 Testcontainers 회귀 검증을 포함하며 개발 DB를 초기화하지 않는다.

| 공통 사례 | 이번 실행 범위 | 검증하지 않은 범위 |
| --- | --- | --- |
| STATIC 146개 중 139개 | 정상 40개, 무효 요청 30개, 무효 결과 69개를 실제 파서·DTO·요청 상대 검증에 연결 | AI 내부 인증·제공자 호출 |
| STATIC 146개 HTTP 기대값 | 가짜 HTTP 응답을 받은 BE 어댑터의 성공·실패 및 헤더/본문 검증 | 실제 AI가 해당 상태를 반환하는지 |
| 무효 결과 69개 | AI가 200으로 보내도 BE가 독립적으로 전체 거부 | DB 롤백 |
| RECEIPT 30개 중 29개 | 각 사례의 명시된 HTTP 응답을 독립적으로 주입해 매핑·재호출 금지 확인 | AI의 등록·정규화·동시성·재시작·키 교체 |
| BACKEND 25개 | aiResponse의 전송 내용과 BE 디코딩/검증 부분만 실행 | setup/events/publicQuery, 실제 상태·저장·삭제·공개 HTTP 검증 |

정적 모델 검증에서 분리한 7개는 `AiV2ContractTests.HTTP_CASES`에 이유와 함께 명시한다. 인증·헤더 불일치 오류는 모의 응답으로 매핑하며 실제 잘못된 인증키를 AI로 보내지 않는다. `A-header-uuid-case`는 BE가 UUID 값에서 본문과 같은 헤더를 구성하는 것으로 검증한다.

`B-uncomparable-record`는 원본이 `BLOCKED`·호출 0회만 지정하고 HTTP 상태/코드를 정하지 않았으므로 이번 어댑터 실행 대상에서 제외한다. fixture 무결성 검사는 유지하며 임의 상태를 만들어 통과로 표시하지 않는다. AI 수신 기록 구현에서 검증할 사례다.

추가 경계 테스트는 오류 조합, 중복 키·추가 JSON·잘못된 인코딩, 정밀도·날짜·타입, 원문 보존, v1 제한 비전파, 민감 정보 비노출, 타임아웃·연결 실패를 확인한다. BE에서 생성하는 요청은 직접 만든 DTO도 전송 전에 검사한다. 모든 HTTP 테스트는 단일 요청만 허용하여 자동 재호출과 fallback을 탐지한다.

이 결과를 201개 시나리오의 전체 기능 통과 또는 실제 모델 품질 검증이라고 표현하지 않는다.

## 다음 연결과 의존성

- BE: 접수 시 계약 버전 고정, 요청 후보·근거 구성, 스냅샷 및 현재 유효성 검증, 네 작업 저장·오류 저장·공개 조회·삭제 전파, 새 Flyway 마이그레이션.
- AI: 실제 `/v2/analyses` 라우트, 인증/헤더 검사, 영속 수신 기록, 제공자 연결. 모델·운영값·보관 정책은 별도 합의.
- FE: 이번 변경으로 새 요청이나 Swagger 테스트 절차가 생기지 않는다. v2 제안 조회 응답 확장은 후속 합의.
- 활성화: 양쪽 계약·BE DB/상태·v1 회귀와 서버 간 연동을 검증한 뒤 신규 실행부터 전환. 첫 단계 완료만으로 활성화하지 않는다.
