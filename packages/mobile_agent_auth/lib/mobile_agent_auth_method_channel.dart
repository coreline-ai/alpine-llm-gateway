import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'mobile_agent_auth.dart';
import 'mobile_agent_auth_platform_interface.dart';

final class MethodChannelMobileAgentAuth extends MobileAgentAuthPlatform {
  MethodChannelMobileAgentAuth({
    MethodChannel? methodChannel,
    EventChannel? authEventChannel,
  }) : methodChannel =
           methodChannel ?? const MethodChannel('mobile_agent_auth'),
       authEventChannel =
           authEventChannel ?? const EventChannel('mobile_agent_auth/events');

  @visibleForTesting
  final MethodChannel methodChannel;

  @visibleForTesting
  final EventChannel authEventChannel;

  Stream<AuthEvent>? _events;

  @override
  Future<MobileAgentAuthCapabilities> capabilities() async {
    try {
      final value = await methodChannel.invokeMapMethod<Object?, Object?>(
        'getCapabilities',
      );
      return MobileAgentAuthCapabilities.fromMap(
        value ?? const <Object?, Object?>{},
      );
    } on PlatformException catch (error) {
      throw _redacted(error);
    }
  }

  @override
  Stream<AuthEvent> authStateStream() => _events ??= authEventChannel
      .receiveBroadcastStream()
      .map<AuthEvent>((raw) {
        if (raw is! Map) {
          throw const MobileAgentAuthException(
            code: 'auth_event_invalid',
            message: '인증 상태 변경 형식이 올바르지 않습니다.',
          );
        }
        return AuthEvent.fromMap(raw);
      })
      .handleError((Object error) {
        if (error is PlatformException) throw _redacted(error);
        throw error;
      });

  @override
  Future<AuthSessionSummary> signIn(
    MobileAgentAuthConfiguration configuration,
  ) async {
    try {
      final value = await methodChannel.invokeMapMethod<Object?, Object?>(
        'signIn',
        configuration.toMap(),
      );
      return AuthSessionSummary.fromMap(value ?? const <Object?, Object?>{});
    } on PlatformException catch (error) {
      throw _redacted(error);
    }
  }

  @override
  Future<AuthSessionSummary> restoreSession() async {
    try {
      final value = await methodChannel.invokeMapMethod<Object?, Object?>(
        'restoreSession',
      );
      return AuthSessionSummary.fromMap(value ?? const <Object?, Object?>{});
    } on PlatformException catch (error) {
      throw _redacted(error);
    }
  }

  @override
  Future<void> cancelSignIn() async {
    try {
      await methodChannel.invokeMethod<void>('cancelSignIn');
    } on PlatformException catch (error) {
      throw _redacted(error);
    }
  }

  @override
  Future<void> signOut(Uri? bffBaseUrl) async {
    try {
      await methodChannel.invokeMethod<void>('signOut', <String, Object>{
        if (bffBaseUrl != null) 'bffBaseUrl': bffBaseUrl.toString(),
      });
    } on PlatformException catch (error) {
      throw _redacted(error);
    }
  }

  MobileAgentAuthException _redacted(PlatformException error) =>
      MobileAgentAuthException(
        code: error.code,
        message: switch (error.code) {
          'oauth_authorization_cancelled' => '로그인이 취소되었습니다.',
          'oauth_discovery_failed' => '인증 서버 설정을 확인할 수 없습니다.',
          'oauth_authorization_failed' => '인증 요청이 거부되었습니다.',
          'oauth_token_exchange_failed' => '로그인 완료 코드를 교환하지 못했습니다.',
          'storage_failure' => '보안 저장소를 사용할 수 없습니다.',
          'activity_unavailable' => '로그인 화면을 표시할 수 없습니다.',
          'operation_in_progress' => '이미 로그인이 진행 중입니다.',
          'protocol_version_mismatch' => '앱과 인증 플러그인 버전이 호환되지 않습니다.',
          _ => '인증을 완료하지 못했습니다.',
        },
      );
}
