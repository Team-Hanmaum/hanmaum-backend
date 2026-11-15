# 공유 링크

현재 참여자가 확정 항목을 선택해 링크를 발급한다. 링크를 열어도 로그인과 공간 참여가 필요하다. 링크는 자동 만료되지 않으며 해제 API는 제공하지 않는다.

## 발급

`POST /api/spaces/{spaceId}/share-links`에 세션·CSRF와 `clientRequestId`, `targets`를 보낸다. 각 대상에는 `itemId`, `expectedItemVersion`, `includesChange`가 필요하다. 한 개 이상의 서로 다른 항목을 선택한다.

대상의 버전을 확인한다. 삭제·재확인 항목은 발급 대상에서 제외하며 `includesChange: true`에는 유효한 확정 변경 이력이 필요하다. 대상 하나라도 실패하면 링크를 발급하지 않는다. 성공은 `201`과 `shareLinkId`, `shareUrl`, `cards`다. 외부 카드에는 항목 유형·변경 표시·URL만 제공한다.

## 조회

`GET /api/share-links/{token}`은 현재 공간 참여자에게 `spaceId`와 `items`를 반환한다.

| state | item |
| --- | --- |
| `AVAILABLE` | 조회 시점의 최신 `CareItemDetail` |
| `DELETED` | null |
| `NEEDS_RECONFIRMATION` | null |

미로그인은 `401`, 비참여자는 `403`, 없는 링크나 비활성 공간은 `404`다. 발급자가 공간을 떠나도 현재 참여자의 조회 권한은 유지된다.

## 저장·설정·연동

공유 URL의 접두사는 `SHARE_URL_PREFIX`로 덮어쓸 수 있으며, 운영 기본 경로는 `https://hanmaum.site/share/`다. 동일 발급 요청의 재전송에는 기존 `COMMAND_DIGEST_KEY`가 필요하다. 키 교체에는 이관 계획이 필요하다.

FE는 `/share/:token`에서 로그인 후 복귀와 항목 조회를 처리하고, 카카오 SDK로 공유 카드를 전송한다. [FE 카카오 공유 설정](https://github.com/Team-Hanmaum/hanmaum-frontend/blob/dev/docs/deployment.md#카카오톡-공유)
