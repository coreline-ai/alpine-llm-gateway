# Alpine Runtime Phase 4 검증 기록

- 검증일: `2026-08-01 KST`
- 대상: 보안 Host Bridge, 분리형 Python Gateway package, lifecycle owner, Alpine `llmctl`
- 실기기: Samsung `SM-S931N`, Android API 36, `arm64-v8a`

## 구현 결과

| 영역 | 결과 |
|---|---|
| 모듈 이동 | `HostBridgeServer`, `AlpineLlmGatewayClient`를 `:alpine-llm-bridge`로 이동 |
| Gateway 자산 | `:alpine-llm-gateway-pack-bundled`로 rootfs와 분리, version/protocol/SHA-256/size lock 적용 |
| lifecycle | `AlpineLlmBridgeController`가 bridge→runtime→gateway start/health/stop/restart를 단일 소유 |
| credential | guest에는 OAuth token 대신 TTL capability 파일 경로만 전달 |
| 정책 | loopback origin, fail-closed model allowlist, request/response/SSE limit, timeout, closed event 적용 |
| protocol | normalized Host Bridge SSE용 `android-host-bridge` Python adapter 추가 |
| process | cancel/stop 시 PRoot와 추적 guest process를 함께 종료 |
| DNS | Android active network DNS를 app-private rootfs `resolv.conf`에 안전하게 반영 |

## 자동 검증

- Python protocol/policy/provider/gateway/CLI/architecture 회귀 테스트
- Host Bridge 401, 429, 5xx redaction, timeout, token expiry/rotation, response limit
- Gateway client 5xx body redaction, malformed JSON/SSE, response/stream limit
- Controller checksum mismatch, single owner, restart rotation, gateway crash, secret cleanup
- Android bridge/runtime/gateway-pack 단위 테스트, release AAR, lint, Probe APK build
- deterministic Python Gateway layer 재생성과 별도 production lock 일치

## 삼성폰 E2E 결과

`scripts/runtime/run-llm-bridge-probe-device.sh <device-serial>` 결과는 `success: true`였다.

| 항목 | 결과 |
|---|---|
| `llmctl models` | exit 0, `bridge-test` 확인 |
| `llmctl run` | exit 0, `bridge-ok` 확인 |
| `llmctl run --stream --format jsonl` | start/delta/done, `stream-ok` 확인 |
| cancel | command process 시작·취소 승인·process cleanup 확인 |
| restart | capability 회전 및 새 Gateway health 확인 |
| health | owner/runtime/gateway process/capability TTL/Host Bridge/Python Gateway/protocol 전체 true |
| stop | runtime `READY`, Gateway/llmctl process 모두 정리 |

## 발견 이슈와 수정

1. Python OpenAI adapter가 Host Bridge의 normalized SSE를 해석하지 못했다. 전용 adapter와 protocol version gate를 추가했다.
2. minirootfs에 DNS 파일이 없어 `apk add python3`가 실패했다. Android system DNS 반영 단계를 runtime start에 추가했다.
3. PRoot wrapper만 종료하면 guest Python이 남을 수 있었다. command PID 경계와 descendant signal cleanup을 추가했다.

## 남은 범위

- 실제 Provider 계정/OAuth inference E2E는 실제 계정과 앱 소유 client registration이 필요한 별도 검증이다.
- Phase 5에서 빠른 채팅/Alpine 작업 모드 routing, 명시적 fallback, 중복 요청 방지 계약을 구현한다.
