import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:mobile_agent_llm_transport/mobile_agent_llm_transport.dart';

import 'conversation_store.dart';

enum ChatRunState { idle, streaming, cancelling, completed, cancelled, failure }

final class ChatController extends ChangeNotifier {
  ChatController(this._transport, {ConversationStore? conversationStore})
    : _conversationStore = conversationStore ?? InMemoryConversationStore();

  final LlmTransport _transport;
  final ConversationStore _conversationStore;
  StreamSubscription<LlmStreamEvent>? _subscription;
  Future<void>? _initializing;
  int _sequence = 0;
  String? _runConversationId;
  final Set<String> _finalizedRequestIds = <String>{};

  ChatRunState state = ChatRunState.idle;
  String responseText = '';
  String? errorMessage;
  String? statusMessage;
  String? conversationErrorMessage;
  String? activeRequestId;
  String? activeConversationId;
  bool conversationsRestoring = true;
  LlmCancelResult? lastCancelResult;
  Map<String, Object?> usage = const {};
  ConversationSnapshot _snapshot = const ConversationSnapshot();

  List<ConversationRecord> get conversations => _snapshot.records;

  ConversationRecord? get activeConversation {
    final id = activeConversationId;
    if (id == null) return null;
    for (final conversation in _snapshot.records) {
      if (conversation.id == id) return conversation;
    }
    return null;
  }

  List<ConversationRecord> searchConversations(String query) =>
      _snapshot.search(query);

  Future<void> initialize() => _initializing ??= _restoreConversations();

  Future<void> _restoreConversations() async {
    conversationsRestoring = true;
    conversationErrorMessage = null;
    notifyListeners();
    try {
      _snapshot = await _conversationStore.load();
      activeConversationId = _snapshot.records.isEmpty
          ? null
          : _snapshot.records.first.id;
    } on ConversationStoreException catch (error) {
      conversationErrorMessage = error.message;
      _snapshot = const ConversationSnapshot();
      activeConversationId = null;
    } finally {
      conversationsRestoring = false;
      notifyListeners();
    }
  }

  void newConversation() {
    if (_isActive) return;
    activeConversationId = null;
    responseText = '';
    errorMessage = null;
    statusMessage = null;
    usage = const {};
    state = ChatRunState.idle;
    notifyListeners();
  }

  void selectConversation(String conversationId) {
    if (_isActive || !conversations.any((item) => item.id == conversationId)) {
      return;
    }
    activeConversationId = conversationId;
    responseText = _lastAssistantText(activeConversation) ?? '';
    errorMessage = null;
    statusMessage = null;
    usage = const {};
    state = ChatRunState.idle;
    notifyListeners();
  }

  Future<void> deleteConversation(String conversationId) async {
    if (_isActive || !conversations.any((item) => item.id == conversationId)) {
      return;
    }
    final next = ConversationSnapshot(
      records: conversations
          .where((conversation) => conversation.id != conversationId)
          .toList(growable: false),
    );
    try {
      await _conversationStore.save(next);
      _snapshot = next;
      if (activeConversationId == conversationId) newConversation();
      conversationErrorMessage = null;
      notifyListeners();
    } on ConversationStoreException catch (error) {
      conversationErrorMessage = error.message;
      notifyListeners();
    }
  }

  Future<void> clearAllConversations() async {
    if (_isActive) return;
    try {
      await _conversationStore.clear();
      _snapshot = const ConversationSnapshot();
      activeConversationId = null;
      responseText = '';
      errorMessage = null;
      statusMessage = null;
      usage = const {};
      state = ChatRunState.idle;
      conversationErrorMessage = null;
      notifyListeners();
    } on ConversationStoreException catch (error) {
      conversationErrorMessage = error.message;
      notifyListeners();
    }
  }

