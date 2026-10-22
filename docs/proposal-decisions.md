# AI 제안 검토 및 소유자 반영 (#33)

2026-10-22 합의 및 2026-10-23 크기 제한 보완 합의 기준. 기존 BE↔AI v1/v2 계약과 공통 fixture는 변경하지 않는다. AI는 검토 제안만 저장하고, 실제 항목 변경은 아래 소유자 API에서 수행한다. 관련 항목 재분석·추가 제안 생성은 후속이다.

## 공개 API

모든 경로 앞에 `/api/spaces/{spaceId}`를 붙인다. POST는 로그인 세션·CSRF·JSON을 사용하고 응답은 공통 형식 및 `Cache-Control: no-store`를 따른다.

| Method / 경로 | 권한 | 성공 data |
| --- | --- | --- |
| POST `/proposal-applications` | 현재 소유자 | 200 `{batchId, appliedAt, results: [{proposalId, itemId, itemVersion}]}` |
| POST `/proposal-declines` | 현재 소유자 | 200 `{batchId, declinedProposalIds, decidedAt}` |
| POST `/items/{itemId}/edit-lock` | 현재 참여자 | 201 `{itemId, leaseToken, holderMembershipId, expiresAt, renewAfterSeconds}` |
| POST `/items/{itemId}/edit-lock/renewals` | 현재 참여자·본인 토큰 | 200 동일 잠금 정보, `leaseToken: null` |
| POST `/items/{itemId}/edit-lock/releases` | 현재 참여자·본인 토큰 | 204, 본문 없음 |

### 일괄 반영

```json
{
  "clientRequestId": "832cbe35-9970-4f99-a219-8f019315b3ab",
  "entries": [
    {
      "proposalId": "2f4fd7af-5b2f-4d38-b1ed-8ea1141db910",
      "action": "CREATE_ITEM",
      "reviewToken": "<제안 상세에서 받은 값>",
      "values": {
        "itemType": "SCHEDULE",
        "title": "진료 일정",
        "description": null,
        "fields": {"scheduledDate": "2026-10-25", "scheduledTime": null, "location": null}
      }
    }
  ]
}
```

- `clientRequestId`, `entries`는 필수다. 1~20개, 중복 제안 ID는 금지한다. 같은 기존 항목을 대상으로 하는 제안은 한 묶음에 하나만 선택한다.
- 각 항목의 `proposalId`, `action`, `reviewToken`은 필수다. `action`과 유형은 원래 제안과 같아야 한다. 근거·작성자·원본은 서버에서 결정한다.
- 기존 항목을 변경할 때는 `targetItemId`, `expectedItemVersion`(양의 정수 **문자열**), `leaseToken`도 필요하다. CREATE에서는 이 세 필드를 생략하거나 null로 둔다.
- 확정 대상은 변경하지 못한다. 모호한 대상은 상세의 `candidates` 중 하나를 선택한다. `TARGET_NOT_FOUND`는 대상 임의 지정이나 CREATE 변환을 허용하지 않으며 보류/미적용한다.
- 화면에서 수정하는 초안은 FE가 보관한다. 별도 자동 저장 API는 없다. `values`는 사용자가 최종 검토한 값이며 AI `changes`를 서버가 자동 병합하지 않는다.

| action | values |
| --- | --- |
| CREATE_ITEM | `{itemType, title, description?, fields}`. title은 공백이 아닌 문자열, fields는 객체(빈 객체 허용) |
| UPDATE_ITEM | `{title?, description?, fields?, status?}`. 실제 변경할 값이 하나 이상 필요 |
| RESOLVE_QUESTION | `{confirmedAnswer}`만. 공백이 아닌 확정 답변, 대상은 UNCONFIRMED 질문 |
| LINK_EVIDENCE | 생략 또는 null. 현재 내용·상태를 유지하고 근거 연결과 이력을 저장 |

| 유형 | fields의 허용 필드 | 생성 상태 |
| --- | --- | --- |
| SCHEDULE | scheduledDate, scheduledTime, location | SCHEDULED |
| TASK | dueDate, assigneeMembershipId | NOT_STARTED |
| QUESTION | 없음 (`{}`) | UNCONFIRMED |
| OBSERVATION | observedDate, observedTimeText | null |
| GUIDANCE | sourceName, receivedDate | null |

