.PHONY: mobile-analyze mobile-test mobile-build-debug oauth-release-scan bff-bootstrap bff-test dev-up dev-down dev-oidc-test

BFF_VENV ?= backend/mobile_agent_bff/.venv

mobile-analyze:
	cd packages/mobile_agent_auth && flutter analyze
	cd packages/mobile_agent_llm_transport && flutter analyze
	cd apps/mobile_agent && flutter analyze

mobile-test:
	cd packages/mobile_agent_auth && flutter test
	cd packages/mobile_agent_llm_transport && flutter test
	cd apps/mobile_agent && flutter test

mobile-build-debug:
	cd apps/mobile_agent && flutter build apk --debug
	cd apps/mobile_agent && flutter build ios --simulator --debug

oauth-release-scan:
	python3 scripts/verify-mobile-oauth-release.py --require-default-roots

bff-bootstrap:
	python3.11 -m venv $(BFF_VENV)
	$(BFF_VENV)/bin/pip install -e 'backend/mobile_agent_bff[test]'

bff-test:
	$(BFF_VENV)/bin/pytest -q backend/mobile_agent_bff

dev-up:
	docker compose -f backend/dev-idp/compose.yaml up -d --build

dev-down:
	docker compose -f backend/dev-idp/compose.yaml down

dev-oidc-test:
	python3 scripts/verify-dev-oidc-flow.py
