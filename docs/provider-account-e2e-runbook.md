# 실제 Provider 계정 E2E 실행 절차

실제 OAuth/API E2E는 CI secret이나 저장소 파일로 token을 복사하지 않고 Android Keystore에 저장된
사용자 승인 session에서만 수행한다. 자동 테스트는 fake Provider로 429/5xx/비정상 SSE를 검증하고,
실계정은 정상 흐름과 사용자가 직접 유발한 취소만 확인한다.

## 결정론 fault 기준선

실계정 실행 전에 로컬 fake transport로 다음 항목이 통과해야 한다.

- Python Gateway: 401/403/404와 retryable 408/429/500/502/503/504, `Retry-After`, timeout/disconnect,
  malformed JSON/SSE, invalid UTF-8, event/전체 크기와 stream-open 이후 no-retry
- MobileAgent BFF: 401/403/404/429/500/502/503/504 고정 mapping, `text/event-stream`, UTF-8 chunk split,
  invalid UTF-8, multiline SSE, event 1 MiB·stream 32 MiB 상한, timeout과 HTTP 200 이후 redacted error
- Android direct Provider: strict UTF-8 SSE, 모든 field/comment를 포함한 event/전체 상한,
  OpenAI-compatible non-2xx·invalid success body redaction
- OAuth callback: stale port/path/state, 중복 code/state/error와 code+error 동시 입력 거부,
  Provider `error_description` 원문 비노출

```bash
python3.11 -m unittest discover -s tests -v
cd backend/mobile_agent_bff && .venv/bin/pytest -q
```

2026-08-09 로컬 기준선은 Python 106/106, BFF 39/39, Android OAuth/Provider unit,
Samsung OAuth 저장소 3/3·Provider 12/12·Integrated 10/10 통과다. 이는 실제 Provider 또는 staging
fault proxy 실행을 대체하지 않는다.

## Provider별 공통 절차

1. 별도 테스트 계정과 비용 한도를 준비한다.
2. 앱에 등록한 공식 client ID/redirect URI/scope를 운영자와 대조한다.
3. Samsung 실기기에서 로그인하고 token 원문이 화면·logcat·support bundle에 없는지 확인한다.
4. 모델 목록, non-stream 1회, stream 1회, 사용자 취소 1회를 실행한다.
5. [공식 정책 재검토](provider-official-policy-review-20260809.md)의 Provider별 endpoint 문서와
   owner 승인 계약으로 idempotency key/header 지원 여부를 검토한다.
   계약이 확인되지 않으면 `NEVER_AUTOMATIC`으로 기록하고 앱이 429·5xx·I/O 오류를 자동 재시도하지 않는지 확인한다.
   지원이 확인된 경우에만 `IDEMPOTENT_WITH_STABLE_KEY`를 사용하고 동일 논리 요청의 모든 transport attempt가
   같은 key/header를 재사용하는지 확인한다.
6. 같은 논리 요청을 다른 backend로 자동 fallback하지 않는지 확인한다.
7. 로그에는 Provider, model, status category, request ID와 redacted error code만 보존한다.
8. 로그아웃 후 refresh/access token과 bridge capability가 더 이상 동작하지 않는지 확인한다.

## 실제 callback lifecycle 추가 절차

이 절차는 credential-free fake 복구 테스트와 별개이며 계정 owner가 승인한 파괴적 테스트 창에서만 수행한다.

1. OAuth 동의 화면을 연 상태에서 Activity recreation을 유발하고 자동 token 교환·자동 로그인 재실행이 없는지 확인한다.
2. 새 로그인 시도를 시작한 뒤 callback 전에 app process를 종료하고 cold start한다.
3. `AUTH_FLOW_INTERRUPTED` 또는 `AUTH_SESSION_EXPIRED`만 표시되고 code/state/verifier/Provider 원문이 보이지 않는지 확인한다.
4. 기존 브라우저 callback이 token exchange를 시작하지 못하고, 사용자가 새 **로그인**을 누른 뒤에만 새 시도가 1회 시작되는지 확인한다.
5. token 저장 직후 종료 fixture에서는 정상 인증 token이 유지되고 stale lifecycle marker만 정리되는지 확인한다.
6. 각 단계의 화면·logcat에는 계정, client ID, callback query와 token을 남기지 않는다.

로컬 기준선에서는 fake Provider Activity recreation, orphaned encrypted transaction, 성공 token 우선과
Samsung `SM-S931N`의 credential-free `am force-stop` cold start가 통과했다. 이는 실제 Provider callback
중 process kill 완료를 의미하지 않는다.

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
