# 관리 항목 근거·확정 변경 이력 조회

기본 경로는 `/api/spaces/{spaceId}/items/{itemId}`다. 세 API 모두 로그인한 현재 공간 참여자가 조회한다. GET에는 CSRF·편집 잠금·clientRequestId가 필요하지 않다. 공통 응답과 `Cache-Control: no-store`를 사용한다.

| 호출 | data |
| --- | --- |
| GET /evidence | `{items: [...]}` 현재 연결된 근거 |
| GET /history | `{items: [...]}` 확정 버전 내림차순 전체 이력 |
| GET /history/{version} | 선택 버전과 직전 저장 버전의 비교 |

## 현재 근거

행은 `evidenceId, kind, status, recordId, text, createdByMembershipId, createdAt, fieldPaths`다. 근거 연결 생성 시각·ID 오름차순 전체 조회이며 별도 페이지 상한은 없다.

- `RECORD_QUOTE`: 소식 원본의 인용. evidenceId는 기존 evidence ID이고 작성자·시각은 반영자가 아닌 원본 기록자·등록 시각이다.
- `DIRECT_INPUT`: 이전 문서를 부모로 갖지 않는 직접 입력. evidenceId는 care_item_basis ID, text는 저장된 입력 JSON의 문자열 표현이다. 문자열 값의 공백·줄바꿈·내용은 보존하지만 최초 HTTP JSON의 공백·키 순서를 재현하지 않는다.
- `DERIVED`: 부모 문서에 의존하는 직접 수정 입력 등. 기존 값을 참고한 입력을 독립된 새 원본으로 표시하지 않는다. text는 저장된 입력 JSON 표현이다.
- 현재 `ANSWER`·`RECONFIRMATION` 입력 경로는 미구현이므로 이 종류의 근거를 만들어 반환하지 않는다.
- `VALID`만 text를 포함한다. 원본 제거·버전 불일치, 인용 제거, 제안 무효화, 직접 문서 또는 조상 문서의 가시성 상실이면 `REDACTED`, text와 recordId는 null이다. 남아 있는 독립 근거는 각각 검증한다.
- 회원 연결이 제거된 입력자는 createdByMembershipId=null이다. 공간에서 나간 것만으로 작성자를 지우지 않는다.
- 소식 원본의 기록 시각이 제거된 경우 createdAt=null이다. 이를 근거 연결·승인 시각으로 대체하지 않는다.
- **fieldPaths는 아직 저장하지 않으므로 항상 []다.** 해당 근거가 전체 필드를 뒷받침한다는 뜻으로 해석하지 않는다. 필드별 연결 추적을 임의로 역산하지 않는다.

## 확정 이력과 비교

목록 행은 `version, changeKind, changedBy, changedAt, redactedFields`다. 대기 제안·요청은 포함하지 않는다. 저장 이력이 없으면 빈 배열이다. 일반 삭제 항목도 현재 참여자에게 보존된 이력을 제공한다.

- 공개 changeKind는 현재 저장 동작 `CREATE, UPDATE, RESOLVE, LINK_EVIDENCE`와 `ASSIGNEE_CHANGE, STATUS_CHANGE`다. `UNASSIGN`은 ASSIGNEE_CHANGE로 표시한다. UPDATE 중 조회 가능한 전후 값에서 상태만 바뀌었으면 STATUS_CHANGE, 담당자만 바뀌었으면 ASSIGNEE_CHANGE다. 여러 필드가 바뀌거나 내용을 확인할 수 없으면 UPDATE를 유지한다.
- changedBy는 기존 작성자 표현 `{membershipId, displayName, withdrawn}`을 사용한다. 회원 연결 제거 시 앞의 두 값은 null, withdrawn=true다. 단순 공간 참여 종료는 탈퇴로 표시하지 않는다. 회원 탈퇴 실행 API 자체의 구현을 의미하지 않는다.
- 상세는 `itemId, version, previousVersion, before, after, changedBy, changedAt, evidenceIds, redactedFields`다. before/after는 당시 title·description·유형별 fields·status, 질문의 confirmedAnswer만 제공한다. 내부 AI 제안·요청 문서를 그대로 반환하지 않는다.
- 최초 버전은 previousVersion과 before가 null이다. 그 외에는 선택 버전보다 작은 가장 최근 저장 버전과 비교한다. 과거 담당자 ID는 현재 담당자로 바꿔 쓰거나 현재 참여 여부 때문에 지우지 않는다.
- 제거된 문서와 그 문서를 복사한 파생 문서는 DB의 `care_document_available`로 가린다. before/after를 각각 검증하여 읽을 수 없는 쪽은 null, redactedFields에 `before` 또는 `after`를 넣는다. 목록에도 같은 표시를 제공하고 버전·변경자·시각은 유지한다. 현재 문서의 가시성 상실만으로 독립적인 과거 이력까지 모두 숨기지 않는다.
- evidenceIds는 **저장된 결정의 항목/버전 및 직접 문서 연결로 해당 변경과의 관계를 확인할 수 있는, 현재 남아 있는 근거 연결만** 제공한다. LINK_EVIDENCE는 같은 문서를 재사용하더라도 해당 버전의 결정에 연결된 인용만 제공한다. 현재 근거 전체를 과거 버전에 복사하거나 시각으로 과거 연결을 추정하지 않는다. 연결이 제거되었거나 관계를 입증할 수 없는 기존 데이터는 빈 배열일 수 있다. 과거 근거 연결 전체의 불변 스냅샷 저장을 의미하지 않는다.

## 권한·일관성·오류

공간의 활성 상태·현재 참여·항목 소속·근거·비교값을 하나의 read-only REPEATABLE_READ 트랜잭션에서 조회한다. 동시 삭제·참여 종료 전후의 값을 같은 응답 안에서 섞지 않고 다음 조회에서 최신 상태를 적용한다. GET은 업무 데이터나 잠금·분석 상태를 변경하지 않는다.

- 400 INVALID_REQUEST: 잘못된 UUID, version이 1 이상 정규 십진 정수 문자열이 아니거나 DB bigint 범위를 초과한 경우
- 401 UNAUTHENTICATED: 세션 없음·현재 회원 검증 실패
- 403 FORBIDDEN: 현재 공간 비참여자
- 404 RESOURCE_NOT_FOUND: 공간 없음/비활성, 항목의 공간 불일치·항목 없음·요청한 확정 버전 없음
- 500 INTERNAL_ERROR: 조회 실패. 일부 결과만 성공으로 반환하지 않는다.

V14까지의 기존 저장 구조를 조회하며 새 마이그레이션은 없다. 삭제 실행·가족 답변·재확인 입력 및 필드별 근거 추적은 별도 범위다. 노션 초안의 미정 표현과 현재 지원 범위를 구분하며 프론트에서는 REDACTED 근거 링크를 비활성화하고 null 비교값을 삭제된 내용으로 표시한다.
