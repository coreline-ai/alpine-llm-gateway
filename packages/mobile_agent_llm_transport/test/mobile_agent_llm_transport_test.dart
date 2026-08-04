import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent_llm_transport/mobile_agent_llm_transport.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('mobile_agent_llm_transport');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('request validates a bounded provider-neutral message contract', () {
    const request = LlmStreamRequest(
      requestId: 'request_1234',
      provider: LlmProvider.openai,
      model: 'coding-model',
      messages: [LlmMessage(role: 'user', content: 'hello')],
    );

    request.validate();
    expect(request.toMap().keys, isNot(contains('accessToken')));
    expect(request.toMap().keys, isNot(contains('apiKey')));
  });

  test('request rejects unsupported roles and absent user input', () {
    const request = LlmStreamRequest(
      requestId: 'request_1234',
      provider: LlmProvider.anthropic,
      model: 'claude-model',
      messages: [LlmMessage(role: 'tool', content: 'not supported yet')],
    );

    expect(request.validate, throwsA(isA<LlmTransportException>()));
  });

  test('event parser exposes only normalized data', () {
    final event = LlmStreamEvent.fromMap(<Object?, Object?>{
      'requestId': 'request_1234',
      'type': 'delta',
      'data': <String, Object?>{'text': 'hello'},
    });

    expect(event.data, {'text': 'hello'});
    expect(event.terminal, isFalse);
  });

  test('cancel result distinguishes local stop from server acknowledgment', () {
    final result = LlmCancelResult.fromMap(<Object?, Object?>{
      'requestId': 'request_1234',
      'localCancelled': true,
      'serverAcknowledgment': 'unavailable',
    });

    expect(result.localCancelled, isTrue);
    expect(result.serverConfirmedStopped, isFalse);
    expect(result.serverAcknowledgment, LlmCancelAcknowledgment.unavailable);
  });

  test('not-active cancel acknowledgment confirms idempotent stop', () {
    final result = LlmCancelResult.fromMap(<Object?, Object?>{
      'requestId': 'request_1234',
      'localCancelled': false,
      'serverAcknowledgment': 'not_active',
    });

    expect(result.serverConfirmedStopped, isTrue);
  });

  test(
    'native cancel method returns the server acknowledgment contract',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'cancelRequest');
            return <String, Object>{
              'requestId': 'request_1234',
              'localCancelled': true,
              'serverAcknowledgment': 'accepted',
            };
          });
      final transport = NativeLlmTransport(
        bffBaseUrl: Uri.parse('https://bff.mobileagent.example'),
      );

      final result = await transport.cancel('request_1234');

      expect(result.serverAcknowledgment, LlmCancelAcknowledgment.accepted);
    },
  );

  test('native request state method parses a preparing request', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'requestState');
          return <String, Object>{
            'requestId': 'request_1234',
            'state': 'preparing',
          };
        });
    final transport = NativeLlmTransport(
      bffBaseUrl: Uri.parse('https://bff.mobileagent.example'),
    );

    final state = await transport.requestState('request_1234');

    expect(state, LlmRequestState.preparing);
  });
}
