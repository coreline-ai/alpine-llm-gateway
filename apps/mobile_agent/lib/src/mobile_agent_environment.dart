import 'package:mobile_agent_auth/mobile_agent_auth.dart';

final class MobileAgentEnvironment {
  const MobileAgentEnvironment({
    required this.issuer,
    required this.clientId,
    required this.redirectUri,
    required this.audience,
    required this.bffBaseUrl,
    required this.openAiModel,
    required this.anthropicModel,
    required this.xaiModel,
  });

  factory MobileAgentEnvironment.fromBuild() => MobileAgentEnvironment(
    issuer: const String.fromEnvironment('OIDC_ISSUER'),
    clientId: const String.fromEnvironment('OIDC_CLIENT_ID'),
    redirectUri: const String.fromEnvironment(
      'OIDC_REDIRECT_URI',
      defaultValue: 'ai.coreline.mobileagent:/oauth/callback',
    ),
    audience: const String.fromEnvironment('OIDC_AUDIENCE'),
    bffBaseUrl: const String.fromEnvironment('BFF_BASE_URL'),
    openAiModel: const String.fromEnvironment('OPENAI_MODEL'),
    anthropicModel: const String.fromEnvironment('ANTHROPIC_MODEL'),
    xaiModel: const String.fromEnvironment('XAI_MODEL'),
  );

  final String issuer;
  final String clientId;
  final String redirectUri;
  final String audience;
  final String bffBaseUrl;
  final String openAiModel;
  final String anthropicModel;
  final String xaiModel;

  bool get hasOAuthConfiguration => issuer.isNotEmpty && clientId.isNotEmpty;
  bool get hasBffConfiguration =>
      Uri.tryParse(bffBaseUrl)?.scheme.toLowerCase() == 'https';

  String modelFor(String provider) => switch (provider) {
    'openai' => openAiModel,
    'anthropic' => anthropicModel,
    'xai' => xaiModel,
    _ => '',
  };

  MobileAgentAuthConfiguration get authConfiguration =>
      MobileAgentAuthConfiguration(
        issuer: Uri.parse(issuer),
        clientId: clientId,
        redirectUri: Uri.parse(redirectUri),
        audience: audience.isEmpty ? null : audience,
      );
}
