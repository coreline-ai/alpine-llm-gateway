# Alpine LLM Gateway

Alpine Linux 샌드박스 안에서 Claude, GPT, Gemini 계열 LLM을 호출하기 위한 독립 모듈입니다.

이 모듈은 OpenMinis에 의존하지 않습니다. Python 3 표준 라이브러리 Gateway와 재사용 가능한 Android Library를 포함합니다. Alpine rootfs 안에서는 `llmctl`을 사용하고 Android에서는 OAuth token을 Host에만 보관한 채 loopback bridge로 LLM을 호출할 수 있습니다.

## 구조

```text
Alpine llmctl
      │
      ▼
alpine-llm-gatewayd :8787 (127.0.0.1)
      │
      ├── Policy Engine
      ├── Provider Adapter
      └── HTTPS
            ├── OpenAI-compatible
            ├── Anthropic
            └── Gemini
```

기본값은 `127.0.0.1`에만 바인딩합니다. API Key는 설정 파일에 직접 넣지 않고 환경변수에서 읽는 방식을 권장합니다.

## 빠른 실행

```bash
cd alpine-llm-gateway
cp config.example.json config.json
export LLM_API_KEY="..."
export LLM_MODEL="gpt-4o-mini"

python3 -m alpine_llm.cli serve --config config.json
```

다른 터미널에서:

```bash
python3 -m alpine_llm.cli models
python3 -m alpine_llm.cli run --model auto --prompt "Alpine 환경에서 동작하는지 설명해줘"
python3 -m alpine_llm.cli run --model auto --prompt "스트리밍 테스트" --stream --format jsonl
```

## 입력 계약

Gateway는 OpenAI 호환 메시지 형식을 공통 입력으로 사용합니다.

```json
{
  "model": "auto",
  "messages": [
    {"role": "user", "content": "요청 내용"}
  ],
  "max_tokens": 1024,
  "temperature": 0.2,
  "stream": false
}
```

Provider별 native request 변환은 Gateway가 담당합니다. Alpine 애플리케이션은 Provider별 API 형식을 알 필요가 없습니다.

## Android + OAuth

Android 모듈은 다음 두 실행 방식을 분리합니다.

- 서버/개발 모드: Python Gateway가 환경변수의 API key로 Provider를 직접 호출
- Android 배포 모드: Android가 OAuth/Keystore/Provider HTTPS를 담당하고 Alpine은 임시 Host Bridge session token만 사용

Android 배포 경로는 `OAuthManager → OAuthLlmSession → OAuthHttpLlmBridge → HostBridgeServer`로 구현되어 있습니다. OpenAI-compatible, Anthropic Messages, Gemini generateContent adapter와 표시용 JWT metadata adapter가 포함됩니다.

Host Bridge는 bounded concurrency, overload 429/`Retry-After`, request ID와 active request health 정보를 제공합니다.

통합 코드는 [Android 통합 가이드](android/README.md), Provider별 남은 작업은 [OAuth adapter 요구사항](docs/provider-oauth-adapters.md)을 참고하세요.

## 테스트

```bash
python3 -m unittest discover -s tests -v
./gradlew :android:testDebugUnitTest :android:assembleDebug
./gradlew :android:assembleRelease :android:publishReleasePublicationToProjectRepository
```

테스트는 외부 LLM/OAuth API를 호출하지 않고 protocol, policy, Provider request 변환, PKCE/state, callback, refresh single-flight, Host Bridge 인증과 credential 비노출을 검증합니다.

## 현재 제한

- Alpine rootfs와 PRoot 실행 바이너리는 포함하지 않습니다.
- 실제 Provider OAuth client registration/client id는 앱 개발자가 준비해야 합니다.
- Kimi device flow, xAI OIDC discovery, Gemini project onboarding과 upstream SSE streaming은 후속 범위입니다.
- 외부 Provider 실제 계정 end-to-end 검증은 credential이 없는 로컬 테스트에서 수행하지 않습니다.
