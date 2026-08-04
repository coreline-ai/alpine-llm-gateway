import 'dart:async';

import 'package:flutter/services.dart';

enum LlmProvider { openai, anthropic, xai }

enum LlmRequestState { notFound, preparing, streaming, cancelling }

enum LlmCancelAcknowledgment { accepted, notActive, notRequired, unavailable }

final class LlmCancelResult {
  const LlmCancelResult({
    required this.requestId,
    required this.localCancelled,
    required this.serverAcknowledgment,
  });

  final String requestId;
  final bool localCancelled;
  final LlmCancelAcknowledgment serverAcknowledgment;

  bool get serverConfirmedStopped => const {
    LlmCancelAcknowledgment.accepted,
    LlmCancelAcknowledgment.notActive,
    LlmCancelAcknowledgment.notRequired,
  }.contains(serverAcknowledgment);

  factory LlmCancelResult.fromMap(Map<Object?, Object?> value) {
    final requestId = value['requestId'];
    final localCancelled = value['localCancelled'];
    final serverAcknowledgment = switch (value['serverAcknowledgment']) {
      'accepted' => LlmCancelAcknowledgment.accepted,
      'not_active' => LlmCancelAcknowledgment.notActive,
      'not_required' => LlmCancelAcknowledgment.notRequired,
      'unavailable' => LlmCancelAcknowledgment.unavailable,
      _ => null,
    };
    if (requestId is! String ||
        localCancelled is! bool ||
        serverAcknowledgment == null) {
      throw const LlmTransportException(
        code: 'cancel_result_invalid',
        message: '중단 확인 응답 형식이 올바르지 않습니다.',
      );
    }
    return LlmCancelResult(
      requestId: requestId,
      localCancelled: localCancelled,
      serverAcknowledgment: serverAcknowledgment,
    );
  }
}

final class LlmMessage {
  const LlmMessage({required this.role, required this.content});

  final String role;
  final String content;

  Map<String, Object> toMap() => <String, Object>{
    'role': role,
    'content': content,
  };
}

final class LlmStreamRequest {
  const LlmStreamRequest({
    required this.requestId,
    required this.provider,
    required this.model,
    required this.messages,
    this.temperature,
  });

  final String requestId;
  final LlmProvider provider;
  final String model;
  final List<LlmMessage> messages;
  final double? temperature;

  void validate() {
    if (!RegExp(r'^[A-Za-z0-9_-]{8,80}$').hasMatch(requestId) ||
        !RegExp(r'^[A-Za-z0-9._:/-]{1,120}$').hasMatch(model) ||
        messages.isEmpty ||
        messages.length > 128 ||
        !messages.any((message) => message.role == 'user') ||
        messages.any(
          (message) =>
              !const {'system', 'user', 'assistant'}.contains(message.role) ||
              message.content.isEmpty ||
              message.content.length > 80000,
        )) {
      throw const LlmTransportException(
        code: 'request_invalid',
        message: 'LLM 요청 형식이 올바르지 않습니다.',
      );
    }
  }

  Map<String, Object> toMap() => <String, Object>{
    'request_id': requestId,
    'provider': provider.name,
    'model': model,
    'messages': messages.map((message) => message.toMap()).toList(),
    'temperature': ?temperature,
  };
}

final class LlmStreamEvent {
  const LlmStreamEvent({
    required this.requestId,
    required this.type,
    required this.data,
  });

  final String requestId;
  final String type;
  final Map<String, Object?> data;

  bool get terminal => const {'done', 'cancelled', 'error'}.contains(type);

  factory LlmStreamEvent.fromMap(Map<Object?, Object?> value) {
    final requestId = value['requestId'];
    final type = value['type'];
    final rawData = value['data'];
    if (requestId is! String || type is! String || rawData is! Map) {
      throw const LlmTransportException(
        code: 'event_invalid',
        message: 'LLM 응답 형식이 올바르지 않습니다.',
      );
    }
    return LlmStreamEvent(
      requestId: requestId,
      type: type,
      data: Map<String, Object?>.from(rawData),
    );
  }
}

final class LlmTransportException implements Exception {
  const LlmTransportException({required this.code, required this.message});

  final String code;
  final String message;
}

abstract interface class LlmTransport {
  Future<Stream<LlmStreamEvent>> start(LlmStreamRequest request);

