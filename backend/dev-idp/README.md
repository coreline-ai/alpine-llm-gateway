# MobileAgent development OIDC fixture

Keycloak `26.7.0`의 고정 realm import로 Authorization Code + PKCE S256, offline refresh,
BFF audience를 로컬에서 재현합니다.

```bash
docker compose -f backend/dev-idp/compose.yaml up -d
curl http://127.0.0.1:9000/health/ready
python3 scripts/verify-dev-oidc-flow.py
```

Realm JSON은 개발 이미지를 만들 때 포함되므로 Docker/Colima host bind mount에 의존하지 않습니다.
검증 스크립트는 token 값을 출력하지 않고 실제 로그인 폼, PKCE code exchange, BFF audience,
refresh rotation, 이전 refresh replay 거부와 revoke를 확인합니다.

- Issuer: `http://127.0.0.1:8080/realms/mobileagent`
- Native client: `mobile-agent-native` (public client, secret 없음)
- Redirect: `ai.coreline.mobileagent:/oauth/callback`
- BFF audience: `mobile-agent-bff`

이 fixture는 HTTP `start-dev`이므로 server contract와 브라우저 화면 개발에만 사용합니다.
Android/iOS 실기기 완료 근거는 소유 HTTPS staging issuer에서 생성해야 합니다. 저장된 개발 계정과
admin password는 production에서 사용할 수 없습니다.
