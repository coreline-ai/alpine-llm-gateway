# 실제 Provider 계정 E2E 실행 절차

실제 OAuth/API E2E는 CI secret이나 저장소 파일로 token을 복사하지 않고 Android Keystore에 저장된
사용자 승인 session에서만 수행한다. 자동 테스트는 fake Provider로 429/5xx/비정상 SSE를 검증하고,
실계정은 정상 흐름과 사용자가 직접 유발한 취소만 확인한다.

## Provider별 공통 절차

1. 별도 테스트 계정과 비용 한도를 준비한다.
2. 앱에 등록한 공식 client ID/redirect URI/scope를 운영자와 대조한다.
3. Samsung 실기기에서 로그인하고 token 원문이 화면·logcat·support bundle에 없는지 확인한다.
4. 모델 목록, non-stream 1회, stream 1회, 사용자 취소 1회를 실행한다.
5. Provider 공식 문서 또는 owner 승인 계약으로 idempotency key/header 지원 여부를 검토한다.
   계약이 확인되지 않으면 `NEVER_AUTOMATIC`으로 기록하고 앱이 429·5xx·I/O 오류를 자동 재시도하지 않는지 확인한다.
   지원이 확인된 경우에만 `IDEMPOTENT_WITH_STABLE_KEY`를 사용하고 동일 논리 요청의 모든 transport attempt가
   같은 key/header를 재사용하는지 확인한다.
6. 같은 논리 요청을 다른 backend로 자동 fallback하지 않는지 확인한다.
7. 로그에는 Provider, model, status category, request ID와 redacted error code만 보존한다.
8. 로그아웃 후 refresh/access token과 bridge capability가 더 이상 동작하지 않는지 확인한다.

실행 결과는 `integration-fixtures/provider-e2e/report.template.json`을 복사해 각 Provider의
`models`, `non_stream`, `stream`, `cancel`, `logout` 상태만 `PASS`/`FAIL`/`NOT_RUN`으로 기록한다.
token, authorization/cookie, API key, raw request/response와 Provider 본문은 넣지 않는다.

실행된 각 Provider 항목에는 `idempotency` 객체를 추가한다. 현재 Android adapter의 기본값은
`NEVER_AUTOMATIC`이며 공식 계약과 안정 key 전달이 모두 검증되기 전에는 자동 재시도를 켤 수 없다.

```bash
python3 scripts/verify-provider-e2e-report.py /path/to/redacted-report.json --require-executed
```

승인 전 기본 template은 `executed=false`이며 validator 통과가 실제 E2E 성공을 의미하지 않는다.

## 의도적으로 하지 않는 검증

- 실제 계정에 429나 5xx를 강제로 발생시키지 않는다.
- token/authorization header를 adb, shell environment 또는 JSON report로 내보내지 않는다.
- 다른 앱의 public client ID를 복사해 배포하지 않는다.

실제 계정·공식 client registration·비용 승인이 없으면 이 gate는 `외부 조건 대기`로 기록한다.
