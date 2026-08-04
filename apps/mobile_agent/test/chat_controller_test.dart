import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_agent/src/chat_controller.dart';
import 'package:mobile_agent/src/conversation_store.dart';
import 'package:mobile_agent_llm_transport/mobile_agent_llm_transport.dart';

final class FakeLlmTransport implements LlmTransport {
  final events = StreamController<LlmStreamEvent>.broadcast();
  LlmCancelAcknowledgment acknowledgment = LlmCancelAcknowledgment.accepted;
  LlmRequestState nativeState = LlmRequestState.streaming;
  bool localCancelled = true;
  LlmStreamRequest? request;

  @override
  Future<LlmCancelResult> cancel(String requestId) async => LlmCancelResult(
    requestId: requestId,
    localCancelled: localCancelled,
    serverAcknowledgment: acknowledgment,
  );

  @override
  Future<LlmRequestState> requestState(String requestId) async => nativeState;

  @override
  Future<Stream<LlmStreamEvent>> start(LlmStreamRequest request) async {
    this.request = request;
    return events.stream;
  }
}

final class ToggleConversationStore implements ConversationStore {
  ToggleConversationStore(this.snapshot);

  ConversationSnapshot snapshot;
  bool failWrites = false;

  @override
  Future<void> clear() async {
    if (failWrites) throw _storageFailure();
    snapshot = const ConversationSnapshot();
  }

  @override
  Future<ConversationSnapshot> load() async => snapshot;

  @override
  Future<void> save(ConversationSnapshot next) async {
    if (failWrites) throw _storageFailure();
    snapshot = next;
  }
}

ConversationStoreException _storageFailure() =>
    const ConversationStoreException(
      code: 'conversation_storage_failure',
      message: '암호화 대화 저장소를 사용할 수 없습니다.',
    );

ConversationSnapshot _seededSnapshot() {
  final now = DateTime.utc(2026, 8, 4, 12);
  return ConversationSnapshot(
    records: <ConversationRecord>[
      ConversationRecord(
        id: 'conversation_20260804_3',
        provider: 'anthropic',
        model: 'claude-4',
        updatedAt: now,
        messages: <ConversationMessage>[
          ConversationMessage(
            role: ConversationRole.user,
            content: '삼성폰에서 복구할 한글 대화',
            createdAt: now,
          ),
          ConversationMessage(
            role: ConversationRole.assistant,
            content: '복구된 답변',
            createdAt: now,
          ),
        ],
      ),
    ],
  );
}