- UPDATE 생략은 기존 값을 유지하고, nullable 필드의 명시적 null은 값을 지운다. title은 null로 지울 수 없고 fields 자체의 null은 거부한다. 종료된 담당자도 생략 시 저장값은 유지하며 공개 조회·AI 입력에서는 계속 null로 가린다. 담당자 변경을 명시하면 같은 공간의 현재 참여 여부를 검증한다.
- 날짜는 실제 유효한 `YYYY-MM-DD`, 시각은 `HH:mm`이다. 미상 값은 null, 담당자는 같은 공간의 현재 참여만 선택한다. 과거/미래 날짜 제한은 추가하지 않았다.
- 반영·미적용 POST의 원시 JSON 본문은 최대 1 MiB(1,048,576바이트)다. Content-Length 유무와 무관하게 JSON 디코딩 전에 최대 상한+1바이트까지만 읽고 초과 시 `413 PAYLOAD_TOO_LARGE`로 거부한다.
- 자유입력 문자열은 각각 10,000 UTF-16 단위 이하, CREATE/UPDATE/RESOLVE로 새로 저장할 최종 문서의 묶음 합계는 512 KiB(524,288바이트) 이하다. 후자는 생략으로 유지한 값·확정 답변을 포함한 최종 문서를 공백 없는 UTF-8 JSON으로 직렬화한 바이트 수의 합이며 JSONB 디스크 크기가 아니다. LINK_EVIDENCE는 새 문서를 저장하지 않으므로 합계에서 제외한다. 초과는 `400 INVALID_REQUEST`, 값 자르기·부분 반영 없음이다. 문자열·직렬화 상한은 서비스 직접 호출에도 적용한다.
- 일정 상태 변경은 SCHEDULED → COMPLETED/CANCELED다. 할 일은 NOT_STARTED → IN_PROGRESS/COMPLETED, IN_PROGRESS → COMPLETED, COMPLETED → IN_PROGRESS다. 동일 상태만 보내고 다른 변경이 없으면 거부한다.
- 질문 확정 답변은 `confirmedAnswer`로 저장한다. 확인된 질문의 제목·설명 UPDATE는 허용하되 기존 확정 답변과 CONFIRMED 상태를 유지한다. 확정 답변 교체·질문 재열기·구성원 답변 제출은 이번 범위가 아니다. 다음 분석의 `currentValues.answerText`에는 이 확정 답변만 전달한다.
- CREATE는 버전 `"1"`, 기존 항목의 UPDATE/RESOLVE/LINK는 각 `+1`이다. 공간 버전은 변경하지 않는다. 원래 AI 제안은 보존하고 확정 내용은 별도 문서·이력으로 저장한다.

### 일괄 미적용

```json
{
  "clientRequestId": "d1b999f7-1956-43af-962e-df568e236f71",
  "entries": [{"proposalId": "2f4fd7af-5b2f-4d38-b1ed-8ea1141db910", "reviewToken": "<상세 값>"}]
}
```

유효한 미처리 제안만 가능하다. 항목·버전·편집 잠금은 바꾸지 않고 `DECLINED`를 기록한다. 미적용 사유 입력과 되돌리기는 없다.

## 잠금·검토·트랜잭션

- 잠금 획득 본문은 `{"expectedItemVersion":"1"}`, 갱신/해제는 `{"leaseToken":"<획득 응답>"}`다. 기본 유효 시간은 2분, 권장 갱신 간격은 30초이며 `CARE_ITEM_EDIT_LOCK_DURATION`, `CARE_ITEM_EDIT_LOCK_RENEWAL`로 분리했다. 갱신 간격은 1초 이상이고 유효 시간보다 짧아야 한다.
- 잠금 획득·갱신·만료 확인·공개 조회는 PostgreSQL 시간 기준이다. 쓰기는 공간·항목 행 잠금 획득 후 DB 현재 시각을 읽으며 애플리케이션 노드의 시계나 오래된 트랜잭션 시작 시각을 사용하지 않는다.
- 토큰은 획득 응답에서만 제공하고 DB에는 HMAC만 저장한다. 같은 사용자라도 다른 탭에서 기존 잠금을 빼앗거나 재획득하지 못한다. 응답 유실 시 만료 후 다시 획득한다. 유효 잠금이 없을 때 해제는 204이며, 이전 토큰으로 새 잠금을 해제할 수 없다.
- 현재 참여자는 잠글 수 있지만 소유자 반영 권한을 얻지는 않는다. 참여 종료 시 그 참여의 잠금을 제거한다. 기존 항목 반영 성공 시 잠금을 해제하고 실패 시 기존 잠금을 유지한다.
- `reviewToken`은 유효한 OPEN 제안 상세에서 발급한다. 회원·참여·제안·캡처한 입력에 묶인 HMAC이며 저장/고정 TTL은 없다. 반영 시 상태와 입력의 유효성도 다시 검사한다. 토큰이 권한 검사를 대신하지 않는다.
- 짧은 쓰기 트랜잭션에서 요청 ID → 공간/현재 참여 → 제안/항목 순으로 잠근다. **전체 항목을 변경 전 상태로 검증한 다음** 항목·문서·근거·이력·제안 처리·성공 기록을 함께 저장한다. 도중 DB 실패도 전체 롤백한다. AI 호출은 하지 않는다.
- 동일 사용자·clientRequestId·같은 업무 내용은 최초 batchId/결과 ID/버전/시각을 재전송한다. 현재 소유자 및 최초 참여 유지가 필요하다. 업무 내용 비교에는 항목 순서·작업·대상·버전·values를 포함하되 검토/잠금 토큰은 제외한다. JSON 객체 키 순서는 무관하고 생략과 null은 구분한다.
- 이미 성공한 요청은 원본 삭제 뒤에도 **텍스트 없는 결과 메타데이터**만 재전송한다. 새 요청 ID로 같은 처리된 제안을 다시 반영하지 못한다.

