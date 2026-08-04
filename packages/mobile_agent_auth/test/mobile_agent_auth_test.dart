import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';
import 'package:mobile_agent_auth/mobile_agent_auth_method_channel.dart';
import 'package:mobile_agent_auth/mobile_agent_auth_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class FakeMobileAgentAuthPlatform
    with MockPlatformInterfaceMixin
    implements MobileAgentAuthPlatform {
  @override
  Stream<AuthEvent> authStateStream() => const Stream<AuthEvent>.empty();

  @override
  Future<MobileAgentAuthCapabilities> capabilities() async =>
      const MobileAgentAuthCapabilities(
        protocolVersion: mobileAgentAuthProtocolVersion,
        authStateEvents: true,
        secureSessionStorage: true,
        nativeAuthorizedTransport: true,
      );

  @override
  Future<void> cancelSignIn() async {}

  @override
  Future<AuthSessionSummary> restoreSession() async =>
      const AuthSessionSummary.signedOut();

  @override
  Future<void> signOut(Uri? bffBaseUrl) async {}

  @override
  Future<AuthSessionSummary> signIn(
    MobileAgentAuthConfiguration configuration,
  ) async => const AuthSessionSummary(
    status: AuthSessionStatus.authenticated,
    accountLabel: 'Tester',
    canRefresh: true,
  );
}

void main() {
  test('$MethodChannelMobileAgentAuth is the default instance', () {
    expect(
      MobileAgentAuthPlatform.instance,
      isInstanceOf<MethodChannelMobileAgentAuth>(),
    );
  });

  test('configuration rejects non HTTPS issuer', () {
    final configuration = MobileAgentAuthConfiguration(
      issuer: Uri.parse('http://auth.mobileagent.example'),
      clientId: 'mobile-agent-native',
      redirectUri: Uri.parse('ai.coreline.mobileagent:/oauth/callback'),
    );

    expect(configuration.validate, throwsA(isA<MobileAgentAuthException>()));
  });

  test('configuration rejects a foreign custom redirect scheme', () {
    final configuration = MobileAgentAuthConfiguration(
      issuer: Uri.parse('https://auth.mobileagent.example'),
      clientId: 'mobile-agent-native',
      redirectUri: Uri.parse('other.app:/oauth/callback'),
    );

    expect(configuration.validate, throwsA(isA<MobileAgentAuthException>()));
  });

  test('configuration rejects an unregistered MobileAgent callback path', () {
    final configuration = MobileAgentAuthConfiguration(
      issuer: Uri.parse('https://auth.mobileagent.example'),
      clientId: 'mobile-agent-native',
      redirectUri: Uri.parse('ai.coreline.mobileagent:/unexpected'),
    );

    expect(configuration.validate, throwsA(isA<MobileAgentAuthException>()));
  });

  test('public API returns only a session summary', () async {
    MobileAgentAuthPlatform.instance = FakeMobileAgentAuthPlatform();
    const auth = MobileAgentAuth();
    final summary = await auth.signIn(
      MobileAgentAuthConfiguration(
        issuer: Uri.parse('https://auth.mobileagent.example'),
        clientId: 'mobile-agent-native',
        redirectUri: Uri.parse('ai.coreline.mobileagent:/oauth/callback'),
      ),
    );

    expect(summary.accountLabel, 'Tester');
    expect(summary.canRefresh, isTrue);
  });

  test('capability negotiation rejects an incompatible native protocol', () {
    expect(
      () => MobileAgentAuthCapabilities.fromMap(<Object?, Object?>{
        'protocolVersion': mobileAgentAuthProtocolVersion + 1,
        'authStateEvents': true,
        'secureSessionStorage': true,
        'nativeAuthorizedTransport': true,
      }),
      throwsA(
        isA<MobileAgentAuthException>().having(
          (error) => error.code,
          'code',
          'protocol_version_mismatch',
        ),
      ),
    );
  });

  test('auth event parses a redacted reauthentication state', () {
    final event = AuthEvent.fromMap(<Object?, Object?>{
      'protocolVersion': mobileAgentAuthProtocolVersion,
      'reason': 'reauthentication_required',
      'session': <Object?, Object?>{
        'status': 'reauthentication_required',
        'canRefresh': false,
      },
    });

    expect(event.reason, AuthEventReason.reauthenticationRequired);
    expect(event.session.status, AuthSessionStatus.reauthenticationRequired);
  });

  test('signOut rejects a non HTTPS BFF revocation endpoint', () {
    expect(
      () => const MobileAgentAuth().signOut(
        bffBaseUrl: Uri.parse('http://bff.mobileagent.example'),
      ),
      throwsA(isA<MobileAgentAuthException>()),
    );
  });
}
