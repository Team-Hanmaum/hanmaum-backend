# AI v2 실행·저장·조회

내부 요청 형식은 [통신 계약](ai-contract-v2-client.md)을 따른다. 운영은 v2로 새 작업을 접수하며 계약 버전은 실행에 고정한다. v2 실패 시 v1으로 재호출하지 않는다.

## 접수와 실행

1. 원본, 최초 `QUEUED` 실행, 요청 성공 기록을 한 트랜잭션으로 저장한다.
2. 워커는 현재 참여와 유효 원본을 검사하고 같은 공간의 후보·근거·버전을 캡처한다. 한도를 넘으면 원본을 보존하고 분석을 실패로 기록한다.
3. DB 트랜잭션 밖에서 AI를 호출한다. 저장 시 lease와 참여, 캡처한 원본·항목·문서·근거를 다시 검사한다. 무효 입력을 사용한 결과는 폐기한다.
4. 제안·문서·근거를 한 트랜잭션에서 저장한다. 실패 시 일부만 남기지 않는다.

lease 만료는 `ANALYSIS_INTERRUPTED`로 처리한다. 최신 실패에 대한 명시적 재시도만 새 분석 ID를 만든다. [워커 배포 설정](deployment.md#분석-워커-활성화비활성화)

## 공개 조회

기존 분석·제안 URL을 사용한다. 내부 `CREATE`, `UPDATE`, `RESOLVE`, `LINK_EVIDENCE`는 공개 응답의 `CREATE_ITEM`, `UPDATE_ITEM`, `RESOLVE_QUESTION`, `LINK_EVIDENCE`에 대응한다.

- `currentValues`는 유효한 확정 대상의 값, `proposedValues`는 변경분이다. 설정과 삭제는 `changedFields`와 `clearFields`로 구분한다.
- `relationKind`, `reviewReasons`, `candidates`, `comparisonBasis`를 함께 제공한다. 불명확한 대상을 자동으로 확정하지 않는다.
- 확정 항목은 [소유자 반영](proposal-decisions.md)을 거쳐 저장한다.

## 삭제 전파와 배포

[소식 원본 삭제](api-conventions.md#소식-원본-삭제-api)는 영향 범위의 분석과 파생 문서·근거를 함께 정리한다. 삭제되거나 무효가 된 내용은 조회와 늦은 AI 응답으로 다시 노출되지 않는다. 독립적으로 유효한 근거는 보존한다.