## 조회와 삭제 전파

- 제안 목록은 REVIEW_PENDING/INVALIDATED/APPLIED/DECLINED를 지원한다. 기본은 REVIEW_PENDING이다. 상세 `decision`은 `{batchId, decidedAt, itemId, itemVersion, appliedValues}`이며 처리 전에는 null, DECLINED의 항목/값은 null이다. `appliedValues`는 반영 시 문서의 title/description/fields/confirmedAnswer이고 상태는 항목 조회로 확인한다.
- OPEN은 모든 캡처 입력의 현재 버전·문서·근거를 계속 확인한다. 선택한 제안을 반영해 버전이 바뀌면 선택하지 않은 다른 제안이 INVALIDATED가 될 수 있다. 다른 일정/할 일을 자동 수정하거나 새 분석을 실행하지 않는다.
- 처리된 제안의 APPLIED/DECLINED는 처리 이력이다. 자신의 반영으로 변경된 항목 버전 때문에 이 이력이 사라지지 않는다. 다만 출처가 삭제/무효화되면 제목·제안 값·근거·파생 확정 값은 숨기고 처리 메타데이터만 남긴다.
- `CareItem` 상세는 `editLock` 요약과 `confirmedAnswer`를 제공한다. 타인의 잠금 토큰은 조회되지 않는다. 기존 answers 배열은 별도 답변 제출 기능 전까지 빈 배열이다.
- 확정 문서는 이전 문서와 AI 출력/입력 문서의 의존성을 기록한다. 소유자가 AI 내용을 수정했어도 삭제 의존성을 끊지 않는다. 원본·직접 입력 문서 삭제 연결점은 파생 현재 값·이력 문서·답변·후속 제안까지 같은 트랜잭션에서 내용을 제거한다. 독립 원문 문서는 유지한다.
- 이후 원본 삭제/근거 편집 API는 기존 `invalidateSource` / `invalidateBasis` / `invalidateDirectDocument`를 같은 공간 잠금·트랜잭션에서 호출해야 한다. 공개 삭제 API·독립 근거로 현재 값을 재구성하는 기능은 후속이다.

## 오류와 로컬 검증

| HTTP / code | 조건 |
| --- | --- |
| 400 INVALID_REQUEST | 본문·유형·허용 필드·대상 선택·날짜·담당자·묶음 크기·중복 ID·실제 변경 없음 |
| 401 UNAUTHENTICATED | 유효한 로그인 없음 |
| 403 FORBIDDEN | 현재 참여/소유자 권한·CSRF·다른 사람의 잠금 갱신/해제·재전송 참여 불일치 |
| 404 RESOURCE_NOT_FOUND | 활성 공간 또는 같은 공간의 대상 없음·사용 불가능한 항목 |
| 413 PAYLOAD_TOO_LARGE | 반영·미적용 원시 JSON 요청 본문이 1 MiB 초과 |
| 409 VERSION_CONFLICT | 현재 항목 버전 불일치 |
| 409 PROPOSAL_STATE_CONFLICT | 이미 처리/무효인 제안·다른 검토 토큰·허용하지 않는 상태 전이 |
| 409 PROPOSAL_BATCH_CONFLICT | 같은 대상 항목을 한 묶음에서 두 번 선택 |
| 409 ITEM_LOCKED / EDIT_LOCK_EXPIRED | 기존 잠금/다른 편집자, 본인 잠금 만료·토큰 불일치 |
| 409 IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS | 같은 요청 ID의 다른 업무 내용 또는 처리 중 |
| 500 INTERNAL_ERROR | 저장 등 예상하지 못한 실패, 전체 롤백 |

Swagger에서는 로그인 → CSRF Authorize → 제안 상세의 reviewToken 확인 → 기존 대상이면 잠금 획득 → 최종 values로 일괄 반영 → 제안 APPLIED와 항목/버전 확인 순서로 검증한다. 새 업무에는 새 clientRequestId, 성공 재전송에는 원래 값을 사용한다. AI가 빈 제안 배열을 반환하면 수동 반영할 제안이 없으므로 이 흐름은 실행할 수 없다.

격리 Testcontainers DB에서 합성 AI 응답으로 검증한다. 실제 AI 서버·유료 모델이나 개발 DB의 테스트 데이터 조작 없이 CREATE 5유형, UPDATE/RESOLVE/LINK, 모호한 대상, 권한·버전·잠금, 원자성·동시 요청·재전송, 삭제 후 내용 제거와 기존 계약 회귀를 확인한다. V11은 적용된 V1~V10을 수정하지 않는 추가 마이그레이션이며, 기존 문서 의존성을 보충한다. 운영 적용 시 기존 쓰기/분석 실행기를 중지한 뒤 V11 검증과 호환 버전 기동을 완료하고 재개한다.
