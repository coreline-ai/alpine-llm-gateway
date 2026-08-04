import 'mobile_agent_auth_platform_interface.dart';

enum AuthSessionStatus { signedOut, authenticated, reauthenticationRequired }

enum AuthEventReason { restored, signedIn, signedOut, reauthenticationRequired }

const int mobileAgentAuthProtocolVersion = 1;

final class MobileAgentAuthCapabilities {
  const MobileAgentAuthCapabilities({
    required this.protocolVersion,
    required this.authStateEvents,
    required this.secureSessionStorage,
    required this.nativeAuthorizedTransport,
  });

  final int protocolVersion;
  final bool authStateEvents;
  final bool secureSessionStorage;
  final bool nativeAuthorizedTransport;

  factory MobileAgentAuthCapabilities.fromMap(Map<Object?, Object?> value) {
    final protocolVersion = value['protocolVersion'];
    if (protocolVersion is! int ||
        value['authStateEvents'] is! bool ||
        value['secureSessionStorage'] is! bool ||
        value['nativeAuthorizedTransport'] is! bool) {
      throw const MobileAgentAuthException(
        code: 'protocol_invalid',
        message: '인증 플러그인 계약을 확인할 수 없습니다.',
      );
    }
    if (protocolVersion != mobileAgentAuthProtocolVersion) {
      throw const MobileAgentAuthException(
        code: 'protocol_version_mismatch',
        message: '앱과 인증 플러그인 버전이 호환되지 않습니다.',
      );
    }
    return MobileAgentAuthCapabilities(
      protocolVersion: protocolVersion,
      authStateEvents: value['authStateEvents'] as bool,
      secureSessionStorage: value['secureSessionStorage'] as bool,
      nativeAuthorizedTransport: value['nativeAuthorizedTransport'] as bool,
    );
  }
}

final class MobileAgentAuthConfiguration {
  MobileAgentAuthConfiguration({
    required this.issuer,
    required this.clientId,
    required this.redirectUri,
    this.scopes = const <String>['openid', 'profile', 'offline_access'],
    this.audience,
  });

  final Uri issuer;
  final String clientId;
  final Uri redirectUri;
  final List<String> scopes;
  final String? audience;

  void validate() {
    if (issuer.scheme != 'https' ||
        issuer.host.isEmpty ||
        issuer.userInfo.isNotEmpty ||
        issuer.hasQuery ||
        issuer.hasFragment) {
      throw const MobileAgentAuthException(
        code: 'configuration_invalid',
        message: 'OIDC issuer must be a clean HTTPS URL.',
      );
    }
    if (!RegExp(r'^[A-Za-z0-9._~-]{3,200}$').hasMatch(clientId)) {
      throw const MobileAgentAuthException(
        code: 'configuration_invalid',
        message: 'OIDC public client ID is invalid.',
      );
    }
    final scheme = redirectUri.scheme.toLowerCase();
    final allowedRedirect =
        (scheme == 'https' && redirectUri.host.isNotEmpty) ||
        redirectUri.toString() == 'ai.coreline.mobileagent:/oauth/callback';
    if (!allowedRedirect || redirectUri.userInfo.isNotEmpty) {
      throw const MobileAgentAuthException(
        code: 'configuration_invalid',
        message: 'Redirect URI must use the MobileAgent scheme or HTTPS.',
      );
    }
    if (!scopes.contains('openid') ||
        scopes.isEmpty ||
        scopes.any(
          (scope) => !RegExp(r'^[A-Za-z0-9:._~-]+$').hasMatch(scope),
        )) {
      throw const MobileAgentAuthException(
        code: 'configuration_invalid',
        message: 'OIDC scopes must include openid and contain safe values.',
      );
    }
  }

  Map<String, Object> toMap() => <String, Object>{
    'issuer': issuer.toString(),
    'clientId': clientId,
    'redirectUri': redirectUri.toString(),
    'scopes': List<String>.unmodifiable(scopes),
    if (audience?.trim().isNotEmpty ?? false) 'audience': audience!.trim(),
  };
}

