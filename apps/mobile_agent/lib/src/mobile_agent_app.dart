import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';
import 'package:mobile_agent_llm_transport/mobile_agent_llm_transport.dart';

import 'auth_controller.dart';
import 'chat_controller.dart';
import 'conversation_store.dart';
import 'mobile_agent_environment.dart';

const _ink = Color(0xFF10120F);
const _paper = Color(0xFFF4F3ED);
const _acid = Color(0xFFB9F227);
const _slate = Color(0xFF31372F);

class MobileAgentApp extends StatefulWidget {
  const MobileAgentApp({super.key});

  @override
  State<MobileAgentApp> createState() => _MobileAgentAppState();
}

class _MobileAgentAppState extends State<MobileAgentApp> {
  late final MobileAgentEnvironment environment;
  late final AuthController controller;

  @override
  void initState() {
    super.initState();
    environment = MobileAgentEnvironment.fromBuild();
    controller = AuthController(const MobileAgentAuth(), environment);
    controller.restore();
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'MobileAgent',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: _paper,
      colorScheme: ColorScheme.fromSeed(
        seedColor: _acid,
        brightness: Brightness.light,
        surface: _paper,
      ),
      textTheme: ThemeData.light().textTheme.apply(
        bodyColor: _ink,
        displayColor: _ink,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: _ink,
          foregroundColor: _paper,
          minimumSize: const Size.fromHeight(56),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    ),
    home: OAuthLandingScreen(controller: controller, environment: environment),
  );
}

class OAuthLandingScreen extends StatelessWidget {
  const OAuthLandingScreen({
    required this.controller,
    required this.environment,
    super.key,
  });

  final AuthController controller;
  final MobileAgentEnvironment environment;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            padding: EdgeInsets.symmetric(
              horizontal: constraints.maxWidth >= 720 ? 48 : 20,
              vertical: 20,
            ),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 920),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const _BrandBar(),
                    const SizedBox(height: 32),
                    _HeroPanel(
                      controller: controller,
                      configured: environment.hasOAuthConfiguration,
                    ),
                    const SizedBox(height: 20),
                    if (!environment.hasOAuthConfiguration)
                      const _ConfigurationNotice(),
                    if (controller.errorMessage case final message?) ...[
                      const SizedBox(height: 12),
                      _ErrorNotice(message: message),
                    ],
                    const SizedBox(height: 28),
                    Text(
                      'ONE LOGIN · THREE ENGINES',
                      style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        letterSpacing: 1.8,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 12),
                    const _ProviderGrid(),
                    if (controller.state == AuthUiState.authenticated) ...[
                      const SizedBox(height: 20),
                      _ChatWorkspace(environment: environment),
                    ],
                    const SizedBox(height: 24),
                    const _SecurityFootnote(),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _BrandBar extends StatelessWidget {
  const _BrandBar();

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Container(
        width: 42,
        height: 42,
        decoration: BoxDecoration(
          color: _ink,
          borderRadius: BorderRadius.circular(12),
        ),
        alignment: Alignment.center,
        child: const Text(
          '>_',
          style: TextStyle(
            color: _acid,
            fontWeight: FontWeight.w900,
            fontSize: 18,
          ),
        ),
      ),
      const SizedBox(width: 12),
      const Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'MOBILEAGENT',
              style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.4),
            ),
            Text('SECURE REMOTE INTELLIGENCE', style: TextStyle(fontSize: 11)),
          ],
        ),
      ),
      const SizedBox(width: 8),
      const Flexible(child: _ProtocolBadge()),
    ],
  );
}

class _ProtocolBadge extends StatelessWidget {
  const _ProtocolBadge();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
    decoration: BoxDecoration(
      border: Border.all(color: _ink.withValues(alpha: .18)),
      borderRadius: BorderRadius.circular(99),
    ),
    child: const Text(
      'OAUTH 2.0 · PKCE',
      style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800),
    ),
  );
}

class _HeroPanel extends StatelessWidget {
  const _HeroPanel({required this.controller, required this.configured});

  final AuthController controller;
  final bool configured;

