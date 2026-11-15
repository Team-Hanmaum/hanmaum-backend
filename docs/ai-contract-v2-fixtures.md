# AI v2 공통 fixture

- 원본: AI 저장소 `97972f9ff9980646646947571a8bddfce2df5d9e`의 `tests/fixtures/v2/`.
- 계약 revision: `v2-20261021.1`. BE 사본은 [테스트 리소스](../src/test/resources/ai-contract/v2/README.md)에 있다.
- `manifest.json` SHA-256은 `524f95bb6c1b27c3ef5cd781a15fd95f372b52b0b79787bf952316b12033183e`다. 원본 JSON·문서·줄바꿈을 바꾸지 않는다.

| 구분 | BE에서 확인하는 범위 |
| --- | --- |
| STATIC / LIMITS | 요청·응답 계약 검증, 생성 원문의 크기·해시·문자열 합계 |
| RECEIPT / HTTP | BE 오류 매핑, 재호출 여부, 본문 크기와 전송 경계 |
| BACKEND | Testcontainers DB에서 실행·저장·공개 조회·무효화 검증 |

[생성 규칙](../src/test/resources/ai-contract/v2/GENERATION.md)을 `V2GeneratedPayloads`로 재현한다. AI 수신 저장소·실제 모델은 BE의 가짜 응답과 별도로 검사한다.

```powershell
.\gradlew.bat test --tests '*AiV2*' --tests '*HanmaumAiV2ClientTests' --tests '*HanmaumAiClientTests'
.\gradlew.bat check bootJar
```

첫 명령은 어댑터·fixture 검사, 두 번째는 Docker가 필요한 DB 통합 테스트까지 실행한다. [통신 계약](ai-contract-v2-client.md) · [운영 검증](deployed-analysis-verification.md)
