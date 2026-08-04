import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent/src/auth_controller.dart';
import 'package:mobile_agent/src/mobile_agent_app.dart';
import 'package:mobile_agent/src/mobile_agent_environment.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';
import 'package:mobile_agent_auth/mobile_agent_auth_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class FakeAuthPlatform
    with MockPlatformInterfaceMixin
    implements MobileAgentAuthPlatform {
  bool signedIn = false;
  final events = StreamController<AuthEvent>.broadcast();

  @override
  Stream<AuthEvent> authStateStream() => events.stream;

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
  Future<AuthSessionSummary> restoreSession() async => signedIn
      ? const AuthSessionSummary(
          status: AuthSessionStatus.authenticated,
          accountLabel: 'MobileAgent Tester',
          canRefresh: true,
        )
      : const AuthSessionSummary.signedOut();

  @override
  Future<void> signOut(Uri? bffBaseUrl) async => signedIn = false;

  @override
  Future<AuthSessionSummary> signIn(
    MobileAgentAuthConfiguration configuration,
  ) async {
    signedIn = true;
    return restoreSession();
  }
}

void main() {
  const conversationChannel = MethodChannel('mobile_agent_conversations');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(conversationChannel, (call) async {
          if (call.method == 'loadConversationSnapshot') return null;
          if (call.method == 'saveConversationSnapshot' ||
              call.method == 'clearConversationSnapshot') {
            return null;
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(conversationChannel, null);
  });

  testWidgets('unconfigured app explains why OAuth button is disabled', (
    tester,
  ) async {
    MobileAgentAuthPlatform.instance = FakeAuthPlatform();

    await tester.pumpWidget(const MobileAgentApp());
    await tester.pumpAndSettle();

    expect(find.text('MOBILEAGENT'), findsOneWidget);
    expect(find.text('OAuth 로그인 페이지 열기'), findsOneWidget);
    expect(find.byKey(const Key('oauth_configuration_notice')), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('oauth_sign_in_button')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets('configured OAuth flow reaches authenticated UI', (tester) async {
    final fake = FakeAuthPlatform();
    MobileAgentAuthPlatform.instance = fake;
    const environment = MobileAgentEnvironment(
      issuer: 'https://auth.mobileagent.example',
      clientId: 'mobile-agent-native',
      redirectUri: 'ai.coreline.mobileagent:/oauth/callback',
      audience: 'mobile-agent-bff',
      bffBaseUrl: 'https://api.mobileagent.example',
      openAiModel: 'coding-model',
      anthropicModel: 'claude-model',
      xaiModel: 'grok-model',
    );
    final controller = AuthController(const MobileAgentAuth(), environment);
    await controller.restore();

    await tester.pumpWidget(
      MaterialApp(
        home: OAuthLandingScreen(
          controller: controller,
          environment: environment,
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('oauth_sign_in_button')));
    await tester.pumpAndSettle();

    expect(find.text('SESSION ACTIVE'), findsOneWidget);
    expect(find.text('MobileAgent Tester'), findsOneWidget);
    expect(find.byKey(const Key('sign_out_button')), findsOneWidget);
    await fake.events.close();
  });

  testWidgets('reauthentication event hides workspace and offers login again', (
    tester,
  ) async {
    final fake = FakeAuthPlatform()..signedIn = true;
    MobileAgentAuthPlatform.instance = fake;
    const environment = MobileAgentEnvironment(
      issuer: 'https://auth.mobileagent.example',
      clientId: 'mobile-agent-native',
      redirectUri: 'ai.coreline.mobileagent:/oauth/callback',
      audience: 'mobile-agent-bff',
      bffBaseUrl: 'https://api.mobileagent.example',
      openAiModel: 'coding-model',
      anthropicModel: 'claude-model',
      xaiModel: 'grok-model',
    );
    final controller = AuthController(const MobileAgentAuth(), environment);
    await controller.restore();
    await tester.pumpWidget(
      MaterialApp(
        home: OAuthLandingScreen(
          controller: controller,
          environment: environment,
        ),
      ),
    );

    fake.events.add(
      const AuthEvent(
        reason: AuthEventReason.reauthenticationRequired,
        session: AuthSessionSummary(
          status: AuthSessionStatus.reauthenticationRequired,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('SESSION EXPIRED'), findsOneWidget);
    expect(find.byKey(const Key('llm_workspace')), findsNothing);
    expect(find.byKey(const Key('oauth_sign_in_button')), findsOneWidget);
    controller.dispose();
    await fake.events.close();
  });

  testWidgets('OAuth login and Stop render at 200 percent text scale', (
    tester,
  ) async {
    final fake = FakeAuthPlatform()..signedIn = true;
    MobileAgentAuthPlatform.instance = fake;
    const transportMethods = MethodChannel('mobile_agent_llm_transport');
    const transportEvents = EventChannel('mobile_agent_llm_transport/events');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(transportMethods, (call) async {
          if (call.method == 'startStream') return null;
          if (call.method == 'cancelRequest') {
            return <String, Object>{
              'requestId':
                  (call.arguments as Map<Object?, Object?>)['requestId']!
                      as String,
              'localCancelled': true,
              'serverAcknowledgment': 'accepted',
            };
          }
          if (call.method == 'requestState') {
            return <String, Object>{'state': 'streaming'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          transportEvents,
          MockStreamHandler.inline(onListen: (arguments, events) {}),
        );
    const environment = MobileAgentEnvironment(
      issuer: 'https://auth.mobileagent.example',
      clientId: 'mobile-agent-native',
      redirectUri: 'ai.coreline.mobileagent:/oauth/callback',
      audience: 'mobile-agent-bff',
      bffBaseUrl: 'https://api.mobileagent.example',
      openAiModel: 'coding-model',
      anthropicModel: 'claude-model',
      xaiModel: 'grok-model',
    );
    final controller = AuthController(const MobileAgentAuth(), environment);
    await controller.restore();
    await tester.binding.setSurfaceSize(const Size(400, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(2)),
          child: child!,
        ),
        home: OAuthLandingScreen(
          controller: controller,
          environment: environment,
        ),
      ),
    );
    await tester.enterText(find.byKey(const Key('prompt_input')), 'hello');
    await tester.ensureVisible(find.byKey(const Key('send_llm_button')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('send_llm_button')));
    await tester.pump();

    expect(find.byKey(const Key('stop_llm_button')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
    await fake.events.close();
  });
}
