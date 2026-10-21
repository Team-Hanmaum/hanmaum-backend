# AI v2 공통 fixture 동기화

## 기준과 원문 보존

- 저장소: `Team-Hanmaum/hanmaum-ai`
- 고정 커밋: `97972f9ff9980646646947571a8bddfce2df5d9e`
- 계약·fixture revision: `v2-20261021.1`
- [계약 13·16절](https://github.com/Team-Hanmaum/hanmaum-ai/blob/97972f9ff9980646646947571a8bddfce2df5d9e/docs/ai-contract-v2.md)
- AI 원본: `tests/fixtures/v2/` 전체
- BE 복사: `src/test/resources/ai-contract/v2/` 전체
- manifest SHA-256: `524f95bb6c1b27c3ef5cd781a15fd95f372b52b0b79787bf952316b12033183e`

고정 Git 커밋에서 원문을 복사했다. JSON·기대값·줄바꿈·fixture 문서는 수정하지 않는다. manifest 자체를 제외한 183개 파일과 caseId를 검증한다. Java 생성 도구는 번들 밖의 테스트 코드에만 존재하며 생성 결과를 파일로 중복 저장하지 않는다.

기존 `v2-20261016.2` 출처는 AI `af3abb3310f2f86c55fdf182f687da7491ef5fec`이며 manifest 해시는 `a65c27c8a8c323f3bdfb6fec0bddefe8cf251870f940e2410a2043b0dab83ff9`다. 이전 manifest와 변경 전 `B-uncomparable-record`는 새 번들의 history에 그대로 보존된다. 과거 전체 번들의 복원 커밋과 caseId 증감은 `revision-history.json`을 따른다.

## 변경과 생성 데이터

| 구분 | 사례 수 | 변경 |
| --- | ---: | --- |
| STATIC / A-* | 146 | 기존 사례·원문 유지 |
| RECEIPT / B-* | 30 | B-uncomparable-record의 503 REQUEST_COMPARISON_UNAVAILABLE 확정 |
| BACKEND / C-* | 25 | 기존 사례·원문 유지 |
| LIMITS / L-* | 102 | 개수·길이·합계·바이트 경계와 계산 사례 추가 |
| HTTP / H-* | 25 | 오류·수신·중복/과부하·슬롯·경쟁 사례 추가 |
| 전체 | 328 | 추가 127, 변경 1, 삭제 0 |

`GENERATION.md` 규칙을 Java `V2GeneratedPayloads`에서 독립 재현한다. 생성 원문 116개의 byteLength·SHA-256·디코딩 후 UTF-16 문자열 합계·전체 measurements를 `generated.json`과 대조한다. 객체 키 정렬·이스케이프·끝 공백 패딩을 포함한 실제 바이트를 검사하며, HTTP 경계 테스트에서는 재직렬화하지 않고 그 바이트를 전송한다.

## BE 테스트의 실제 범위

| 사례 | 연결한 검증 | 검증하지 않은 범위 |
| --- | --- | --- |
| STATIC 146 | 139개 파서/요청 상대 검증, 146개 모의 HTTP 매핑, 무효 결과 69개의 200 응답 독립 거부 | 실제 AI 인증·제공자 |
| RECEIPT 30 | SUBMIT별 응답의 BE 매핑, 자동 재호출 금지 | AI 수신 기록 등록·키 교체·경쟁·재시작 |
| BACKEND 25 | 내부 AI 응답의 디코딩/계약 부분 | setup/events/publicQuery 및 실제 DB 상태·저장·삭제 |
| LIMITS 102 | 96개 경계의 크기 검사/계약 거부, 6개 측정 전용 사례의 계산 | AI 서버가 실제로 같은 오류를 내는지 |
| HTTP 25 중 BE 11 | 실제 루프백 HTTP의 chunked 수신·오류/과대 200 거부·전송 전 거부·재호출 금지 | DB 저장 건수·원본 보존·공개 조회 |
| HTTP 나머지 14 | 목록·메타데이터 확인, 공통 오류 매핑은 별도 테스트 | AI 런타임 슬롯·수신 기록·수신 보호 실행 |

`AiV2SharedLimitsTests`는 복합 위반 사례에서도 크기 검사 자체와 전체 계약 검사를 별도로 실행한다. evidence 10,001자, 입력에 존재할 수 없는 초과 참조의 `coupledViolations`를 무시하거나 정상 사례로 바꾸지 않는다.

하이픈 없는 UUID를 포함하는 요청은 원문 JSON 디코딩 직후 길이로 판정한다. 이를 BE DTO로 변환한 뒤 실제 전송하면 UUID 하이픈으로 문자열 합계가 늘 수 있다. 따라서 원문 파싱 성공과 재직렬화한 요청의 전송 가능 여부를 따로 검증한다. 실제 전송 본문도 상한을 만족해야 하며 초과하면 HTTP 호출 없이 거부한다.

`H-be-preflight-limit`의 원문은 끝 공백 패딩으로 1 MiB를 초과한다. 원문 제한 거부와 클라이언트의 전송 전 거부를 나눠 검증한다. 후자는 같은 JSON 내용의 정상 DTO를 먼저 준비하고 테스트 클라이언트의 요청 예산만 직렬화 크기보다 1바이트 작게 설정하여 `analyze()`가 HTTP 호출 없이 거부하는지 확인한다. 정상 요청의 직렬화 크기와 같은 예산에서는 실제 전송도 확인한다. 공통 fixture·합의 상한·기대값은 변경하지 않는다. 성공 응답은 주입한 원문 JSON과 요청 ID·제안 전체 필드를 비교하며, 빈 제안 경계 사례 외에 제안이 있는 응답도 검증한다.

직접 실행하는 HTTP 11개:

- H-chunked-response-at / H-chunked-response-above
- H-unknown-error-422-request-too-large / H-unknown-error-409-request-comparison-unavailable
- H-unknown-error-429-ai-overloaded / H-unknown-error-503-unknown-code
- H-be-preflight-limit / H-be-oversized-error / H-be-invalid-200-limit
- H-be-error-bytes-below / H-be-error-bytes-at

AI 실행 대기 14개는 `AiV2TransportTests.AI_RUNTIME_CASES`에서 전체 caseId와 함께 명시한다. 기존 기록 우선 3개, 신규 과부하 1개, 등록 경쟁 2개, 과부하와 경쟁 등록 1개, 취소/시간 초과 슬롯 유지 2개, AI chunked 요청 수신 2개, 과대 결과 후 기록 보존 3개다. 테스트는 25개 중 분류 누락이 없는지도 확인한다.

## 실행 명령과 후속 작업

```powershell
.\gradlew.bat test --tests '*AiV2*' --tests '*HanmaumAiV2ClientTests' --tests '*HanmaumAiClientTests'
.\gradlew.bat check bootJar
```

첫 명령은 실제 AI 서버·유료 모델·Docker 없이 실행한다. 전체 검사는 기존 Testcontainers PostgreSQL 회귀를 포함한다. 결과는 Gradle 테스트 보고서에 남으며 fixture 검증을 328개 시나리오의 DB·AI 런타임 전체 통과로 표현하지 않는다.

다음은 실행별 계약 버전, 제한을 지키는 후보/근거 입력 구성, 정확한 입력 스냅샷, 현재 권한·버전·근거 연결의 완료/조회 재검사, 네 작업의 검토 제안 원자 저장 및 실패 코드 저장 연결이다. 새 Flyway 마이그레이션·공개 조회·원본 삭제 전파도 해당 후속 범위에서 다룬다. 기존 실행은 v1을 유지한다.
