FROM quay.io/keycloak/keycloak:26.7.0

# Bake the deterministic fixture into the image instead of using a host bind
# mount. This also works when the repository lives outside Colima's mounted
# home directory.
COPY --chown=keycloak:keycloak keycloak/mobileagent-realm.json /opt/keycloak/data/import/mobileagent-realm.json
