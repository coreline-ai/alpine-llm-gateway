import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';
import 'package:mobile_agent_auth/mobile_agent_auth_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late MethodChannelMobileAgentAuth platform;
  const channel = MethodChannel('mobile_agent_auth');
  const eventChannel = EventChannel('mobile_agent_auth/events');
  final calls = <MethodCall>[];

  setUp(() {
    platform = MethodChannelMobileAgentAuth();
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'signIn') {
            return <String, Object>{
              'status': 'authenticated',
              'accountLabel': 'Mobile Agent Tester',
              'expiresAtMillis': 2000000000000,
              'canRefresh': true,
            };
          }
          if (call.method == 'restoreSession') {
            return <String, Object>{
              'status': 'signed_out',
              'canRefresh': false,
            };
          }
          if (call.method == 'getCapabilities') {
            return <String, Object>{
              'protocolVersion': mobileAgentAuthProtocolVersion,
              'authStateEvents': true,
              'secureSessionStorage': true,
              'nativeAuthorizedTransport': true,
            };
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(eventChannel, null);
  });

  test(
    'signIn sends public configuration and returns redacted summary',
    () async {
      final summary = await platform.signIn(
        MobileAgentAuthConfiguration(
          issuer: Uri.parse('https://auth.mobileagent.example'),
          clientId: 'mobile-agent-native',
          redirectUri: Uri.parse('ai.coreline.mobileagent:/oauth/callback'),
        ),
      );

      expect(summary.isAuthenticated, isTrue);
      expect(summary.accountLabel, 'Mobile Agent Tester');
      expect(summary.canRefresh, isTrue);
      expect(calls.single.method, 'signIn');
      final arguments = calls.single.arguments as Map<Object?, Object?>;
      expect(arguments.keys, isNot(contains('accessToken')));
      expect(arguments.keys, isNot(contains('refreshToken')));
      expect(arguments.keys, isNot(contains('clientSecret')));
    },
  );

  test('getCapabilities validates the native protocol contract', () async {
    final capabilities = await platform.capabilities();

    expect(capabilities.protocolVersion, mobileAgentAuthProtocolVersion);
    expect(capabilities.authStateEvents, isTrue);
    expect(calls.single.method, 'getCapabilities');
  });

  test('auth event channel emits a typed redacted session event', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          eventChannel,
          MockStreamHandler.inline(
            onListen: (arguments, events) {
              events.success(<String, Object>{
                'protocolVersion': mobileAgentAuthProtocolVersion,
                'reason': 'signed_in',
                'session': <String, Object>{
                  'status': 'authenticated',
                  'accountLabel': 'Tester',
                  'canRefresh': true,
                },
              });
            },
          ),
        );

    final event = await platform.authStateStream().first;

    expect(event.reason, AuthEventReason.signedIn);
    expect(event.session.accountLabel, 'Tester');
  });

  test(
    'auth event stream safely resubscribes after the last listener leaves',
    () async {
      var listenCount = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockStreamHandler(
            eventChannel,
            MockStreamHandler.inline(
              onListen: (arguments, events) {
                listenCount += 1;
                events.success(<String, Object>{
                  'protocolVersion': mobileAgentAuthProtocolVersion,
                  'reason': 'restored',
                  'session': <String, Object>{
                    'status': 'signed_out',
                    'canRefresh': false,
                  },
                });
              },
            ),
          );

      await platform.authStateStream().first;
      await platform.authStateStream().first;

      expect(listenCount, 2);
    },
  );

  test(
    'restoreSession returns signed out without exposing credentials',
    () async {
      final summary = await platform.restoreSession();

      expect(summary.status, AuthSessionStatus.signedOut);
      expect(summary.accountLabel, isNull);
    },
  );

  test('signOut sends only the public BFF revocation URL', () async {
    await platform.signOut(Uri.parse('https://bff.mobileagent.example'));

    expect(calls.single.method, 'signOut');
    expect(calls.single.arguments, <String, Object>{
      'bffBaseUrl': 'https://bff.mobileagent.example',
    });
  });
}
