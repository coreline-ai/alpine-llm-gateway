import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent/src/conversation_store.dart';

ConversationSnapshot _snapshot({String prompt = '한글 검색 가능한 대화'}) {
  final now = DateTime.utc(2026, 8, 4, 12);
  return ConversationSnapshot(
    records: <ConversationRecord>[
      ConversationRecord(
        id: 'conversation_20260804_1',
        provider: 'openai',
        model: 'gpt-5',
        updatedAt: now,
        messages: <ConversationMessage>[
          ConversationMessage(
            role: ConversationRole.user,
            content: prompt,
            createdAt: now,
          ),
          ConversationMessage(
            role: ConversationRole.assistant,
            content: '저장된 답변입니다.',
            createdAt: now,
          ),
        ],
      ),
    ],
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('snapshot round trip keeps Korean content and supports search', () {
    final restored = ConversationSnapshot.decode(_snapshot().encode());

    expect(restored.records, hasLength(1));
    expect(restored.records.single.messages.last.content, '저장된 답변입니다.');
    expect(restored.search('한글 검색'), hasLength(1));
    expect(restored.search('없는 내용'), isEmpty);
  });

  test('snapshot rejects unexpected and token-like fields', () {
    const payload = '''
      {"schemaVersion":1,"records":[],"accessToken":"must-not-persist"}
    ''';

    expect(
      () => ConversationSnapshot.decode(payload),
      throwsA(
        isA<ConversationStoreException>().having(
          (error) => error.code,
          'code',
          'conversation_invalid',
        ),
      ),
    );
  });

  test('snapshot rejects an oversized message before native storage', () {
    final now = DateTime.utc(2026, 8, 4, 12);
    final oversized = ConversationSnapshot(
      records: <ConversationRecord>[
        ConversationRecord(
          id: 'conversation_20260804_2',
          provider: 'xai',
          model: 'grok-4',
          updatedAt: now,
          messages: <ConversationMessage>[
            ConversationMessage(
              role: ConversationRole.user,
              content: 'a' * (conversationMaxMessageCharacters + 1),
              createdAt: now,
            ),
          ],
        ),
      ],
    );

    expect(
      oversized.encode,
      throwsA(
        isA<ConversationStoreException>().having(
          (error) => error.code,
          'code',
          'conversation_invalid',
        ),
      ),
    );
  });

  test(
    'native channel receives only schema version and snapshot payload',
    () async {
      const channel = MethodChannel('mobile_agent_conversations');
      MethodCall? captured;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            captured = call;
            return null;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );

      await NativeConversationStore(methodChannel: channel).save(_snapshot());

      expect(captured?.method, 'saveConversationSnapshot');
      final arguments = captured?.arguments as Map<Object?, Object?>;
      expect(
        arguments.keys,
        unorderedEquals(<String>['schemaVersion', 'payload']),
      );
      expect(arguments['payload'], isA<String>());
      expect(arguments.toString(), isNot(contains('accessToken')));
      expect(arguments.toString(), isNot(contains('refreshToken')));
    },
  );
}