  @override
  Widget build(BuildContext context) {
    final authenticated = controller.state == AuthUiState.authenticated;
    final authorizing = controller.state == AuthUiState.authorizing;
    final reauthenticationRequired =
        controller.state == AuthUiState.reauthenticationRequired;
    return Container(
      decoration: BoxDecoration(
        color: _ink,
        borderRadius: BorderRadius.circular(24),
      ),
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
            decoration: BoxDecoration(
              color: _acid,
              borderRadius: BorderRadius.circular(7),
            ),
            child: Text(
              authenticated
                  ? 'SESSION ACTIVE'
                  : reauthenticationRequired
                  ? 'SESSION EXPIRED'
                  : 'NATIVE SYSTEM BROWSER',
              style: const TextStyle(
                color: _ink,
                fontSize: 10,
                fontWeight: FontWeight.w900,
                letterSpacing: 1.1,
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            authenticated
                ? '연결되었습니다.\n작업을 시작하세요.'
                : reauthenticationRequired
                ? '세션이 만료되었습니다.\n다시 연결하세요.'
                : '한 번의 안전한 로그인.\n세 개의 LLM 엔진.',
            style: const TextStyle(
              color: _paper,
              fontSize: 34,
              height: 1.1,
              fontWeight: FontWeight.w900,
              letterSpacing: -1.2,
            ),
          ),
          const SizedBox(height: 14),
          Text(
            authenticated
                ? (controller.session.accountLabel ?? 'MobileAgent account')
                : reauthenticationRequired
                ? '저장된 인증 정보를 내보내지 않고 시스템 브라우저에서 다시 로그인합니다.'
                : '비밀번호는 앱에 입력하지 않습니다. Android와 iOS의 시스템 인증 세션에서 로그인합니다.',
            style: TextStyle(
              color: _paper.withValues(alpha: .72),
              fontSize: 15,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 24),
          if (authenticated)
            OutlinedButton.icon(
              key: const Key('sign_out_button'),
              onPressed: controller.signOut,
              icon: const Icon(Icons.logout_rounded),
              label: const Text('로그아웃'),
              style: OutlinedButton.styleFrom(
                foregroundColor: _paper,
                side: BorderSide(color: _paper.withValues(alpha: .35)),
                minimumSize: const Size.fromHeight(52),
              ),
            )
          else if (authorizing)
            Row(
              children: [
                const Expanded(
                  child: LinearProgressIndicator(
                    minHeight: 6,
                    color: _acid,
                    backgroundColor: _slate,
                  ),
                ),
                const SizedBox(width: 14),
                TextButton(
                  key: const Key('cancel_sign_in_button'),
                  onPressed: controller.cancelSignIn,
                  child: const Text('취소', style: TextStyle(color: _paper)),
                ),
              ],
            )
          else
            FilledButton.icon(
              key: const Key('oauth_sign_in_button'),
              onPressed: configured ? controller.signIn : null,
              icon: const Icon(Icons.open_in_browser_rounded),
              label: const Text(
                'OAuth 로그인 페이지 열기',
                style: TextStyle(fontWeight: FontWeight.w800),
              ),
              style: FilledButton.styleFrom(
                backgroundColor: _acid,
                foregroundColor: _ink,
                disabledBackgroundColor: _slate,
                disabledForegroundColor: _paper.withValues(alpha: .45),
              ),
            ),
        ],
      ),
    );
  }
}

class _ConfigurationNotice extends StatelessWidget {
  const _ConfigurationNotice();

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('oauth_configuration_notice'),
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: const Color(0xFFFFE7A6),
      border: Border.all(color: _ink, width: 1.2),
      borderRadius: BorderRadius.circular(14),
    ),
    child: const Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.settings_ethernet_rounded, size: 20),
        SizedBox(width: 10),
        Expanded(
          child: Text(
            '실 OAuth 설정이 필요합니다. OIDC_ISSUER와 OIDC_CLIENT_ID를 '
            '--dart-define으로 전달하면 위 버튼이 활성화됩니다.',
            style: TextStyle(
              fontSize: 13,
              height: 1.45,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    ),
  );
}

class _ErrorNotice extends StatelessWidget {
  const _ErrorNotice({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      key: const Key('oauth_error_notice'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFFFD7D2),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(message, style: const TextStyle(fontWeight: FontWeight.w700)),
    ),
  );
}

class _ProviderGrid extends StatelessWidget {
  const _ProviderGrid();

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      const providers = [
        ('CX', 'Codex', 'OpenAI Responses', Color(0xFF202420)),
        ('CL', 'Claude', 'Anthropic Messages', Color(0xFFCC785C)),
        ('GR', 'Grok', 'xAI Inference', Color(0xFF2758F2)),
      ];
      final width = constraints.maxWidth >= 700
          ? (constraints.maxWidth - 24) / 3
          : constraints.maxWidth;
      return Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          for (final provider in providers)
            SizedBox(
              width: width,
              child: _ProviderCard(
                monogram: provider.$1,
                name: provider.$2,
                protocol: provider.$3,
                color: provider.$4,
              ),
            ),
        ],
      );
    },
  );
}

