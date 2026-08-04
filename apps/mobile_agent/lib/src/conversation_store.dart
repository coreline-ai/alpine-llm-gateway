import 'dart:convert';

import 'package:flutter/services.dart';

const int conversationStorageSchemaVersion = 1;
const int conversationMaxRecords = 20;
const int conversationMaxMessagesPerRecord = 64;
const int conversationMaxMessageCharacters = 32 * 1024;
const int conversationMaxSnapshotBytes = 1024 * 1024;
const int conversationMaxSnapshotCharacters = 512 * 1024;

enum ConversationRole { user, assistant }

final class ConversationMessage {
  const ConversationMessage({
    required this.role,
    required this.content,
    required this.createdAt,
  });

  final ConversationRole role;
  final String content;
  final DateTime createdAt;

  Map<String, Object> toJson() => <String, Object>{
    'role': role.name,
    'content': content,
    'createdAtMillis': createdAt.toUtc().millisecondsSinceEpoch,
  };

  factory ConversationMessage.fromJson(Map<Object?, Object?> value) {
    _requireExactKeys(value, const {'role', 'content', 'createdAtMillis'});
    final role = switch (value['role']) {
      'user' => ConversationRole.user,
      'assistant' => ConversationRole.assistant,
      _ => throw const ConversationStoreException(
        code: 'conversation_invalid',
        message: '저장된 대화 형식이 올바르지 않습니다.',
      ),
    };
    final content = value['content'];
    final createdAtMillis = value['createdAtMillis'];
    if (content is! String ||
        content.trim().isEmpty ||
        content.length > conversationMaxMessageCharacters ||
        createdAtMillis is! int ||
        createdAtMillis <= 0) {
      throw const ConversationStoreException(
        code: 'conversation_invalid',
        message: '저장된 대화 형식이 올바르지 않습니다.',
      );
    }
    return ConversationMessage(
      role: role,
      content: content,
      createdAt: DateTime.fromMillisecondsSinceEpoch(
        createdAtMillis,
        isUtc: true,
      ),
    );
  }
}

final class ConversationRecord {
  const ConversationRecord({
    required this.id,
    required this.provider,
    required this.model,
    required this.updatedAt,
    required this.messages,
  });

  final String id;
  final String provider;
  final String model;
  final DateTime updatedAt;
  final List<ConversationMessage> messages;

  String get title {
    final firstUser = messages.cast<ConversationMessage?>().firstWhere(
      (message) => message?.role == ConversationRole.user,
      orElse: () => null,
    );
    final content = firstUser?.content.trim() ?? '새 대화';
    return content.length <= 42 ? content : '${content.substring(0, 42)}…';
  }

  bool matches(String query) {
    final normalized = query.trim().toLowerCase();
    if (normalized.isEmpty) return true;
    return title.toLowerCase().contains(normalized) ||
        model.toLowerCase().contains(normalized) ||
        messages.any(
          (message) => message.content.toLowerCase().contains(normalized),
        );
  }

  ConversationRecord copyWith({
    String? provider,
    String? model,
    DateTime? updatedAt,
    List<ConversationMessage>? messages,
  }) => ConversationRecord(
    id: id,
    provider: provider ?? this.provider,
    model: model ?? this.model,
    updatedAt: updatedAt ?? this.updatedAt,
    messages: messages ?? this.messages,
  );

  Map<String, Object> toJson() => <String, Object>{
    'id': id,
    'provider': provider,
    'model': model,
    'updatedAtMillis': updatedAt.toUtc().millisecondsSinceEpoch,
    'messages': messages.map((message) => message.toJson()).toList(),
  };

  factory ConversationRecord.fromJson(Map<Object?, Object?> value) {
    _requireExactKeys(value, const {
      'id',
      'provider',
      'model',
      'updatedAtMillis',
      'messages',
    });
    final id = value['id'];
    final provider = value['provider'];
    final model = value['model'];
    final updatedAtMillis = value['updatedAtMillis'];
    final rawMessages = value['messages'];
    if (id is! String ||
        !RegExp(r'^[A-Za-z0-9_-]{8,80}$').hasMatch(id) ||
        provider is! String ||
        !const {'openai', 'anthropic', 'xai'}.contains(provider) ||
        model is! String ||
        !RegExp(r'^[A-Za-z0-9._:/-]{1,120}$').hasMatch(model) ||
        updatedAtMillis is! int ||
        updatedAtMillis <= 0 ||
        rawMessages is! List ||
        rawMessages.isEmpty ||
        rawMessages.length > conversationMaxMessagesPerRecord) {
      throw const ConversationStoreException(
        code: 'conversation_invalid',
        message: '저장된 대화 형식이 올바르지 않습니다.',
      );
    }
    final messages = rawMessages
        .map((raw) {
          if (raw is! Map) {
            throw const ConversationStoreException(
              code: 'conversation_invalid',
              message: '저장된 대화 형식이 올바르지 않습니다.',
            );
          }
          return ConversationMessage.fromJson(raw);
        })
        .toList(growable: false);
    if (messages.first.role != ConversationRole.user) {
      throw const ConversationStoreException(
        code: 'conversation_invalid',
        message: '저장된 대화 형식이 올바르지 않습니다.',
      );
    }
    return ConversationRecord(
      id: id,
      provider: provider,
      model: model,
      updatedAt: DateTime.fromMillisecondsSinceEpoch(
        updatedAtMillis,
        isUtc: true,
      ),
      messages: messages,
    );
  }
}

final class ConversationSnapshot {
  const ConversationSnapshot({this.records = const <ConversationRecord>[]});

  final List<ConversationRecord> records;

  List<ConversationRecord> search(String query) =>
      records.where((record) => record.matches(query)).toList(growable: false);

