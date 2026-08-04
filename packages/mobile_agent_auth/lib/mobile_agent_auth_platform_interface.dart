import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'mobile_agent_auth.dart';
import 'mobile_agent_auth_method_channel.dart';

abstract class MobileAgentAuthPlatform extends PlatformInterface {
  MobileAgentAuthPlatform() : super(token: _token);

  static final Object _token = Object();
  static MobileAgentAuthPlatform _instance = MethodChannelMobileAgentAuth();

  static MobileAgentAuthPlatform get instance => _instance;

  static set instance(MobileAgentAuthPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<MobileAgentAuthCapabilities> capabilities() {
    throw UnimplementedError('capabilities() has not been implemented.');
  }

  Stream<AuthEvent> authStateStream() {
    throw UnimplementedError('authStateStream() has not been implemented.');
  }

  Future<AuthSessionSummary> signIn(
    MobileAgentAuthConfiguration configuration,
  ) {
    throw UnimplementedError('signIn() has not been implemented.');
  }

  Future<AuthSessionSummary> restoreSession() {
    throw UnimplementedError('restoreSession() has not been implemented.');
  }

  Future<void> cancelSignIn() {
    throw UnimplementedError('cancelSignIn() has not been implemented.');
  }

  Future<void> signOut(Uri? bffBaseUrl) {
    throw UnimplementedError('signOut() has not been implemented.');
  }
}