final class AuthSessionSummary {
  const AuthSessionSummary({
    required this.status,
    this.accountLabel,
    this.expiresAt,
    this.canRefresh = false,
  });

  const AuthSessionSummary.signedOut()
    : this(status: AuthSessionStatus.signedOut);

  final AuthSessionStatus status;
  final String? accountLabel;
  final DateTime? expiresAt;
  final bool canRefresh;

  bool get isAuthenticated => status == AuthSessionStatus.authenticated;

  factory AuthSessionSummary.fromMap(Map<Object?, Object?> value) {
    final status = switch (value['status']) {
      'authenticated' => AuthSessionStatus.authenticated,
      'reauthentication_required' => AuthSessionStatus.reauthenticationRequired,
      _ => AuthSessionStatus.signedOut,
    };
    final expiresAtMillis = value['expiresAtMillis'];
    return AuthSessionSummary(
      status: status,
      accountLabel: (value['accountLabel'] as String?)?.trim().isEmpty ?? true
          ? null
          : value['accountLabel'] as String?,
      expiresAt: expiresAtMillis is int
          ? DateTime.fromMillisecondsSinceEpoch(expiresAtMillis, isUtc: true)
          : null,
      canRefresh: value['canRefresh'] == true,
    );
  }
}

final class AuthEvent {
  const AuthEvent({required this.reason, required this.session});

  final AuthEventReason reason;
  final AuthSessionSummary session;

  factory AuthEvent.fromMap(Map<Object?, Object?> value) {
    final protocolVersion = value['protocolVersion'];
    final rawSession = value['session'];
    final reason = switch (value['reason']) {
      'restored' => AuthEventReason.restored,
      'signed_in' => AuthEventReason.signedIn,
      'signed_out' => AuthEventReason.signedOut,
      'reauthentication_required' => AuthEventReason.reauthenticationRequired,
      _ => null,
    };
    if (protocolVersion != mobileAgentAuthProtocolVersion ||
        rawSession is! Map ||
        reason == null) {
      throw const MobileAgentAuthException(
        code: 'auth_event_invalid',
        message: '인증 상태 변경 형식이 올바르지 않습니다.',
      );
    }
    return AuthEvent(
      reason: reason,
      session: AuthSessionSummary.fromMap(rawSession),
    );
  }
}

final class MobileAgentAuthException implements Exception {
  const MobileAgentAuthException({required this.code, required this.message});

  final String code;
  final String message;

  @override
  String toString() => 'MobileAgentAuthException($code): $message';
}

final class MobileAgentAuth {
  const MobileAgentAuth();

  Future<MobileAgentAuthCapabilities> capabilities() =>
      MobileAgentAuthPlatform.instance.capabilities();

  Stream<AuthEvent> authStateStream() =>
      MobileAgentAuthPlatform.instance.authStateStream();

  Future<AuthSessionSummary> signIn(
    MobileAgentAuthConfiguration configuration,
  ) {
    configuration.validate();
    return MobileAgentAuthPlatform.instance.signIn(configuration);
  }

  Future<AuthSessionSummary> restoreSession() =>
      MobileAgentAuthPlatform.instance.restoreSession();

  Future<void> cancelSignIn() =>
      MobileAgentAuthPlatform.instance.cancelSignIn();

  Future<void> signOut({Uri? bffBaseUrl}) {
    if (bffBaseUrl != null &&
        (bffBaseUrl.scheme != 'https' ||
            bffBaseUrl.host.isEmpty ||
            bffBaseUrl.userInfo.isNotEmpty ||
            bffBaseUrl.hasQuery ||
            bffBaseUrl.hasFragment)) {
      throw const MobileAgentAuthException(
        code: 'configuration_invalid',
        message: 'BFF URL must be a clean HTTPS URL.',
      );
    }
    return MobileAgentAuthPlatform.instance.signOut(bffBaseUrl);
  }
}
