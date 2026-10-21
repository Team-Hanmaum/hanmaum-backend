# AI v2 요청·응답 검증과 통신 어댑터

[확정 계약 v2-20261021.1](https://github.com/Team-Hanmaum/hanmaum-ai/blob/97972f9ff9980646646947571a8bddfce2df5d9e/docs/ai-contract-v2.md)의 13·16절을 적용한다. [공통 fixture 출처·해시·실행 범위](ai-contract-v2-fixtures.md)를 함께 따른다.

## 적용 범위

- `ai.dto.v2`: 5종 currentValues, 2종 basis, 4종 검토 제안 DTO.
- `AiV2Contract`·`AiV2Json`·`AiV2SizeRules`: 엄격한 JSON, 크기 및 요청 상대 검증.
- `HanmaumAiV2Client`: 내부 키·X-Request-Id를 포함한 단일 `POST /v2/analyses` 호출.
- `AiV2ErrorMapper`: HTTP/code 전체 13개 허용 조합과 안전한 실패 표현.

`AnalysisProcessor`는 실행에 고정된 계약 버전의 클라이언트를 호출한다. 버전 선택·DB 저장·실패 코드 영속화·공개 조회는 [v2 실행 계약](ai-contract-v2-execution.md)을 따른다. 공개 BE `/api/v2`를 추가하지 않으며 내부 `/v2/analyses` 제공자는 AI다. AI를 DB 트랜잭션 안에서 호출하지 않고 자동 재시도·v1 fallback·수신 기록 저장소·응답 캐시를 추가하지 않는다.

## 초기 연동 상한

상한과 같으면 허용하고 초과하면 전체 거부한다. 아래 모든 조건을 동시에 만족해야 한다. 운영 성능·모델 처리 가능량을 보장하는 값이 아니다.

| 대상 | 상한 |
| --- | ---: |
| 비압축 UTF-8 요청 / 성공·오류 응답 본문 | 1,048,576 / 524,288바이트 |
| content | 10,000 UTF-16 코드 단위 |
| candidates | 100개 |
| 후보별 basis / 전체 basis | 20 / 500개 |
| basis.text | 20,000 UTF-16 코드 단위 |
| currentValues 자유 서술 필드 | 각각 10,000 UTF-16 코드 단위 |
| 요청 전체 문자열 값 합계 | 100,000 UTF-16 코드 단위 |
| suggestions | 30개 |
| 표시 title / evidence | 500 / 10,000 UTF-16 코드 단위 |
| changes 자유 서술 필드 | 각각 10,000 UTF-16 코드 단위 |
| 응답 전체 문자열 값 합계 | 50,000 UTF-16 코드 단위 |
| 제안별 candidateItemIds / basisReferences | 각각 100개 |
| 참조 항목별 basisIds / 제안별 basisIds 합계 | 20 / 500개 |

자유 서술 필드는 유형/작업에서 허용하는 title·description·location·observedTimeText·sourceName·answerText다. 제안 표시 title과 changes.title의 상한은 다르다. 기존 null·빈 값·필수 키·허용 필드·작업 조합·근거 소속 검증을 유지한다.

바이트는 공백·이스케이프를 포함한 실제 JSON 본문이며 HTTP 헤더는 제외한다. 문자열은 JSON 디코딩 후 UUID/날짜 등 타입 변환 전 Java String.length()로 센다. 모든 문자열 값·배열 원소·반복 등장한 값을 합산하고 객체 키·null·숫자·boolean은 제외한다. trim/NFC 변환을 하지 않으며 DIRECT_INPUT.text 내부 JSON을 재해석하지 않는다.

요청은 크기 제한 출력 스트림으로 직렬화하고 실제 전송할 JSON도 재검증한다. 직접 받은 byte[]도 디코딩 전에 바이트 상한을 검사한다. 응답 Content-Length가 초과하면 읽기 전에 거부하며, 길이 미지정/과소 기재에도 누적 수신량을 제한한다. 상한 초과를 판단하는 1바이트만 추가로 읽고 버퍼에 저장하지 않으며 응답 스트림을 닫는다.

소식/관리 항목의 공개 저장 API 제한을 변경하지 않는다. `AnalysisV2InputService`는 후보/근거 메타데이터를 상한+1개까지만 읽고, 문서별 바이트·문자열 합계를 검사하며 입력을 구성한다. 한도를 넘으면 원본을 유지하고 분석만 AI_INTEGRATION_ERROR로 실패시킨다. 내용을 잘라내거나 후보를 일부 선택하지 않는다.

## 설정과 HTTP 기한

| 설정 | local 프로필 | 기타 프로필 |
| --- | --- | --- |
| AI_V2_MAX_REQUEST_BYTES | 기본 1048576 | 명시 필요 |
| AI_V2_MAX_RESPONSE_BYTES | 기본 524288 | 명시 필요 |

양의 설정은 계약 상한과 비교해 더 작은 값을 적용한다. 큰 설정으로 계약을 우회할 수 없다. local 이외에서 미설정이면 v2 호출만 AI_INTEGRATION_ERROR로 차단한다. v1은 이 설정 없이 기존대로 동작한다. local 기본값을 추가해도 신규 분석을 v2로 전환하지 않는다.

v2 전용 JDK HTTP 클라이언트는 연결 3초, HTTP 전송 시작부터 응답 본문 수신 완료까지 전체 30초를 사용한다. 데이터를 조금씩 보내도 기한은 늘어나지 않는다. 기한 초과 시 호출/본문 읽기를 취소하고 AI_TIMEOUT을 반환한다. 전송 전 JSON 검증과 수신 후 검증 CPU 시간은 이 HTTP 기한에 포함하지 않는다.

Uvicorn과의 내부 통신은 HTTP/1.1로 고정한다. JDK 기본 HTTP/2 선호가 평문 HTTP 연결에서 h2c 업그레이드를 시도해 요청을 거절당하는 문제를 방지한다. JSON 계약과 기존 인증·기한·리디렉션 정책은 유지한다.

자동 리디렉션과 압축 자동 해제를 끄고 Accept-Encoding: identity를 사용한다. v1 HTTP 설정은 유지한다. 시간 초과는 AI 처리나 과금 취소를 보장하지 않는다. AI의 프로세스당 슬롯 2개·대기열 없음, 기존 수신 기록 우선 판정 및 취소된 제공자의 슬롯 유지 구현은 AI 책임이다.

## 실패 매핑

| AI HTTP | AI code | BE 어댑터 실패 코드 |
| --- | --- | --- |
| 400 | REQUEST_ID_MISMATCH | AI_INTEGRATION_ERROR |
| 401 | UNAUTHORIZED | AI_INTEGRATION_ERROR |
| 413 | REQUEST_TOO_LARGE | AI_INTEGRATION_ERROR |
| 422 | INVALID_REQUEST | AI_INTEGRATION_ERROR |
| 409 | REQUEST_ALREADY_RECEIVED | AI_DUPLICATE_REQUEST |
| 409 | REQUEST_ID_REUSED | AI_REQUEST_ID_REUSED |
| 502 | INVALID_AI_RESPONSE | AI_INVALID_RESPONSE |
| 502 | AI_UNAVAILABLE | AI_SERVICE_UNAVAILABLE |
| 503 | AI_NOT_CONFIGURED | AI_INTEGRATION_ERROR |
| 503 | RECEIPT_STORE_UNAVAILABLE | AI_RECEIPT_STORE_UNAVAILABLE |
| 503 | REQUEST_COMPARISON_UNAVAILABLE | AI_RECEIPT_STORE_UNAVAILABLE |
| 503 | AI_OVERLOADED | AI_SERVICE_UNAVAILABLE |
| 504 | AI_TIMEOUT | AI_TIMEOUT |

- 전송 전 요청 크기/계약 위반: HTTP 호출 없이 AI_INTEGRATION_ERROR.
- 200 응답의 크기/계약 위반: AI_INVALID_RESPONSE, 부분 결과 반환 없음.
- 성공/오류 응답의 바이트 초과: 상태와 관계없이 AI_INVALID_RESPONSE. 유효한 오류 envelope도 전체 문자열 상한을 넘으면 AI_INVALID_RESPONSE.
- 잘못된 상태/code 조합·미등록 코드·잘못된 오류 형식: AI_SERVICE_UNAVAILABLE.
- 연결/전체 기한 초과: AI_TIMEOUT. 그 외 연결·전송 실패: AI_SERVICE_UNAVAILABLE.

고정된 안전한 메시지만 반환한다. 제공자 message·원문 body·파서 오류·cause를 저장하거나 노출하지 않는다. 오류 코드가 추가되어도 자동 재시도나 새 ID 우회를 허용하지 않는다. 어댑터 실패 코드는 분석 FAILED 상태의 failureCode에 저장한다. 공개 분석 조회의 HTTP는 정상 조회인 200을 유지한다.

## 검증과 보존

중복 JSON 키·잘못된 UTF-8·추가 JSON 값·누락/추가 키·타입 강제 변환을 거부한다. recordedAt은 초/오프셋/소수초 최대 6자리 원문을 보존하고 버전은 BigInteger로 다룬다. UUID는 하이픈 유무와 대소문자를 값으로 비교하되 크기는 정규화 전에 검사한다. 대상·후보·근거의 소속, 전체 결과 검증, 날짜/시각/상태/작업 조합을 유지한다. TARGET_AMBIGUOUS의 후보 1개도 기존 합의대로 허용한다.

기존 v1 제한을 v2에 복사하지 않는다. v1보다 큰 정상 입력은 새 v2 상한 안에서 검증하며, 종전 무제한 허용 사례는 새 경계 테스트로 보완한다. 실제 DB의 현재 권한·버전·근거 연결과 삭제 전파는 이 통신 검증기가 판정하지 않는다.

실행 방법·사례별 연결 범위는 [fixture 문서](ai-contract-v2-fixtures.md)에 기록한다. 기존 v1 회귀를 유지하며 양쪽 HTTP·BE DB/상태·공개 조회를 검증하기 전에는 v2를 활성화하지 않는다.
