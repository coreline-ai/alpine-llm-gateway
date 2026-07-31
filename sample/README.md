# Android sample

이 샘플은 Provider credential을 소스나 `BuildConfig`에 포함하지 않고 런타임 입력으로 OAuth/streaming 통합 경계를 보여준다.

```bash
./gradlew :sample:assembleDebug
```

설치 후 Provider console에서 발급한 public client ID, authorization/token endpoint, scope, completion endpoint와 model을 직접 입력한다.

- Claude completion endpoint: Messages API HTTPS endpoint
- Gemini completion endpoint: `{model}`을 포함한 `:generateContent` HTTPS template
- Redirect URI: `http://127.0.0.1:54545/oauth/callback`

샘플은 완성형 제품 UI나 credential 배포 방법을 제공하지 않는다. 실제 앱에서는 endpoint/client ID를 검증된 remote config 또는 빌드 환경에서 공급하고 민감정보를 로그에 기록하지 않아야 한다.
