import pytest

from app.config import Settings


def settings(**updates) -> Settings:
    values = {
        "oidc_issuer": "https://auth.mobileagent.example/realms/mobileagent",
        "oidc_audience": "mobile-agent-bff",
        "oidc_allowed_azp": ("mobile-agent-native",),
    }
    values.update(updates)
    return Settings(**values)


def test_settings_accept_owned_https_issuer() -> None:
    value = settings()

    value.validate()


def test_settings_reject_non_https_issuer() -> None:
    value = settings(oidc_issuer="http://localhost:8080/realms/mobileagent")

    with pytest.raises(ValueError):
        value.validate()


def test_provider_models_are_fail_closed_by_default() -> None:
    value = settings()

    assert value.models_for("openai") == ()
    assert value.models_for("anthropic") == ()
    assert value.models_for("xai") == ()
