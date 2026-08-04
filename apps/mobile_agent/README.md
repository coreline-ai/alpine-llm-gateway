# MobileAgent Flutter app

Android/iOS 공통 OAuth 로그인, Provider 선택, native SSE Run Card와 Stop을 제공하는 제품 앱입니다.
사용자는 API key를 입력하지 않습니다. MobileAgent OIDC로 로그인하고 OpenAI·Anthropic·xAI
credential은 LLM BFF가 server-side에서만 사용합니다.

## 실행 설정

소유 HTTPS staging issuer/BFF와 public native client를 준비한 뒤 실행합니다.

```bash
flutter run \
  --dart-define=OIDC_ISSUER=https://auth.staging.example/realms/mobileagent \
  --dart-define=OIDC_CLIENT_ID=mobile-agent-native \
  --dart-define=OIDC_REDIRECT_URI=ai.coreline.mobileagent:/oauth/callback \
  --dart-define=OIDC_AUDIENCE=mobile-agent-bff \
  --dart-define=BFF_BASE_URL=https://llm.staging.example \
  --dart-define=OPENAI_MODEL=approved-openai-model \
  --dart-define=ANTHROPIC_MODEL=approved-anthropic-model \
  --dart-define=XAI_MODEL=approved-xai-model
```

- issuer/BFF는 HTTPS가 아니면 fail-closed입니다.
- `OIDC_CLIENT_ID`는 MobileAgent가 소유한 public native client여야 하며 client secret을 넣지 않습니다.
- Codex/Claude/Grok UI는 각각 OpenAI Responses/Anthropic Messages/xAI inference BFF adapter입니다.
- ChatGPT·Claude·Grok consumer 구독 OAuth 또는 다른 CLI의 client ID를 사용하지 않습니다.
- native auth protocol version이 맞지 않으면 로그인 전에 fail-closed 처리합니다.
- refresh 실패는 별도 `다시 로그인 필요` 화면으로 전환됩니다.
- Stop은 local native 실행을 즉시 중단하고 BFF acknowledgment를 별도로 표시합니다.
- 앱 resume에서 native request가 사라졌으면 자동 재전송하지 않고 중단 상태로 정리합니다.

## 기기 로컬 대화 보관

- 최근 20개 대화(대화당 최대 64개 메시지)는 **이 기기에서만** 암호화해 복구·검색·개별/전체 삭제할 수 있습니다.
- Android는 Keystore AES/GCM + no-backup app-private file, iOS는 Keychain device-only key + CryptoKit AES-GCM + file protection을 사용합니다.
- local delete는 이 기기의 암호화 사본만 지웁니다. BFF·Provider·IdP의 prompt, account 또는 retention 데이터는 삭제하지 않습니다.
- access/refresh/ID token, Authorization header, Provider API key, raw upstream body와 attachment/tool result는 대화 vault에 저장하지 않습니다.
- 최대 크기 또는 암호화 저장소 오류는 기존 vault를 덮어쓰지 않고 안정된 로컬 오류로 표시합니다.

## 검증

```bash
flutter analyze
flutter test
flutter build apk --debug
flutter build ios --simulator --debug
python3 ../../scripts/verify-mobile-oauth-release.py \
  build/app/outputs/flutter-apk/app-debug.apk \
  build/ios/iphonesimulator/Runner.app
```

로컬 Keycloak HTTP fixture는 browser/PKCE server contract 검증용입니다. 앱의 실제 OAuth E2E에는
기기가 접근할 수 있는 MobileAgent 소유 HTTPS issuer를 사용해야 합니다.