  Future<LlmCancelResult> cancel(String requestId);

  Future<LlmRequestState> requestState(String requestId);
}

final class NativeLlmTransport implements LlmTransport {
  NativeLlmTransport({required this.bffBaseUrl});

  final Uri bffBaseUrl;
  static const _methodChannel = MethodChannel('mobile_agent_llm_transport');
  static const _eventChannel = EventChannel(
    'mobile_agent_llm_transport/events',
  );
  static final Stream<dynamic> _nativeEvents = _eventChannel
      .receiveBroadcastStream();

  @override
  Future<Stream<LlmStreamEvent>> start(LlmStreamRequest request) async {
    _validateBaseUrl();
    request.validate();
    final controller = StreamController<LlmStreamEvent>();
    late final StreamSubscription<dynamic> subscription;
    subscription = _nativeEvents.listen((dynamic raw) {
      if (raw is! Map || raw['requestId'] != request.requestId) return;
      try {
        final event = LlmStreamEvent.fromMap(raw);
        controller.add(event);
        if (event.terminal) {
          subscription.cancel();
          controller.close();
        }
      } on LlmTransportException catch (error, stackTrace) {
        controller.addError(error, stackTrace);
        subscription.cancel();
        controller.close();
      }
    }, onError: controller.addError);
    try {
      await _methodChannel.invokeMethod<void>('startStream', <String, Object>{
        'bffBaseUrl': bffBaseUrl.toString(),
        'request': request.toMap(),
      });
    } on PlatformException catch (error) {
      await subscription.cancel();
      await controller.close();
      throw LlmTransportException(
        code: error.code,
        message: _messageFor(error.code),
      );
    }
    controller.onCancel = () => cancel(request.requestId);
    return controller.stream;
  }

  @override
  Future<LlmCancelResult> cancel(String requestId) async {
    _validateRequestId(requestId);
    try {
      final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
        'cancelRequest',
        <String, Object>{
          'requestId': requestId,
          'bffBaseUrl': bffBaseUrl.toString(),
        },
      );
      return LlmCancelResult.fromMap(value ?? const <Object?, Object?>{});
    } on PlatformException catch (error) {
      throw LlmTransportException(
        code: error.code,
        message: _messageFor(error.code),
      );
    }
  }

  @override
  Future<LlmRequestState> requestState(String requestId) async {
    _validateRequestId(requestId);
    try {
      final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
        'requestState',
        <String, Object>{'requestId': requestId},
      );
      return switch (value?['state']) {
        'preparing' => LlmRequestState.preparing,
        'streaming' => LlmRequestState.streaming,
        'cancelling' => LlmRequestState.cancelling,
        'not_found' => LlmRequestState.notFound,
        _ => throw const LlmTransportException(
          code: 'request_state_invalid',
          message: '요청 상태 형식이 올바르지 않습니다.',
        ),
      };
    } on PlatformException catch (error) {
      throw LlmTransportException(
        code: error.code,
        message: _messageFor(error.code),
      );
    }
  }

  void _validateRequestId(String requestId) {
    if (!RegExp(r'^[A-Za-z0-9_-]{8,80}$').hasMatch(requestId)) {
      throw const LlmTransportException(
        code: 'request_invalid',
        message: '요청 ID가 올바르지 않습니다.',
      );
    }
  }

  void _validateBaseUrl() {
    if (bffBaseUrl.scheme != 'https' ||
        bffBaseUrl.host.isEmpty ||
        bffBaseUrl.userInfo.isNotEmpty ||
        bffBaseUrl.hasQuery ||
        bffBaseUrl.hasFragment) {
      throw const LlmTransportException(
        code: 'configuration_invalid',
        message: 'BFF 주소는 깨끗한 HTTPS URL이어야 합니다.',
      );
    }
  }

  String _messageFor(String code) => switch (code) {
    'reauthentication_required' => '다시 로그인해야 합니다.',
    'request_in_progress' => '같은 요청이 이미 실행 중입니다.',
    'network_unavailable' => 'MobileAgent 서버에 연결할 수 없습니다.',
    'request_cancelled' => '요청이 취소되었습니다.',
    _ => 'LLM 요청을 완료하지 못했습니다.',
  };
}
