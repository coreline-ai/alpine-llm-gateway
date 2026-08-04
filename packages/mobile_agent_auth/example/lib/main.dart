import 'package:flutter/material.dart';
import 'package:mobile_agent_auth/mobile_agent_auth.dart';

void main() => runApp(const AuthPluginExample());

class AuthPluginExample extends StatefulWidget {
  const AuthPluginExample({super.key});

  @override
  State<AuthPluginExample> createState() => _AuthPluginExampleState();
}

class _AuthPluginExampleState extends State<AuthPluginExample> {
  String status = 'restoring';

  @override
  void initState() {
    super.initState();
    restore();
  }

  Future<void> restore() async {
    try {
      final session = await const MobileAgentAuth().restoreSession();
      if (mounted) setState(() => status = session.status.name);
    } on MobileAgentAuthException catch (error) {
      if (mounted) setState(() => status = error.code);
    }
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      appBar: AppBar(title: const Text('MobileAgent Auth Plugin')),
      body: Center(child: Text('Auth status: $status')),
    ),
  );
}
