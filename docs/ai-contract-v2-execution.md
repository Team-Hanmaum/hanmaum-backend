# AI v2 실행·저장·조회

기준은 공통 계약 `v2-20261021.1`이다. [통신 계약](ai-contract-v2-client.md)과 [fixture 검증 범위](ai-contract-v2-fixtures.md)를 함께 따른다. 제안 저장은 관리 항목의 최종 반영이 아니다.

## 접수와 전환

- 새 소식의 원본·최초 실행·반복 요청 기록을 하나의 트랜잭션에 저장한다. `ANALYSIS_CONTRACT_VERSION`은 `v1` 또는 `v2`이며 접수 시 `analysis_run.contract_version`에 고정한다.
- 기본값은 v1이다. AI v2 준비 상태와 양쪽 통합 검증 후 새 접수를 v2로 전환한다. 설정 변경은 과거 QUEUED/RUNNING 실행을 바꾸지 않는다. v2 오류를 v1으로 재시도하지 않는다.
- 실패의 명시적 재시도는 새 analysisId/requestId와 현재 설정 버전을 사용한다. 기존 원본 recordedAt·Asia/Seoul은 유지한다. 동일 clientRequestId 재전송은 기존 접수만 재생한다.
- 실행기 동시 수·확인 간격·lease·운영 보관 기간은 이번 변경으로 확정하지 않는다. 기존 worker opt-in 설정을 유지한다.

## 입력과 저장

- 점유 시 공간 → 실행 잠금 순서로 현재 참여·원본을 확인한다. 같은 공간의 유효한 후보와 현재 값을 구성한다. 원래 직접 입력 문서는 현재 문서와 별개로 추적한다.
- 후보 100개, 후보별 근거 20개, 전체 근거 500개 및 합의한 바이트/UTF-16 상한을 적용한다. 후보와 근거는 메타데이터를 상한+1까지만 읽고 문서 본문은 개별 크기를 검사한다. 초과 시 부분 선택·축약 없이 FAILED / AI_INTEGRATION_ERROR로 끝내며 원본은 보존한다.
- 직접 입력 근거는 저장된 JSON 문서를 안정된 JSON 문자열로 직렬화한다. 최초 문서에서 읽으며 현재 값으로 대체하지 않는다. 만들어진 요청 문자열을 재시도 판정을 위해 다시 정규화하지 않는다. QUESTION currentValues.answerText는 현재 합의대로 null이다.
- V10의 `analysis_input_basis`에 실제 연결 ID와 원래 직접 입력 문서 또는 evidence ID를 기록한다. 후보 버전·현재 문서·유형·상태와 원본 버전도 기록한다. 입력 원문을 실행 메타데이터에 복제하지 않는다.
- DB 트랜잭션 밖에서 해당 버전 HTTP를 한 번 호출한다. 완료는 lease 소유권, 현재 요청자 참여와 **응답 참조 여부에 관계없이 모든 캡처 입력**을 재검사한다. 제거된 연결, redacted 문서/근거, 변경된 버전은 결과를 폐기한다.
- CREATE·UPDATE·RESOLVE·LINK_EVIDENCE 전체를 검토 제안으로 원자 저장한다. title·changes·clearFields·reviewReasons·basisReferences·답변 제안은 제거 가능한 AI_OUTPUT 문서에 저장한다. 일부 저장 실패 시 문서·근거·제안 전체를 롤백한다. CareItem·확정 답변·근거 연결을 자동 변경하지 않는다.
- 통신 오류는 안전한 7개 AI 실패 코드로 저장한다. 저장 실패는 ANALYSIS_STORAGE_FAILED, 요청자 이탈은 ANALYSIS_REQUESTER_INACTIVE다. 무효 입력의 폐기가 실패 결과 저장보다 우선한다. 중단 작업의 기존 lease 복구 정책을 유지한다.

## 공개 조회

기존 Analysis·Proposal URL을 확장하며 공개 `/api/v2`는 만들지 않는다. 현재 참여 권한과 원본 삭제의 404 정책은 유지한다.

