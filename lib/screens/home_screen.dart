import 'package:flutter/material.dart';
import '../services/service_channel.dart';
import '../services/prefs_service.dart';
import '../models/blocked_app.dart';
import 'app_selection_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  bool _serviceRunning = false;
  bool _hasUsagePerm = false;
  bool _hasOverlayPerm = false;
  List<BlockedApp> _blockedApps = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final running = await ServiceChannel.isServiceRunning();
    final usage = await ServiceChannel.hasUsageStatsPermission();
    final overlay = await ServiceChannel.hasOverlayPermission();
    final apps = await PrefsService.loadBlockedApps();

    // Auto-start service if permissions ok and was previously active
    if (!running && usage && overlay && await PrefsService.wasServiceActive()) {
      await ServiceChannel.startService();
    }

    if (!mounted) return;
    setState(() {
      _serviceRunning = running;
      _hasUsagePerm = usage;
      _hasOverlayPerm = overlay;
      _blockedApps = apps;
    });
  }

  Future<void> _toggleService() async {
    if (!_hasUsagePerm) {
      await ServiceChannel.requestUsageStatsPermission();
      return;
    }
    if (!_hasOverlayPerm) {
      await ServiceChannel.requestOverlayPermission();
      return;
    }
    if (_serviceRunning) {
      await ServiceChannel.stopService();
    } else {
      await ServiceChannel.startService();
    }
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final allPermsOk = _hasUsagePerm && _hasOverlayPerm;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text(
          'LessScroll',
          style: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 1),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: () async {
              await Navigator.push(context,
                  MaterialPageRoute(builder: (_) => const SettingsScreen()));
              _refresh();
            },
          )
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _buildStatusCard(allPermsOk),
            const SizedBox(height: 24),
            if (!_hasUsagePerm || !_hasOverlayPerm) ...[
              _buildPermissionBanner(),
              const SizedBox(height: 24),
            ],
            _buildBlockedAppsSection(),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusCard(bool allPermsOk) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: _serviceRunning
              ? [const Color(0xFF6C63FF), const Color(0xFF3B82F6)]
              : [const Color(0xFF2A2A35), const Color(0xFF1E1E28)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                _serviceRunning ? Icons.shield : Icons.shield_outlined,
                color: Colors.white,
                size: 28,
              ),
              const SizedBox(width: 12),
              Text(
                _serviceRunning ? 'Aktiv' : 'Inaktiv',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const Spacer(),
              Switch(
                value: _serviceRunning,
                onChanged: (_) => _toggleService(),
                activeColor: Colors.white,
                activeTrackColor: Colors.white30,
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _serviceRunning
                ? 'LessScroll überwacht ${_blockedApps.length} App${_blockedApps.length == 1 ? '' : 's'}'
                : allPermsOk
                    ? 'Schalte ein, um Apps zu pausieren'
                    : 'Berechtigungen fehlen',
            style: TextStyle(
              color: Colors.white.withOpacity(0.8),
              fontSize: 14,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionBanner() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF2A1F10),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.orange.withOpacity(0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.warning_amber_rounded, color: Colors.orange, size: 20),
              SizedBox(width: 8),
              Text(
                'Berechtigungen benötigt',
                style: TextStyle(
                    color: Colors.orange, fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (!_hasUsagePerm)
            _permRow('Nutzungsstatistiken', () async {
              await ServiceChannel.requestUsageStatsPermission();
            }),
          if (!_hasOverlayPerm)
            _permRow('Über anderen Apps anzeigen', () async {
              await ServiceChannel.requestOverlayPermission();
            }),
        ],
      ),
    );
  }

  Widget _permRow(String label, VoidCallback onTap) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        children: [
          const Icon(Icons.close, color: Colors.red, size: 16),
          const SizedBox(width: 8),
          Expanded(child: Text(label, style: const TextStyle(color: Colors.white70))),
          TextButton(
            onPressed: onTap,
            child: const Text('Erlauben'),
          ),
        ],
      ),
    );
  }

  Widget _buildBlockedAppsSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Text(
              'Überwachte Apps',
              style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Colors.white),
            ),
            const Spacer(),
            IconButton(
              icon: const Icon(Icons.add_circle_outline, color: Color(0xFF6C63FF)),
              onPressed: () async {
                await Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (_) => const AppSelectionScreen()));
                _refresh();
              },
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_blockedApps.isEmpty)
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 40),
              child: Column(
                children: [
                  Icon(Icons.apps, size: 56, color: Colors.white.withOpacity(0.2)),
                  const SizedBox(height: 16),
                  Text(
                    'Noch keine Apps ausgewählt',
                    style: TextStyle(color: Colors.white.withOpacity(0.4)),
                  ),
                  const SizedBox(height: 8),
                  TextButton.icon(
                    onPressed: () async {
                      await Navigator.push(
                          context,
                          MaterialPageRoute(
                              builder: (_) => const AppSelectionScreen()));
                      _refresh();
                    },
                    icon: const Icon(Icons.add),
                    label: const Text('Apps hinzufügen'),
                  ),
                ],
              ),
            ),
          )
        else
          ..._blockedApps.map((app) => _buildAppTile(app)),
      ],
    );
  }

  Widget _buildAppTile(BlockedApp app) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: const Color(0xFF1A1A24),
        borderRadius: BorderRadius.circular(12),
      ),
      child: ListTile(
        leading: const CircleAvatar(
          backgroundColor: Color(0xFF2A2A38),
          child: Icon(Icons.android, color: Color(0xFF6C63FF)),
        ),
        title: Text(app.appName, style: const TextStyle(color: Colors.white)),
        subtitle: Text(
          app.packageName,
          style: const TextStyle(color: Colors.white38, fontSize: 12),
        ),
        trailing: IconButton(
          icon: const Icon(Icons.remove_circle_outline, color: Colors.red),
          onPressed: () async {
            final updated =
                _blockedApps.where((a) => a.packageName != app.packageName).toList();
            await PrefsService.saveBlockedApps(updated);
            _refresh();
          },
        ),
      ),
    );
  }
}
