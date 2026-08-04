# mobile_agent_auth

MobileAgent의 Android/iOS OIDC Authorization Code + PKCE Flutter plugin입니다.

| 플랫폼 | OAuth | 저장소 | authorized transport |
|---|---|---|---|
| Android | AppAuth-Android + system Custom Tab | Android Keystore AES/GCM | native HTTPS/SSE |
| iOS | AppAuth-iOS + ASWebAuthenticationSession | Keychain `WhenUnlockedThisDeviceOnly` | URLSession SSE |

Dart에는 `AuthSessionSummary`만 반환합니다. Raw access/refresh/ID token, client secret과 Provider
credential은 MethodChannel을 통과하지 않습니다. issuer와 BFF는 HTTPS만 허용하고 기본 custom
callback은 정확히 `ai.coreline.mobileagent:/oauth/callback`만 허용합니다.

`capabilities()`는 native protocol version과 secure storage/authorized transport 지원을 확인하며,
호환되지 않는 version은 fail-closed 처리합니다. `authStateStream()`은 `restored`, `signedIn`,
`signedOut`, `reauthenticationRequired` 상태만 redacted `AuthSessionSummary`와 함께 전달합니다.

```dart
const auth = MobileAgentAuth();
final session = await auth.signIn(
  MobileAgentAuthConfiguration(
    issuer: Uri.parse('https://auth.example.com/realms/mobileagent'),
    clientId: 'mobile-agent-native',
    redirectUri: Uri.parse('ai.coreline.mobileagent:/oauth/callback'),
    audience: 'mobile-agent-bff',
  ),
);

final capabilities = await auth.capabilities();
final authEvents = auth.authStateStream();
```

Android/iOS native transport는 하나의 in-memory AppAuth state를 공유해 만료 시 concurrent refresh가
single-flight로 합쳐지도록 하고, 갱신된 state를 native secure store에 다시 저장합니다.
`signOut(bffBaseUrl:)`은 local credential을 먼저 제거한 뒤 BFF access-session revoke와 issuer가
광고한 same-host HTTPS RFC 7009 refresh-token revocation을 native에서 best-effort 수행합니다.

```bash
flutter analyze
flutter test
```