| 필드 | 의미 |
| --- | --- |
| action | CREATE_ITEM / UPDATE_ITEM / RESOLVE_QUESTION / LINK_EVIDENCE |
| title | 제안 표시 제목. changes.title을 대신하는 항목 값이 아님 |
| currentValues | 확정 대상의 유효한 현재 값. 미확정 대상·무효 입력이면 null |
| proposedValues | 허용 필드로 변환한 변경분. 현재 값과 자동 병합하지 않음 |
| changedFields | 실제 설정하려는 필드 이름. 나머지 proposedValues의 null은 변경 지시가 아님 |
| clearFields | 명시적으로 지우려는 필드 이름 |
| reviewReasons / candidates | 검토 사유와 가능한 대상. 임의로 하나를 확정하지 않음 |
| relationKind / needsConflictReview | AI 관계 판정 및 CONFLICT 여부. 실제 반영 허가는 아님 |
| comparisonBasis | 응답이 참조한 유효한 입력 근거. 주 원본 근거는 evidence에 유지 |

v2 SCHEDULE/TASK/QUESTION 값은 status를, QUESTION 제안 값은 answerText를 지원한다. v1의 기존 유형별 값은 유지한다. 담당자는 같은 공간의 현재 참여만 반환한다. reviewToken은 최종 반영 API 도입 전까지 null이다.

목록·상세·분석 결과 ID 조회는 같은 `analysis_inputs_valid` SQL 판정을 사용한다. 상세의 추가 비교 조회는 REPEATABLE_READ 스냅샷 안에서 수행한다. 완료 후 입력이 무효가 되면 INVALIDATED로 표시하고 제목·대상·현재 값·제안 값·근거·검토 내용을 가린다. GET은 DB를 수정하지 않는다.

## 삭제 전파와 배포

- `invalidateSource`, `invalidateBasis`, `invalidateDirectDocument`는 해당 변경과 **같은 트랜잭션**에서 호출하는 연결점이다. 공간 잠금을 먼저 획득하고 파생 문서 본문·인용문을 제거하며 실행을 폐기한다. 다른 독립 근거의 원문은 보존한다.
- 조회 차단만으로 저장된 민감 원문 제거를 완료했다고 보지 않는다. 향후 원본 삭제·근거 편집 API는 위 연결점을 반드시 함께 호출해야 한다. 이번 범위는 분석 파생 제안의 제거이며 공개 원본 삭제/영향 확인/최종 반영 API는 별도다.
- 적용된 V1~V9는 변경하지 않고 V10을 추가한다. 기존 실행은 v1로 유지하며 과거 입력을 추정해 v2로 바꾸지 않는다.
- 새 스키마를 이해하지 못하는 구버전 worker와 혼용하지 않는다. 접수/worker를 멈추고 진행 중 작업을 정리한 뒤 V10을 적용하고 호환 버전을 재개한다. 새 v2 실행이 존재한 DB에 구버전 앱을 그대로 되돌리지 않는다.

## 검증

`AnalysisV2IntegrationTests`는 공통 C-* 25개를 격리 PostgreSQL과 실제 루프백 HTTP로 실행한다. 네 작업·0개 결과·롤백·v1→v2 명시적 재시도·고정 버전·삭제·참조/미참조 근거 변경·현재 문서와 원래 직접 입력 문서의 분리·공개 분석 HTTP를 확인한다. 추가 테스트는 일곱 통신 실패의 저장, 상한 초과의 전송 차단, 기존 v1 실행 유지 및 직접 문서 삭제 연결점을 확인한다.

```powershell
.\gradlew.bat test --tests '*AnalysisV2IntegrationTests' --tests '*AnalysisIntegrationTests' --tests '*ProposalQueryIntegrationTests'
.\gradlew.bat check bootJar
```

가짜 응답의 BE 통합 검증과 실제 AI 런타임/모델 품질 검증은 구분한다. AI가 현재 켜져 있다는 사실만으로 v2 제공자·수신 저장소·제한·실제 모델이 준비됐다고 판단하지 않는다.