class _ProviderCard extends StatelessWidget {
  const _ProviderCard({
    required this.monogram,
    required this.name,
    required this.protocol,
    required this.color,
  });

  final String monogram;
  final String name;
  final String protocol;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .58),
      border: Border.all(color: _ink.withValues(alpha: .14)),
      borderRadius: BorderRadius.circular(16),
    ),
    child: Row(
      children: [
        Container(
          width: 42,
          height: 42,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          child: Text(
            monogram,
            style: const TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w900,
              fontSize: 12,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name, style: const TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 2),
              Text(
                protocol,
                style: TextStyle(
                  color: _ink.withValues(alpha: .58),
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
        Icon(
          Icons.arrow_outward_rounded,
          color: _ink.withValues(alpha: .35),
          size: 19,
        ),
      ],
    ),
  );
}

class _SecurityFootnote extends StatelessWidget {
  const _SecurityFootnote();

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Icon(Icons.shield_outlined, size: 18),
      const SizedBox(width: 9),
      Expanded(
        child: Text(
          'Refresh credential은 Android Keystore 또는 iOS Keychain에 저장됩니다. '
          'Provider API key는 앱이 아니라 MobileAgent BFF에서만 관리합니다.',
          style: TextStyle(
            color: _ink.withValues(alpha: .65),
            fontSize: 12,
            height: 1.45,
          ),
        ),
      ),
    ],
  );
}

class _ChatWorkspace extends StatefulWidget {
  const _ChatWorkspace({required this.environment});

  final MobileAgentEnvironment environment;

  @override
  State<_ChatWorkspace> createState() => _ChatWorkspaceState();
}