  ConversationSnapshot copyWith({List<ConversationRecord>? records}) =>
      ConversationSnapshot(records: records ?? this.records);

  String encode() {
    _validateRecords(records);
    final payload = jsonEncode(<String, Object>{
      'schemaVersion': conversationStorageSchemaVersion,
      'records': records.map((record) => record.toJson()).toList(),
    });
    if (utf8.encode(payload).length > conversationMaxSnapshotBytes) {
      throw const ConversationStoreException(
        code: 'conversation_too_large',
        message: '저장 가능한 대화 용량을 초과했습니다.',
      );
    }
    return payload;
  }

  factory ConversationSnapshot.decode(String payload) {
    if (utf8.encode(payload).length > conversationMaxSnapshotBytes) {
      throw const ConversationStoreException(
        code: 'conversation_too_large',
        message: '저장된 대화 용량이 올바르지 않습니다.',
      );
    }
    try {
      final decoded = jsonDecode(payload);
      if (decoded is! Map) throw const FormatException();
      final value = Map<Object?, Object?>.from(decoded);
      _requireExactKeys(value, const {'schemaVersion', 'records'});
      if (value['schemaVersion'] != conversationStorageSchemaVersion ||
          value['records'] is! List) {
        throw const FormatException();
      }
      final records =
          (value['records'] as List)
              .map((raw) {
                if (raw is! Map) throw const FormatException();
                return ConversationRecord.fromJson(
                  Map<Object?, Object?>.from(raw),
                );
              })
              .toList(growable: false)
            ..sort((left, right) => right.updatedAt.compareTo(left.updatedAt));
      _validateRecords(records);
      return ConversationSnapshot(records: records);
    } on ConversationStoreException {
      rethrow;
    } catch (_) {
      throw const ConversationStoreException(
        code: 'conversation_invalid',
        message: '저장된 대화 형식이 올바르지 않습니다.',
      );
    }
  }
}

abstract interface class ConversationStore {
  Future<ConversationSnapshot> load();

  Future<void> save(ConversationSnapshot snapshot);

  Future<void> clear();
}

final class InMemoryConversationStore implements ConversationStore {
  ConversationSnapshot _snapshot = const ConversationSnapshot();

  @override
  Future<void> clear() async {
    _snapshot = const ConversationSnapshot();
  }

  @override
  Future<ConversationSnapshot> load() async => _snapshot;

  @override
  Future<void> save(ConversationSnapshot snapshot) async {
    snapshot.encode();
    _snapshot = snapshot;
  }
}

final class NativeConversationStore implements ConversationStore {
  NativeConversationStore({MethodChannel? methodChannel})
    : _methodChannel =
          methodChannel ?? const MethodChannel('mobile_agent_conversations');

  final MethodChannel _methodChannel;

  @override
  Future<ConversationSnapshot> load() async {
    try {
      final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
        'loadConversationSnapshot',
      );
      if (value == null) return const ConversationSnapshot();
      _requireExactKeys(value, const {'schemaVersion', 'payload'});
      if (value['schemaVersion'] != conversationStorageSchemaVersion ||
          value['payload'] is! String) {
        throw const ConversationStoreException(
          code: 'conversation_invalid',
          message: '저장된 대화 형식이 올바르지 않습니다.',
        );
      }
      return ConversationSnapshot.decode(value['payload']! as String);
    } on PlatformException catch (_) {
      throw const ConversationStoreException(
        code: 'conversation_storage_failure',
        message: '이 기기의 암호화 대화를 불러오지 못했습니다.',
      );
    }
  }

  @override
  Future<void> save(ConversationSnapshot snapshot) async {
    final payload = snapshot.encode();
    try {
      await _methodChannel.invokeMethod<void>(
        'saveConversationSnapshot',
        <String, Object>{
          'schemaVersion': conversationStorageSchemaVersion,
          'payload': payload,
        },
      );
    } on PlatformException catch (_) {
      throw const ConversationStoreException(
        code: 'conversation_storage_failure',
        message: '이 기기의 암호화 대화를 저장하지 못했습니다.',
      );
    }
  }

  @override
  Future<void> clear() async {
    try {
      await _methodChannel.invokeMethod<void>('clearConversationSnapshot');
    } on PlatformException catch (_) {
      throw const ConversationStoreException(
        code: 'conversation_storage_failure',
        message: '이 기기의 암호화 대화를 삭제하지 못했습니다.',
      );
    }
  }
}

final class ConversationStoreException implements Exception {
  const ConversationStoreException({required this.code, required this.message});

  final String code;
  final String message;
}

void _validateRecords(List<ConversationRecord> records) {
  if (records.length > conversationMaxRecords ||
      records.map((record) => record.id).toSet().length != records.length) {
    throw const ConversationStoreException(
      code: 'conversation_invalid',
      message: '저장된 대화 형식이 올바르지 않습니다.',
    );
  }
  var totalCharacters = 0;
  for (final record in records) {
    ConversationRecord.fromJson(record.toJson());
    totalCharacters += record.messages.fold<int>(
      0,
      (total, message) => total + message.content.length,
    );
    if (totalCharacters > conversationMaxSnapshotCharacters) {
      throw const ConversationStoreException(
        code: 'conversation_too_large',
        message: '저장 가능한 대화 용량을 초과했습니다.',
      );
    }
  }
}

void _requireExactKeys(Map<Object?, Object?> value, Set<String> keys) {
  if (value.keys.any((key) => key is! String || !keys.contains(key)) ||
      value.length != keys.length) {
    throw const ConversationStoreException(
      code: 'conversation_invalid',
      message: '저장된 대화 형식이 올바르지 않습니다.',
    );
  }
}
