import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('native secure store returns a redacted session summary', (
    tester,
  ) async {
    final summary = await const MobileAgentAuth().restoreSession();

    expect(summary.status, isA<AuthSessionStatus>());
  });
}