class _ChatWorkspaceState extends State<_ChatWorkspace>
    with WidgetsBindingObserver {
  late final TextEditingController promptController;
  late final TextEditingController conversationSearchController;
  ChatController? chatController;
  LlmProvider provider = LlmProvider.openai;
  String conversationQuery = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    promptController = TextEditingController();
    conversationSearchController = TextEditingController();
    if (widget.environment.hasBffConfiguration) {
      chatController = ChatController(
        NativeLlmTransport(
          bffBaseUrl: Uri.parse(widget.environment.bffBaseUrl),
        ),
        conversationStore: NativeConversationStore(),
      );
      unawaited(chatController!.initialize());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    promptController.dispose();
    conversationSearchController.dispose();
    chatController?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(chatController?.reconcileAfterResume());
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = chatController;
    if (controller == null) {
      return const _WorkspaceNotice(
        message: 'BFF_BASE_URL을 HTTPS 주소로 설정하면 실제 LLM Run Card가 활성화됩니다.',
      );
    }
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final model = widget.environment.modelFor(provider.name);
        final streaming = controller.state == ChatRunState.streaming;
        final cancelling = controller.state == ChatRunState.cancelling;
        final active = streaming || cancelling;
        return Container(
          key: const Key('llm_workspace'),
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: .72),
            border: Border.all(color: _ink.withValues(alpha: .16)),
            borderRadius: BorderRadius.circular(18),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      'LIVE RUN',
                      style: TextStyle(
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.4,
                      ),
                    ),
                  ),
                  Container(
                    width: 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: active ? _acid : _ink.withValues(alpha: .25),
                      shape: BoxShape.circle,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              _ConversationPanel(
                controller: controller,
                searchController: conversationSearchController,
                query: conversationQuery,
                active: active,
                onQueryChanged: (value) =>
                    setState(() => conversationQuery = value),
                onNewConversation: controller.newConversation,
                onSelected: (conversation) {
                  controller.selectConversation(conversation.id);
                  setState(() {
                    provider = LlmProvider.values.byName(conversation.provider);
                    promptController.clear();
                  });
                },
                onDelete: controller.deleteConversation,
                onClearAll: controller.clearAllConversations,
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<LlmProvider>(
                key: const Key('provider_selector'),
                initialValue: provider,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Provider',
                  border: OutlineInputBorder(),
                ),
                items: const [
                  DropdownMenuItem(
                    value: LlmProvider.openai,
                    child: Text('Codex · OpenAI'),
                  ),
                  DropdownMenuItem(
                    value: LlmProvider.anthropic,
                    child: Text('Claude · Anthropic'),
                  ),
                  DropdownMenuItem(
                    value: LlmProvider.xai,
                    child: Text('Grok · xAI'),
                  ),
                ],
                onChanged: active
                    ? null
                    : (value) => setState(() => provider = value ?? provider),
              ),
              const SizedBox(height: 10),
              Text(
                model.isEmpty ? '모델 설정 필요' : model,
                key: const Key('selected_model'),
                style: TextStyle(
                  color: model.isEmpty
                      ? Colors.red.shade700
                      : _ink.withValues(alpha: .64),
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('prompt_input'),
                controller: promptController,
                minLines: 3,
                maxLines: 7,
                enabled: !active,
                decoration: const InputDecoration(
                  hintText: '외부 LLM에 보낼 요청을 입력하세요.',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              if (active)
                OutlinedButton.icon(
                  key: const Key('stop_llm_button'),
                  onPressed: cancelling ? null : controller.stop,
                  icon: const Icon(Icons.stop_circle_outlined),
                  label: Text(cancelling ? '중단 확인 중…' : 'Stop'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                  ),
                )
              else
                FilledButton.icon(
                  key: const Key('send_llm_button'),
                  onPressed: model.isEmpty
                      ? null
                      : () => controller.send(
                          provider: provider,
                          model: model,
                          prompt: promptController.text,
                        ),
                  icon: const Icon(Icons.arrow_upward_rounded),
                  label: const Text('Run external LLM'),
                ),
              if ((controller.activeConversation?.messages.isNotEmpty ??
                      false) ||
                  (active && controller.responseText.isNotEmpty) ||
                  controller.errorMessage != null) ...[
                const SizedBox(height: 14),
                _ConversationTranscript(
                  messages: controller.activeConversation?.messages ?? const [],
                  liveAssistantText: active ? controller.responseText : null,
                  errorMessage: controller.errorMessage,
                ),
              ],
              if (controller.statusMessage case final status?) ...[
                const SizedBox(height: 10),
                Semantics(
                  liveRegion: true,
                  child: Text(
                    status,
                    key: const Key('llm_status_message'),
                    style: TextStyle(
                      color: _ink.withValues(alpha: .72),
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}

class _ConversationPanel extends StatelessWidget {
  const _ConversationPanel({
    required this.controller,
    required this.searchController,
    required this.query,
    required this.active,
    required this.onQueryChanged,
    required this.onNewConversation,
    required this.onSelected,
    required this.onDelete,
    required this.onClearAll,
  });

  final ChatController controller;
  final TextEditingController searchController;
  final String query;
  final bool active;
  final ValueChanged<String> onQueryChanged;
  final VoidCallback onNewConversation;
  final ValueChanged<ConversationRecord> onSelected;
  final Future<void> Function(String) onDelete;
  final Future<void> Function() onClearAll;

  @override
  Widget build(BuildContext context) {
    final conversations = controller.searchConversations(query);
    return Semantics(
      container: true,
      label: '이 기기에 저장된 대화',
      child: Container(
        key: const Key('conversation_panel'),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFE7EEE2),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: _ink.withValues(alpha: .12)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'LOCAL CONVERSATIONS',
              style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.1),
            ),
            const SizedBox(height: 4),
            Text(
              '이 기기의 암호화 저장소에만 보관됩니다. Provider·BFF·계정 데이터는 삭제하지 않습니다.',
              style: TextStyle(
                color: _ink.withValues(alpha: .72),
                fontSize: 12,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('conversation_search'),
              controller: searchController,
              enabled: !active && !controller.conversationsRestoring,
              onChanged: onQueryChanged,
              decoration: const InputDecoration(
                isDense: true,
                prefixIcon: Icon(Icons.search_rounded),
                hintText: '대화 검색',
                border: OutlineInputBorder(),
              ),
            ),
            if (controller.conversationErrorMessage case final message?) ...[
              const SizedBox(height: 10),
              Semantics(
                liveRegion: true,
                child: Text(
                  message,
                  key: const Key('conversation_storage_error'),
                  style: const TextStyle(
                    color: Color(0xFF9A1B12),
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  key: const Key('new_conversation_button'),
                  onPressed: active || controller.conversationsRestoring
                      ? null
                      : onNewConversation,
                  icon: const Icon(Icons.add_comment_outlined),
                  label: const Text('새 대화'),
                ),
                TextButton.icon(
                  key: const Key('clear_conversations_button'),
                  onPressed:
                      active ||
                          controller.conversationsRestoring ||
                          controller.conversations.isEmpty
                      ? null
                      : () => _confirmClear(context),
                  icon: const Icon(Icons.delete_sweep_outlined),
                  label: const Text('이 기기 대화 삭제'),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (controller.conversationsRestoring)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(8),
                  child: CircularProgressIndicator(),
                ),
              )
            else if (conversations.isEmpty)
              Text(
                query.trim().isEmpty ? '저장된 대화가 없습니다.' : '검색 결과가 없습니다.',
                style: TextStyle(color: _ink.withValues(alpha: .66)),
              )
            else
              for (final conversation in conversations)
                _ConversationListItem(
                  conversation: conversation,
                  selected: conversation.id == controller.activeConversationId,
                  disabled: active,
                  onSelect: () => onSelected(conversation),
                  onDelete: () => _confirmDelete(context, conversation),
                ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmDelete(
    BuildContext context,
    ConversationRecord conversation,
  ) async {
    final accepted = await _confirm(
      context,
      title: '이 기기의 대화를 삭제할까요?',
      body:
          '"${conversation.title}"의 암호화 로컬 사본만 삭제합니다. Provider·BFF·계정 데이터는 삭제되지 않습니다.',
      confirmLabel: '이 기기에서 삭제',
    );
    if (accepted) await onDelete(conversation.id);
  }

  Future<void> _confirmClear(BuildContext context) async {
    final accepted = await _confirm(
      context,
      title: '모든 로컬 대화를 삭제할까요?',
      body: '이 기기의 암호화 대화 사본만 삭제합니다. 서버와 Provider에 전송된 데이터는 별도 정책을 따릅니다.',
      confirmLabel: '모두 삭제',
    );
    if (accepted) await onClearAll();
  }

  Future<bool> _confirm(
    BuildContext context, {
    required String title,
    required String body,
    required String confirmLabel,
  }) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(body),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(confirmLabel),
            ),
          ],
        ),
      ) ??
      false;
}

class _ConversationListItem extends StatelessWidget {
  const _ConversationListItem({
    required this.conversation,
    required this.selected,
    required this.disabled,
    required this.onSelect,
    required this.onDelete,
  });

  final ConversationRecord conversation;
  final bool selected;
  final bool disabled;
  final VoidCallback onSelect;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(top: 8),
    decoration: BoxDecoration(
      color: selected ? _ink : Colors.white.withValues(alpha: .65),
      borderRadius: BorderRadius.circular(10),
    ),
    child: ListTile(
      dense: true,
      enabled: !disabled,
      onTap: disabled ? null : onSelect,
      title: Text(
        conversation.title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: selected ? _paper : _ink,
          fontWeight: FontWeight.w800,
        ),
      ),
      subtitle: Text(
        '${conversation.provider} · ${conversation.messages.length} messages',
        style: TextStyle(
          color: selected
              ? _paper.withValues(alpha: .75)
              : _ink.withValues(alpha: .62),
        ),
      ),
      trailing: IconButton(
        key: Key('delete_conversation_${conversation.id}'),
        tooltip: '이 기기의 대화 삭제',
        onPressed: disabled ? null : onDelete,
        icon: Icon(
          Icons.delete_outline_rounded,
          color: selected ? _paper : _ink,
        ),
      ),
    ),
  );
}

class _ConversationTranscript extends StatelessWidget {
  const _ConversationTranscript({
    required this.messages,
    required this.liveAssistantText,
    required this.errorMessage,
  });

  final List<ConversationMessage> messages;
  final String? liveAssistantText;
  final String? errorMessage;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: liveAssistantText?.isNotEmpty ?? false,
    child: Column(
      key: const Key('conversation_transcript'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final message in messages)
          _ConversationBubble(message: message, isLive: false),
        if (liveAssistantText case final text? when text.isNotEmpty)
          _ConversationBubble(
            message: ConversationMessage(
              role: ConversationRole.assistant,
              content: text,
              createdAt: DateTime.now().toUtc(),
            ),
            isLive: true,
          ),
        if (errorMessage case final message?)
          Container(
            key: const Key('llm_response'),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: const Color(0xFFFFD7D2),
              borderRadius: BorderRadius.circular(12),
            ),
            child: SelectableText(
              message,
              style: const TextStyle(
                color: Color(0xFF9A1B12),
                height: 1.45,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
      ],
    ),
  );
}

class _ConversationBubble extends StatelessWidget {
  const _ConversationBubble({required this.message, required this.isLive});

  final ConversationMessage message;
  final bool isLive;

  @override
  Widget build(BuildContext context) {
    final user = message.role == ConversationRole.user;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: user ? _ink : const Color(0xFFFFFFFF),
        border: Border.all(color: _ink.withValues(alpha: user ? 0 : .12)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: SelectableText(
        message.content,
        style: TextStyle(
          color: user ? _paper : _ink,
          height: 1.45,
          fontWeight: isLive ? FontWeight.w700 : FontWeight.w500,
        ),
      ),
    );
  }
}

class _WorkspaceNotice extends StatelessWidget {
  const _WorkspaceNotice({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: const Color(0xFFDDE7FF),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Text(message, style: const TextStyle(fontWeight: FontWeight.w700)),
  );
}
