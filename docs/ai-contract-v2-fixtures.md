# BE↔AI v2 fixture 도입과 BE 연결 범위

이 문서는 공통 fixture의 출처·검증 방법·현재 BE와의 차이를 기록한다. v2 기능 명세를 대체하거나 미정 정책을 확정하지 않는다. 현재 실행 계약은 [v1](ai-contract.md)이며, 이 작업은 운영 코드·DB 마이그레이션·활성 분석 버전을 변경하지 않는다.

이하의 구현 대기 표와 검사 설명은 fixture 도입 시점의 기록이다. 이후 #27에서 요청·응답 검증기와 HTTP 어댑터를 연결했으며, 최신 연결 범위·제외 사례·오류 매핑은 [v2 통신 문서](ai-contract-v2-client.md)를 따른다. DB·상태·공개 조회 시나리오는 여전히 실행 구현 대기다.

## 원본과 무결성

| 항목 | 값 |
| --- | --- |
| AI 저장소 | `Team-Hanmaum/hanmaum-ai` |
| AI 브랜치 | `chore/3-agents-v2-fixtures` |
| 기준 커밋 | `af3abb3310f2f86c55fdf182f687da7491ef5fec` |
| AI PR | [#4](https://github.com/Team-Hanmaum/hanmaum-ai/pull/4), 전달 시 머지 전이므로 위 커밋으로 고정 |
| fixture revision | `v2-20261016.2` |
| 계약 원문 | AI 저장소의 `docs/ai-contract-v2.md`, 위 커밋 기준 |
| BE 검토 기준 | `55473ede7e181bcc5708299230721e6b13ad5ede` |
| BE 사본 | [`src/test/resources/ai-contract/v2/`](../src/test/resources/ai-contract/v2/) |
| 사용법 | [원본 README](../src/test/resources/ai-contract/v2/README.md) |
| 사례 | STATIC 146개, RECEIPT 30개, BACKEND 25개, 총 201개 |
| 파일 | manifest 등록 174개 + manifest 자체 1개 = 175개 |
| manifest SHA-256 | `a65c27c8a8c323f3bdfb6fec0bddefe8cf251870f940e2410a2043b0dab83ff9` |

사용자가 커밋·푸시한 보정본을 전달했으며, AI 로컬 HEAD와 계약·fixture가 지정 커밋과 일치하는지 확인한 뒤 전체 폴더를 바이트 그대로 동기화했다. 숨김 설정 파일 `.gitattributes`와 README도 포함한다. 원본 manifest의 정확한 파일 목록·각 SHA-256·전체 caseId 목록을 검증하고, manifest 자체를 포함한 175개 파일의 원본/사본 SHA-256 일치를 확인했다. 이전 revision의 요청·기존 응답·STATIC·RECEIPT·오류 매핑 파일은 유지되며 응답 파일 1개와 BE 사례 8개가 추가됐다.

이 SHA-256은 파일 동일성 검사다. 런타임 중복 요청의 정규화·HMAC 비교와 별개다. BE 테스트나 포맷 도구로 사본의 JSON·기대값·줄바꿈을 수정하지 않는다. 수정이 필요하면 AI 원본에서 합의한 새 revision을 만들고 전체 사본과 manifest 고정값을 함께 갱신한다. manifest를 재생성해서 임의의 기대값 변경을 통과시키지 않는다.

## 이번에 연결한 검사

[`AiV2FixtureBundleTests`](../src/test/java/com/hanmaum/backend/ai/fixture/AiV2FixtureBundleTests.java)는 외부 연결 없는 fixture 검사다.

- 고정된 manifest SHA-256, revision, 실제 파일 목록, 174개 등록 파일 해시, UTF-8·LF·BOM 여부.
- 세 모음의 사례 수, caseId 유일성·manifest 일치, 계약 절 및 메타데이터.
- 중첩된 수신 기록 단계까지 모든 `*File` 참조와 payload 목록의 일치.
- fixture가 선언한 AI 오류→BE 실패 코드 기대값 사이의 일관성. 실제 BE 오류 매핑을 실행하는 검사가 아니다.
- 중복 JSON 키·소수초 7자리·이스케이프 사례의 원문 바이트 보존. 테스트용 JSON 파서 확인이며 운영 v2 요청 검증 구현이 아니다.

[`AiV2BackendFixtureMetadataTests`](../src/test/java/com/hanmaum/backend/ai/fixture/AiV2BackendFixtureMetadataTests.java)는 새 시나리오 메타데이터를 소비하는 검사다.

- 25개 `publicQuery`의 GET 경로·원본·공간·실행 ID 출처·조회 시점·조회자를 확인한다. 원본 삭제의 404와 내부 상태·저장·파생 내용 제거 기대값을 구분한다.
- `DETACH_BASIS`의 itemId·basisId·basisKind를 요청과 대조하고 연결 행 제거·원본 내용 보존 및 구형 keepBasisRow·keepDocument 제거를 확인한다.
- 직접 입력 8개 사례의 준비 문서 ID 분리, 이벤트 대상, 응답의 실제 근거 참조 여부, 2×2×2 조합, 단계별 기대값의 일관성을 확인한다. UUID는 값으로 비교한다.

이 검사들은 DB 준비·이벤트 실행·HTTP 요청을 수행하지 않는다. `setup`·`events`·`publicQuery`는 테스트 메타데이터이며 실제 API DTO 필드로 추가하지 않는다.

Gradle의 기본 파일 트리 제외 규칙에 포함되는 `.gitattributes`는 `processTestResources`에서 개별 파일로 추가한다. 테스트 classpath에도 manifest에 등록된 전체 파일을 그대로 복사하기 위한 설정이다.

```powershell
.\gradlew.bat test --tests '*AiV2FixtureBundleTests' --tests '*AiV2BackendFixtureMetadataTests'
.\gradlew.bat check bootJar
```

첫 명령은 Docker와 AI 없이 실행한다. 전체 검사는 기존 Testcontainers PostgreSQL 및 가짜 AI를 이용하는 기존 회귀 테스트를 포함한다. AI 저장소나 AI 서버가 BE CI에 설치되어 있을 필요는 없다. 빌드 도구·의존성 캐시는 일반 Gradle 환경을 따른다.

**201개 사례의 메타데이터·파일을 읽는 것과 201개 v2 동작을 실행하는 것은 다르다.** 이 작업에서는 v2 HTTP 어댑터·응답 검증·수신 기록·DB 저장 테스트를 구현하거나 통과로 표시하지 않는다. `@Disabled` 테스트나 가짜 성공 구현으로 v2 검증 수를 늘리지 않는다.

## 후속 테스트의 연결 지점

| 모음 | AI 책임 | BE에서 연결할 검사 | 현재 상태 |
| --- | --- | --- | --- |
| STATIC 146개 | `/v2/analyses` 요청 검증, 가짜 제공자 결과 검증, 호출 횟수 | v2 요청 구성, 독립 응답 검증, 내부 HTTP 오류 매핑, 검토 제안 저장 기대값 | fixture 검사만 연결. 실제 v2 실행 대기 |
| RECEIPT 30개 | 실제 수신 기록 어댑터의 원자적 등록·동시 수신·재시작·본문 비교·키 교체 | 409/503 등 응답의 BE 매핑과 자동 재호출 금지 | AI 저장소를 BE에서 재구현하지 않음. 실제 실행 대기 |
| BACKEND 25개 | 명시된 응답 제공 역할 | Testcontainers에서 실행·트랜잭션·권한·근거·조회·삭제 검증 | 메타데이터 검사 연결. 25개 v2 동작 시나리오는 미연결 |

후속 HTTP 테스트는 기존 `HanmaumAiClientTests`의 `MockRestServiceServer` 방식을 활용할 수 있다. `ClassPathResource("ai-contract/v2/" + bodyFile).getContentAsByteArray()`처럼 fixture를 byte[]로 읽고 원문 그대로 주입한다. 특히 무효 요청을 ObjectMapper로 읽고 다시 직렬화하면 중복 키와 시간 표현 사례가 손실된다. 정상 성공 응답의 비교는 README대로 JSON 객체 기준이며 UUID는 값으로 비교한다.

`A-request-*`를 BE의 공개 `/api` 요청 오류 테스트로 바꾸지 않는다. 이는 AI 요청 경계의 검증 사례이며 BE에서는 요청 생성과 내부 AI 422 응답의 실패 매핑을 구분해 검사한다. 실제 AI 호출 없이 가짜 응답을 주입해도 AI가 실제로 422를 반환했다고 주장할 수 없다.

DB 테스트는 기존 `TestcontainersConfiguration`, `AnalysisIntegrationTests`, `ProposalQueryIntegrationTests`를 연결 지점으로 삼는다. 입력과 기대값의 ID를 유지하며 회원·공간·원본·문서·근거를 격리 DB에 준비하고 `events.phase`에서 변경을 주입한다. 실제 모델은 호출하지 않는다. 기존 v1 DTO로 변환하거나 새 필드를 제거해서 v2 사례를 억지로 실행하지 않는다.

후속 DB 소비 절차:

1. `setup.directInputDocuments`가 있으면 현재 문서와 원본 직접 입력 근거 문서를 서로 다른 ID로 준비한다. 현재 문서는 currentValues에, 원본 문서는 basis.text에 대응시킨다. 원본 basis.text를 현재 값으로 대체하거나 재직렬화하지 않는다.
2. `DETACH_BASIS`는 care_item_basis 행을 제거하되 원본 evidence·소식·문서를 보존한다. `INVALIDATE_DIRECT_DOCUMENT`는 basisDocumentId의 본문을 null로 만들고 redacted_at을 설정하며 문서·연결 행은 유지한다.
3. 완료 전 이벤트는 결과 저장 전에 주입한다. 저장 후 이벤트는 최초 COMPLETED·저장 건수 확인 뒤 주입한다. 현재 문서 ID·본문·유효성과 후보 버전은 그대로인지 확인한다.
4. `basisAfterEvent`를 실제 DB로 확인한다. 이후 공개 조회는 `publicQuery`의 활성 조회자로 모든 이벤트와 결과 처리 후 실행하며, analysisId에는 내부 AI requestId가 아닌 해당 BE 실행 ID를 사용한다.
5. 공개 404와 내부 DISCARDED·저장 0건·파생 내용 제거는 별도 판정한다. 저장 후 근거 무효화의 노출 0건은 해당 분석의 검토 제안·비교 근거 기준이며 관리 항목 전체 근거가 0건이라는 뜻이 아니다.

`C-requester-inactive`에서는 실행 요청자와 상태를 읽는 현재 참여자를 구분한다. 퇴장한 요청자에게 조회 권한을 주라는 기대값이 아니다. 수신 기록의 여러 SUBMIT에 있는 BE 기대값도 독립 응답 매핑이며, 먼저 완료된 같은 BE 실행을 뒤의 409로 FAILED로 덮어쓰라는 뜻이 아니다.

## 현재 BE와 계약의 차이: 구현 대기

아래는 원본 fixture 오류로 단정할 항목이 아니라 v2 지원을 위해 필요한 변경이다. 이번에는 구현하지 않는다.

| 사례 | 계약 절 | 현재 구현과 필요한 후속 연결 |
| --- | --- | --- |
| `A-all-current-values`, `A-request-summary`, `A-request-snake-case` | 3·4 | `AiAnalysisRequest.CandidateItem`은 `summary`만 받는다. `CareItemCandidateService`도 문서 JSON 문자열을 전송하므로 유형별 `currentValues`·`basis` 구성과 v2 요청 DTO가 필요하다. |
| `A-link_evidence-schedule`, `A-resolve-answer`, `A-clear-field`, `C-completed`, `C-storage-rollback` | 5~9·12 | 응답에 LINK_EVIDENCE·clearFields·relationKind·reviewReasons·basisReferences가 없다. 완료 서비스는 non-CREATE를 거부하고 V6 DB 제약도 CREATE만 허용한다. 새 마이그레이션과 v2 검토 제안 저장·전체 롤백 연결이 필요하다. |
| `A-response-empty-update`, `A-response-target-only-empty`, `A-response-basis-missing`, `A-response-mixed`, `C-invalid-ai-200` | 6~9·12·13 | 현재 검증은 v1의 대상·버전·원문 인용 중심이다. v2 작업 조합·빈 변경분 예외·필수 근거·전체 결과 검증 및 AI_INVALID_RESPONSE 구분이 필요하다. |
| `A-auth-missing`, `A-request-date`, `A-disabled`, `A-timeout`, `B-same-body`, `B-store-unavailable-read`, `C-wrong-pair` | 10·13 | `HanmaumAiClient`는 HTTP 오류를 일반 AiServiceException으로 바꾸고 `AnalysisProcessor`는 AI_SERVICE_UNAVAILABLE로 저장한다. v2 상태/코드 허용 조합과 세분화된 실패 코드가 필요하다. |
| `C-fixed-run-version`, `C-explicit-retry` | 1·10·12 | 호출 경로는 `/v1/analyses` 고정이며 AnalysisRun에 실행별 계약 버전이 없다. 접수 시 버전 고정·새 실행의 버전 선택이 필요하다. 기존 원본 시각 유지·명시적 재시도 구조는 재사용한다. |
| `C-basis-detached`, `C-unreferenced-basis`, `C-query-invalidated` 및 직접 입력 8개 사례 | 3·9·12 | 현재 스냅샷은 후보 현재 문서와 source_evidence_ids를 보존하고 SOURCE_RECORD 근거 연결을 재검증한다. 실제로 보낸 모든 basisId·원본 DIRECT_INPUT 문서와 연결의 스냅샷·완료/조회 재검증을 추가해야 한다. 시나리오 표현은 아래 보정본으로 명확해졌다. |
| `A-display-title`, `C-ambiguous-query`, `C-version-changed-query` | 5·12 | 조회 action은 CREATE_ITEM 중심이고 현재 값/대상/후보·관계 등이 연결되지 않았다. 제안 제목과 changes.title 분리, 대상 미정 시 병합 금지, 버전 변경 시 병합 금지를 연결해야 한다. |
| `A-existing-answer`, `A-resolve-answer` | 4·7 | 확정 답변 저장·조회는 미구현이다. 기존 답변이 들어 있는 fixture는 내부 형식 예시이며 최초 BE 연동에서 실제 값을 제공한다는 뜻이 아니다. 최초 입력 answerText는 null, 새 제안 답변은 changes.answerText로 분리한다. |
| `C-delete-derived-copies` | 12·14 | 현재 CREATE 문서·근거 redaction 기반을 새 답변·변경분·비교 내용까지 확장해야 한다. 원본 삭제 전체 API와 모든 파생 내용 삭제가 완료된 것으로 취급하지 않는다. |

## v2-20261016.2에서 해소된 검토 사항

아래 세 항목은 지정 커밋의 계약·README·fixture에서 보정됨을 확인했다. 추가 정책 결정을 요구하는 항목으로 남기지 않는다. 실제 BE v2 기능 검증은 위 구현 대기 범위를 따른다.

| 항목 | 사례·계약 절 | 보정 및 BE 소비 기준 |
| --- | --- | --- |
| 삭제된 원본의 공개 조회 | `C-source-deleted`, `C-delete-derived-copies`; 12·13·14절 | 404 RESOURCE_NOT_FOUND로 기존 BE 정책과 일치. 내부 실행 상태·저장 건수·파생 내용 제거는 별도 검증. 모든 BACKEND 사례에 조회 대상·시점·조회자 추가. |
| 근거 연결 해제 | `C-basis-detached`, `C-unreferenced-basis`, `C-query-invalidated`; 3·9·12·14절 | removeCareItemBasisRow로 연결 행 제거, preserveBasisContent로 원본 내용 보존. keepBasisRow·keepDocument 제거. |
| 직접 입력 근거 검증 누락 | 아래 8개 사례; 3·12·14절 | 완료 전/저장 후 × 연결 해제/문서 무효화 × 응답 참조/미참조 조합 추가. 현재 문서·후보 버전을 유지해 원본 근거만 검사하도록 분리. |

추가 caseId:

- `C-direct-basis-detached`
- `C-direct-document-invalidated`
- `C-direct-basis-detached-query`
- `C-direct-document-invalidated-query`
- `C-unreferenced-direct-basis`
- `C-unreferenced-direct-document`
- `C-unreferenced-direct-basis-query`
- `C-unreferenced-direct-document-query`

이번 동기화에서 추가로 발견한 fixture 의미 불일치는 없다. 이는 v2 DB·HTTP 동작 검증 완료나 실제 모델 품질 검증을 뜻하지 않는다. 사용자가 전달한 AI 측 476개 테스트·Ruff·manifest 통과 결과는 AI 측 보고이며 BE에서 재실행한 결과와 구분한다.

## 후속 구현 순서

1. v1을 유지하는 v2 DTO·독립 검증·HTTP 어댑터·오류 매핑을 별도 작업으로 구현한다.
2. 실행별 계약 버전, 전체 입력 근거 스냅샷, 네 작업의 검토 제안 저장 및 조회를 연결한다. 적용된 마이그레이션은 수정하지 않는다.
3. 가짜 HTTP 응답과 기존 Testcontainers에서 25개 BE 시나리오를 실제 실행한다. AI의 수신 기록 테스트는 AI 책임으로 별도 검증한다.
4. 양쪽 검증과 기존 v1 회귀가 확인되기 전까지 신규 실행을 v2로 전환하지 않는다.

후보·근거 선별 및 확장 크기, 모델·비용·운영값, 수신 기록 보관·정리 정책은 기존 미정을 유지한다.
