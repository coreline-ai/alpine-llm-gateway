import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';

import 'mobile_agent_environment.dart';

enum AuthUiState {
  restoring,
  signedOut,
  authorizing,
  authenticated,
  reauthenticationRequired,
  failure,
}

final class AuthController extends ChangeNotifier {
  AuthController(this._auth, this._environment) {
    _authEvents = _auth.authStateStream().listen(
      _handleAuthEvent,
      onError: _handleAuthEventError,
    );
  }

  final MobileAgentAuth _auth;
  final MobileAgentEnvironment _environment;
  late final StreamSubscription<AuthEvent> _authEvents;

  AuthUiState state = AuthUiState.restoring;
  AuthSessionSummary session = const AuthSessionSummary.signedOut();
  String? errorMessage;

  Future<void> restore() async {
    state = AuthUiState.restoring;
    errorMessage = null;
    notifyListeners();
    try {
      await _auth.capabilities();
      session = await _auth.restoreSession();
      _applySession(session);
    } on MobileAgentAuthException catch (error) {
      state = AuthUiState.failure;
      errorMessage = error.message;
    }
    notifyListeners();
  }

  Future<void> signIn() async {
    if (!_environment.hasOAuthConfiguration ||
        state == AuthUiState.authorizing) {
      return;
    }
    state = AuthUiState.authorizing;
    errorMessage = null;
    notifyListeners();
    try {
      session = await _auth.signIn(_environment.authConfiguration);
      _applySession(session);
    } on MobileAgentAuthException catch (error) {
      state = error.code == 'oauth_authorization_cancelled'
          ? AuthUiState.signedOut
          : AuthUiState.failure;
      errorMessage = error.message;
    }
    notifyListeners();
  }

  Future<void> cancelSignIn() async {
    await _auth.cancelSignIn();
    state = AuthUiState.signedOut;
    errorMessage = null;
    notifyListeners();
  }

  Future<void> signOut() async {
    await _auth.signOut(
      bffBaseUrl: _environment.hasBffConfiguration
          ? Uri.parse(_environment.bffBaseUrl)
          : null,
    );
    session = const AuthSessionSummary.signedOut();
    state = AuthUiState.signedOut;
    errorMessage = null;
    notifyListeners();
  }

  void _handleAuthEvent(AuthEvent event) {
    if (state == AuthUiState.authorizing &&
        event.reason == AuthEventReason.restored &&
        event.session.status == AuthSessionStatus.signedOut) {
      return;
    }
    session = event.session;
    _applySession(event.session);
    if (state != AuthUiState.failure) errorMessage = null;
    if (state == AuthUiState.reauthenticationRequired) {
      errorMessage = '인증 세션이 만료되었습니다. 다시 로그인해 주세요.';
    }
    notifyListeners();
  }

  void _handleAuthEventError(Object error) {
    state = AuthUiState.failure;
    errorMessage = error is MobileAgentAuthException
        ? error.message
        : '인증 상태 변경을 처리하지 못했습니다.';
    notifyListeners();
  }

  void _applySession(AuthSessionSummary value) {
    state = switch (value.status) {
      AuthSessionStatus.authenticated => AuthUiState.authenticated,
      AuthSessionStatus.reauthenticationRequired =>
        AuthUiState.reauthenticationRequired,
      AuthSessionStatus.signedOut => AuthUiState.signedOut,
    };
  }

  @override
  void dispose() {
    _authEvents.cancel();
    super.dispose();
  }
}
