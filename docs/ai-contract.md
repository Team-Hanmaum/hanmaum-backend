# Spring ↔ FastAPI 분석 계약 v1

v2 공통 fixture의 출처·무결성 검사·BE 연결 범위와 미구현/확인 사항은 [v2 fixture 도입 문서](ai-contract-v2-fixtures.md)를 참고합니다. fixture 도입만으로 실행 계약이 v2로 전환되지는 않습니다.

v2 요청·응답 검증과 HTTP 어댑터는 별도로 준비되어 있습니다. [v2 어댑터 범위·오류 매핑·테스트](ai-contract-v2-client.md)를 참고합니다. 실행별 버전으로 v1/v2를 선택합니다. [v2 실행·저장·조회](ai-contract-v2-execution.md)를 참고하며, 아래 본문은 기존 v1 규칙입니다.

이 문서는 기존 BE↔AI 내부 계약입니다. AI 레포에는 stub과 계약 검증 기반이 있으며 실제 LLM 분석 및 전체 런타임 연동 완료와 구분합니다. 현재 동작은 양쪽 코드와 테스트로 확인하고 계약 변경은 양쪽 담당자가 함께 합의합니다. 공개 API의 공통 응답과 별도로 유지합니다.

## 요청

`POST /v1/analyses`, `Content-Type: application/json`

- `X-Internal-Api-Key`: 내부 서버 간 공유 비밀값. 브라우저에 전달하지 않습니다.
- `X-Request-Id`: 본문의 `requestId`와 동일한 UUID
- 호출자는 먼저 공간 접근 권한을 확인하고 같은 공간의 후보만 전달합니다.

```json
{
  "requestId": "00000000-0000-0000-0000-000000000001",
  "careSpaceId": "00000000-0000-0000-0000-000000000010",
  "recordId": "00000000-0000-0000-0000-000000000011",
  "content": "병원이 20일로 바뀌었대",
  "recordedAt": "2026-09-26T12:00:00Z",
  "timezone": "Asia/Seoul",
  "candidates": [
    {
      "itemId": "00000000-0000-0000-0000-000000000002",
      "version": 2,
      "type": "SCHEDULE",
      "summary": "2026년 10월 15일 재진"
    }
  ]
}
```

내부 요청의 원문은 최대 10,000 UTF-16 코드 단위, 후보는 최대 50개로 제한합니다. 이는 현재 내부 기술 제한이며 공개 입력 길이·사용량 정책을 확정하는 값이 아닙니다. `recordedAt`과 IANA 시간대는 상대 날짜 해석의 기준입니다. 시간·날짜가 불분명하면 추측해 채우지 않습니다.

## 응답

```json
{
  "requestId": "00000000-0000-0000-0000-000000000001",
  "suggestions": [
    {
      "operation": "UPDATE",
      "type": "SCHEDULE",
      "targetItemId": "00000000-0000-0000-0000-000000000002",
      "expectedVersion": 2,
      "candidateItemIds": [],
      "title": "재진 일정 변경",
      "evidence": "20일로 바뀌었대",
      "changes": { "date": "2026-10-20" }
    }
  ]
}
```

- 유형: `SCHEDULE`, `TASK`, `QUESTION`, `OBSERVATION`, `GUIDANCE`
- 작업: `CREATE`, `UPDATE`, `RESOLVE`
- `CREATE`: 대상 ID·버전은 `null`, 후보 ID 목록은 빈 배열
- 확실한 `UPDATE`·`RESOLVE`: 요청에 포함된 대상 ID와 해당 버전을 반환
- 사용자 선택이 필요한 `UPDATE`·`RESOLVE`: 대상 ID·버전은 `null`, `candidateItemIds`에 선택 가능한 후보 반환
- `evidence`: 원문에 실제로 존재하는 연속된 구절
- `changes`: 제안할 문자열 필드 값. 유형별 필드·형식·상태 전이 검증은 확정 기능에서 정의하며 엔티티에 직접 매핑하지 않음
- 제안은 최대 30개. 제안할 것이 없으면 빈 배열 반환

백엔드는 응답 형식, 요청 ID, 대상 후보 범위와 버전, 근거 구절을 검사합니다. 이 검사가 AI 의미 판단의 정확성을 보장하지는 않습니다. 모든 제안은 사용자 확인이 필요하고, 확정 시 DB의 현재 버전을 다시 검사해야 합니다.

## BE 공개 조회로의 변환

내부 요청/응답 구조는 유지합니다. BE의 `ProposalValueMapper`가 저장 문서에서 다음 공개 필드만 선택합니다. 이는 조회 변환이며 최종 반영의 필수값·길이·업무 검증 계약은 별도로 합의합니다.

| 유형 | 공개 `proposedValues` 필드 |
| --- | --- |
| 공통 | `title`(저장 제안 제목), `description` |
| SCHEDULE | `scheduledDate`, `scheduledTime`, `location` |
| TASK | `dueDate`, `assigneeMembershipId` |
| QUESTION | 공통 필드만 사용. 확정 답변 없음 |
| OBSERVATION | `observedDate`, `observedTimeText` |
| GUIDANCE | `sourceName`, `receivedDate` |

AI는 `changes`에 위 필드 이름을 사용해야 공개 조회에 해당 값이 나타납니다. 기존 예시의 `content`는 description, `date`는 해당 유형의 날짜, `time`은 scheduledTime으로만 호환합니다. 정식 필드와 별칭이 서로 다른 값을 가지면 null로 반환합니다. 저장 제목을 changes.title로 덮어쓰지 않습니다. 정의하지 않은 키·유형에 맞지 않는 키는 공개하지 않습니다.