  Future<void> send({
    required LlmProvider provider,
    required String model,
    required String prompt,
  }) async {
    if (_isActive || model.isEmpty || prompt.trim().isEmpty) return;
    await initialize();
    final normalizedPrompt = prompt.trim();
    if (normalizedPrompt.length > conversationMaxMessageCharacters) {
      conversationErrorMessage = '한 메시지는 32KB 이내로 입력해 주세요.';
      notifyListeners();
      return;
    }
    final requestId =
        'mobile_${DateTime.now().microsecondsSinceEpoch}_${_sequence++}';
    final now = DateTime.now().toUtc();
    final conversation = _conversationForSend(
      provider: provider,
      model: model,
      prompt: normalizedPrompt,
      now: now,
    );
    conversationErrorMessage = null;
    await _upsertConversation(conversation, preserveMemoryOnFailure: true);
    activeConversationId = conversation.id;
    _runConversationId = conversation.id;
    activeRequestId = requestId;
    responseText = '';
    usage = const {};
    errorMessage = null;
    statusMessage = null;
    lastCancelResult = null;
    state = ChatRunState.streaming;
    notifyListeners();
    try {
      final stream = await _transport.start(
        LlmStreamRequest(
          requestId: requestId,
          provider: provider,
          model: model,
          messages: conversation.messages
              .map(
                (message) => LlmMessage(
                  role: message.role.name,
                  content: message.content,
                ),
              )
              .toList(growable: false),
        ),
      );
      _subscription = stream.listen(
        _handleEvent,
        onError: (Object error) {
          if (activeRequestId != requestId) return;
          state = ChatRunState.failure;
          errorMessage = error is LlmTransportException
              ? error.message
              : '응답 스트림을 처리하지 못했습니다.';
          activeRequestId = null;
          _clearRunContext();
          notifyListeners();
        },
      );
    } on LlmTransportException catch (error) {
      if (activeRequestId != requestId) return;
      state = ChatRunState.failure;
      errorMessage = error.message;
      activeRequestId = null;
      _clearRunContext();
      notifyListeners();
    }
  }

  void _handleEvent(LlmStreamEvent event) {
    if (event.requestId != activeRequestId) return;
    switch (event.type) {
      case 'delta':
        final text = event.data['text'];
        if (text is String) responseText += text;
      case 'usage':
        usage = event.data;
      case 'done':
        state = ChatRunState.completed;
        statusMessage = '응답이 완료되었습니다.';
        activeRequestId = null;
        unawaited(_finalizeAssistantResponse(event.requestId));
      case 'cancelled':
        state = ChatRunState.cancelled;
        statusMessage ??= '디바이스의 LLM 실행을 중단했습니다.';
        activeRequestId = null;
        unawaited(_finalizeAssistantResponse(event.requestId));
      case 'error':
        state = ChatRunState.failure;
        errorMessage = _messageFor(event.data['code']);
        activeRequestId = null;
        _clearRunContext();
    }
    notifyListeners();
  }

  Future<void> _finalizeAssistantResponse(String requestId) async {
    if (!_finalizedRequestIds.add(requestId)) return;
    final conversationId = _runConversationId;
    _clearRunContext();
    final response = responseText.trim();
    if (conversationId == null || response.isEmpty) return;
    final current = conversations.where((item) => item.id == conversationId);
    if (current.isEmpty) return;
    final now = DateTime.now().toUtc();
    final persistedResponse = response.length > conversationMaxMessageCharacters
        ? '${response.substring(0, conversationMaxMessageCharacters - 1)}…'
        : response;
    final conversation = current.single.copyWith(
      updatedAt: now,
      messages: <ConversationMessage>[
        ...current.single.messages,
        ConversationMessage(
          role: ConversationRole.assistant,
          content: persistedResponse,
          createdAt: now,
        ),
      ],
    );
    final saved = await _upsertConversation(
      conversation,
      preserveMemoryOnFailure: true,
    );
    if (saved && persistedResponse != response) {
      conversationErrorMessage = '응답이 길어 이 기기의 대화에는 앞부분만 저장했습니다.';
    }
    notifyListeners();
  }