void main() {
  test('stop records an accepted server cancellation', () async {
    final transport = FakeLlmTransport();
    final controller = ChatController(transport);
    await controller.send(
      provider: LlmProvider.openai,
      model: 'coding-model',
      prompt: 'hello',
    );

    await controller.stop();

    expect(controller.state, ChatRunState.cancelled);
    expect(controller.lastCancelResult?.serverConfirmedStopped, isTrue);
    expect(controller.statusMessage, contains('서버'));
    controller.dispose();
    await transport.events.close();
  });

  test(
    'stop does not report server success when acknowledgment is unavailable',
    () async {
      final transport = FakeLlmTransport()
        ..acknowledgment = LlmCancelAcknowledgment.unavailable;
      final controller = ChatController(transport);
      await controller.send(
        provider: LlmProvider.anthropic,
        model: 'claude-model',
        prompt: 'hello',
      );

      await controller.stop();

      expect(controller.state, ChatRunState.cancelled);
      expect(controller.lastCancelResult?.serverConfirmedStopped, isFalse);
      expect(controller.statusMessage, contains('실패'));
      controller.dispose();
      await transport.events.close();
    },
  );

  test(
    'resume never resends and marks a missing native request as interrupted',
    () async {
      final transport = FakeLlmTransport()
        ..nativeState = LlmRequestState.notFound;
      final controller = ChatController(transport);
      await controller.send(
        provider: LlmProvider.xai,
        model: 'grok-model',
        prompt: 'hello',
      );

      await controller.reconcileAfterResume();

      expect(controller.state, ChatRunState.failure);
      expect(controller.errorMessage, contains('자동 재전송하지 않습니다'));
      controller.dispose();
      await transport.events.close();
    },
  );

  test(
    'already inactive server request is idempotent cancellation success',
    () async {
      final transport = FakeLlmTransport()
        ..localCancelled = false
        ..acknowledgment = LlmCancelAcknowledgment.notActive;
      final controller = ChatController(transport);
      await controller.send(
        provider: LlmProvider.openai,
        model: 'coding-model',
        prompt: 'hello',
      );

      await controller.stop();

      expect(controller.state, ChatRunState.cancelled);
      expect(controller.lastCancelResult?.serverConfirmedStopped, isTrue);
      controller.dispose();
      await transport.events.close();
    },
  );

  test('restores, searches, deletes, and clears local conversations', () async {
    final transport = FakeLlmTransport();
    final store = ToggleConversationStore(_seededSnapshot());
    final controller = ChatController(transport, conversationStore: store);

    await controller.initialize();
    expect(controller.activeConversation?.title, contains('삼성폰'));
    expect(controller.searchConversations('복구할 한글'), hasLength(1));

    await controller.deleteConversation('conversation_20260804_3');
    expect(controller.conversations, isEmpty);

    await controller.send(
      provider: LlmProvider.openai,
      model: 'coding-model',
      prompt: '새로운 대화',
    );
    expect(controller.conversations, hasLength(1));
    await controller.stop();
    await controller.clearAllConversations();
    expect(controller.conversations, isEmpty);
    controller.dispose();
    await transport.events.close();
  });

  test('completed response persists full chat history for a restart', () async {
    final transport = FakeLlmTransport();
    final store = InMemoryConversationStore();
    final controller = ChatController(transport, conversationStore: store);

    await controller.send(
      provider: LlmProvider.openai,
      model: 'coding-model',
      prompt: '이전 대화도 전달해줘',
    );
    final requestId = controller.activeRequestId!;
    transport.events
      ..add(
        LlmStreamEvent(
          requestId: requestId,
          type: 'delta',
          data: const <String, Object?>{'text': '저장할 응답'},
        ),
      )
      ..add(
        LlmStreamEvent(
          requestId: requestId,
          type: 'done',
          data: const <String, Object?>{},
        ),
      );
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);

    final restored = ChatController(transport, conversationStore: store);
    await restored.initialize();
    expect(restored.activeConversation?.messages, hasLength(2));
    expect(restored.activeConversation?.messages.last.content, '저장할 응답');
    expect(transport.request?.messages, hasLength(1));
    controller.dispose();
    restored.dispose();
    await transport.events.close();
  });

  test(
    'failed deletion preserves the current in-memory conversation',
    () async {
      final transport = FakeLlmTransport();
      final store = ToggleConversationStore(_seededSnapshot())
        ..failWrites = true;
      final controller = ChatController(transport, conversationStore: store);

      await controller.initialize();
      await controller.deleteConversation('conversation_20260804_3');

      expect(controller.conversations, hasLength(1));
      expect(controller.conversationErrorMessage, contains('저장소'));
      controller.dispose();
      await transport.events.close();
    },
  );

  test(
    'failed save keeps the active chat in memory and surfaces storage error',
    () async {
      final transport = FakeLlmTransport();
      final store = ToggleConversationStore(const ConversationSnapshot())
        ..failWrites = true;
      final controller = ChatController(transport, conversationStore: store);

      await controller.send(
        provider: LlmProvider.xai,
        model: 'grok-model',
        prompt: '저장소 오류에서도 실행을 이어간다',
      );

      expect(controller.conversations, hasLength(1));
      expect(
        controller.activeConversation?.messages.single.content,
        contains('저장소 오류'),
      );
      expect(controller.conversationErrorMessage, contains('저장소'));
      controller.dispose();
      await transport.events.close();
    },
  );
}