누락·빈 문자열·변환 불가 값은 null입니다. 날짜는 유효한 `YYYY-MM-DD`, 일정 시각은 `HH:mm` 문자열만 사용하고 누락 시각을 자정으로 만들지 않습니다. 상대 날짜 추론을 조회에서 다시 실행하지 않습니다. 담당자 ID는 BE가 같은 공간의 현재 참여를 확인합니다. 내부 계약에 관계/상충 판정 결과가 없으므로 공개 `relationKind`, `needsConflictReview`는 null이며 false를 추정하지 않습니다. CareItem 후보 수집·의존성 검증은 연결됐으며 공개 CREATE 제안의 candidates는 빈 배열을 유지합니다. 실제 모델의 유형별 출력 일치와 관계 판정·UPDATE/RESOLVE 후보 선택 계약은 AI/BE 후속 연동 사항입니다.

## 실패·재시도

연결 타임아웃은 3초, 응답 타임아웃은 30초입니다. 잘못된 응답·연결 실패·외부 오류는 `AiServiceException`으로 정리합니다. 동기 API까지 전파되면 공통 처리기는 `502 AI_SERVICE_UNAVAILABLE`을 반환합니다. 비동기 분석에서는 이미 접수된 HTTP 응답을 바꾸지 않고 실행을 `FAILED`, 실패 사유를 `AI_SERVICE_UNAVAILABLE`로 저장합니다. 상태 조회 자체는 `200 SUCCESS`입니다. 외부 응답 본문은 사용자에게 전달하지 않습니다.

클라이언트는 자동 재시도하지 않습니다. BE는 원본과 최초 QUEUED 실행을 함께 저장하고, 사용자가 최신 실패 실행을 명시적으로 재시도하면 새 analysisId/requestId를 발급합니다. 원본의 recordedAt·Asia/Seoul은 재시도에서도 유지합니다. FE의 같은 clientRequestId 재전송은 접수 기록을 재생하며 AI를 다시 호출하지 않습니다. 중단 실행도 실패로 정리하고 자동 재전송하지 않습니다. FastAPI의 동일 requestId 처리 정책은 AI 담당자와 별도로 합의해야 하며 네트워크 경계의 정확히 한 번 실행을 보장하지 않습니다.

현재 BE는 같은 공간의 유효한 CareItem 후보를 전달하고 CREATE 제안을 저장합니다. 후보가 있어도 UPDATE/RESOLVE 저장은 아직 연결하지 않아 해당 결과를 `ANALYSIS_DEPENDENCY_UNAVAILABLE`로 처리합니다. 내부 DTO는 기존 세 작업을 그대로 유지하며, 제안 저장이 최종 관리 항목 반영을 의미하지 않습니다.

### CareItem 후보와 입력 근거 검증

- 실행기가 작업을 점유하는 트랜잭션에서 같은 공간의 VISIBLE·재확인 불필요 항목을 조회합니다. 유효한 현재 문서와 직접 입력/원본 근거가 있어야 하며, 제거된 원본 근거가 연결된 항목은 후보에서 제외합니다.
- V8의 `analysis_input_item`에 항목 버전·현재 문서 ID를, `analysis_input_source`에 원본 ID·버전을 기록합니다. V9의 `source_evidence_ids`에는 그때 사용한 원본 근거 ID를 기록하며 인용문은 복사하지 않습니다. 이후 연결이 제거되어도 기록된 ID로 검증합니다.
- 결과 저장 전에 항목·문서·원본과 캡처한 근거를 다시 확인합니다. 근거 행이 없어졌거나 인용문/근거가 제거됐거나 해당 후보의 `care_item_basis` 연결이 끊겼으면 원본·항목 버전이 그대로여도 실행을 DISCARDED로 만들고 결과를 저장하지 않습니다. 다른 항목에 같은 근거가 연결되어 있어도 해당 후보의 연결을 대체하지 않습니다. 관련 변경·삭제는 기존 공간 행 잠금 순서를 공유해야 합니다.
- 제안 목록·상세 조회에서도 같은 후보 항목·문서·원본·근거 의존성을 하나의 SQL 스냅샷에서 확인합니다. 분석 완료 후 의존성이 무효가 되면 저장 내용이 남아 있어도 제목·제안 값·근거를 노출하지 않으며, 조회에서 DB 상태를 변경하지 않습니다.
- 근거 ID의 빈 배열은 직접 입력만 사용한 후보입니다. null은 V8 실행에서 근거를 기록하지 않았다는 뜻이므로 완료를 허용하지 않습니다. 현재 근거로 과거 입력을 추정해 채우지 않습니다. 후보를 사용하지 않은 기존 실행과 기존 항목·문서 데이터는 유지합니다.
- 현재 `CandidateItem.summary`는 저장된 현재 문서 JSON을 문자열로 전달합니다. 예시의 자연어 요약을 생성하는 기능은 아직 없으며, 실제 모델 연결 전 요약 표현 방식을 AI와 합의해야 합니다. 후보 50개·summary 2,000 UTF-16 코드 단위의 기존 내부 한도를 넘으면 부분 선택·임의 축약 없이 의존성 실패로 처리합니다. 공개 일정 등록 길이·공간별 항목 상한을 새로 정한 것이 아닙니다.

테스트에서는 가짜 HTTP 응답을 사용합니다. 실제 모델 품질은 신규 항목, 날짜 변경, 질문 해소, 모호한 후보, 언급하지 않은 사실 등의 합성 사례로 별도 평가합니다.