  Future<bool> _upsertConversation(
    ConversationRecord conversation, {
    required bool preserveMemoryOnFailure,
  }) async {
    final nextRecords = <ConversationRecord>[
      conversation,
      ...conversations.where((item) => item.id != conversation.id),
    ]..sort((left, right) => right.updatedAt.compareTo(left.updatedAt));
    final next = ConversationSnapshot(records: nextRecords.take(20).toList());
    try {
      await _conversationStore.save(next);
      _snapshot = next;
      conversationErrorMessage = null;
      return true;
    } on ConversationStoreException catch (error) {
      if (preserveMemoryOnFailure) _snapshot = next;
      conversationErrorMessage = error.message;
      return false;
    }
  }

  ConversationRecord _conversationForSend({
    required LlmProvider provider,
    required String model,
    required String prompt,
    required DateTime now,
  }) {
    final current = activeConversation;
    final useExisting =
        current != null &&
        current.messages.length < conversationMaxMessagesPerRecord - 1;
    final messages = <ConversationMessage>[
      ...(useExisting ? current.messages : const <ConversationMessage>[]),
      ConversationMessage(
        role: ConversationRole.user,
        content: prompt,
        createdAt: now,
      ),
    ];
    return ConversationRecord(
      id: useExisting
          ? current.id
          : 'conversation_${now.microsecondsSinceEpoch}_$_sequence',
      provider: provider.name,
      model: model,
      updatedAt: now,
      messages: messages,
    );
  }

  Future<void> stop() async {
    final requestId = activeRequestId;
    if (requestId == null) return;
    state = ChatRunState.cancelling;
    statusMessage = '디바이스 실행을 중단하고 서버 응답을 확인하고 있습니다.';
    notifyListeners();
    try {
      final cancelResult = await _transport.cancel(requestId);
      lastCancelResult = cancelResult;
      statusMessage = cancelResult.serverConfirmedStopped
          ? '디바이스와 서버에서 요청 중단을 확인했습니다.'
          : '디바이스 실행은 중단했지만 서버 확인에 실패했습니다.';
      if (activeRequestId == requestId) {
        state = ChatRunState.cancelled;
        activeRequestId = null;
        await _finalizeAssistantResponse(requestId);
      }
    } on LlmTransportException catch (error) {
      statusMessage = '디바이스 중단 확인 중 오류가 발생했습니다.';
      if (activeRequestId == requestId) {
        state = ChatRunState.failure;
        errorMessage = error.message;
        activeRequestId = null;
        _clearRunContext();
      }
    }
    notifyListeners();
  }

  Future<void> reconcileAfterResume() async {
    final requestId = activeRequestId;
    if (requestId == null || !_isActive) return;
    try {
      final nativeState = await _transport.requestState(requestId);
      if (activeRequestId != requestId) return;
      if (nativeState == LlmRequestState.notFound) {
        state = ChatRunState.failure;
        activeRequestId = null;
        errorMessage = '앱이 중단된 동안 실행 연결이 종료되었습니다. 자동 재전송하지 않습니다.';
        statusMessage = null;
        await _finalizeAssistantResponse(requestId);
        await _subscription?.cancel();
        notifyListeners();
      }
    } on LlmTransportException {
      // Resume reconciliation is advisory. The active stream remains authoritative.
    }
  }

  bool get _isActive =>
      state == ChatRunState.streaming || state == ChatRunState.cancelling;

  void _clearRunContext() {
    _runConversationId = null;
  }

  String? _lastAssistantText(ConversationRecord? conversation) {
    if (conversation == null) return null;
    for (final message in conversation.messages.reversed) {
      if (message.role == ConversationRole.assistant) return message.content;
    }
    return null;
  }

  String _messageFor(Object? code) => switch (code) {
    'provider_rate_limited' => 'Provider 요청 한도에 도달했습니다.',
    'provider_access_denied' => 'Provider 사용 권한이 없습니다.',
    'model_not_allowed' => '허용되지 않은 모델입니다.',
    'reauthentication_required' => '다시 로그인해야 합니다.',
    _ => '외부 LLM 요청을 완료하지 못했습니다.',
  };

  @override
  void dispose() {
    _subscription?.cancel();
    final requestId = activeRequestId;
    if (requestId != null) unawaited(_transport.cancel(requestId));
    super.dispose();
  }
}
