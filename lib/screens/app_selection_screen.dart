import 'package:flutter/material.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:installed_apps/app_info.dart';
import '../models/blocked_app.dart';
import '../services/prefs_service.dart';

class AppSelectionScreen extends StatefulWidget {
  const AppSelectionScreen({super.key});

  @override
  State<AppSelectionScreen> createState() => _AppSelectionScreenState();
}

class _AppSelectionScreenState extends State<AppSelectionScreen> {
  List<AppInfo> _allApps = [];
  Set<String> _blockedPackages = {};
  bool _loading = true;
  String _search = '';

  @override
  void initState() {
    super.initState();
    _loadApps();
  }

  Future<void> _loadApps() async {
    final apps = await InstalledApps.getInstalledApps(true, true);
    final blocked = await PrefsService.loadBlockedPackages();
    apps.sort((a, b) => (a.name ?? '').compareTo(b.name ?? ''));
    if (!mounted) return;
    setState(() {
      _allApps = apps;
      _blockedPackages = blocked;
      _loading = false;
    });
  }

  Future<void> _toggleApp(AppInfo app) async {
    final pkg = app.packageName ?? '';
    if (pkg.isEmpty) return;
    final current = await PrefsService.loadBlockedApps();
    List<BlockedApp> updated;
    if (_blockedPackages.contains(pkg)) {
      updated = current.where((a) => a.packageName != pkg).toList();
    } else {
      updated = [...current, BlockedApp(packageName: pkg, appName: app.name ?? pkg)];
    }
    await PrefsService.saveBlockedApps(updated);
    setState(() {
      if (_blockedPackages.contains(pkg)) {
        _blockedPackages.remove(pkg);
      } else {
        _blockedPackages.add(pkg);
      }
    });
  }

  List<AppInfo> get _filtered {
    if (_search.isEmpty) return _allApps;
    final q = _search.toLowerCase();
    return _allApps
        .where((a) =>
            (a.name ?? '').toLowerCase().contains(q) ||
            (a.packageName ?? '').toLowerCase().contains(q))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text('Apps auswählen'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              onChanged: (v) => setState(() => _search = v),
              decoration: InputDecoration(
                hintText: 'App suchen…',
                prefixIcon: const Icon(Icons.search),
                filled: true,
                fillColor: const Color(0xFF1A1A24),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : ListView.builder(
                    itemCount: _filtered.length,
                    itemBuilder: (ctx, i) {
                      final app = _filtered[i];
                      final pkg = app.packageName ?? '';
                      final isBlocked = _blockedPackages.contains(pkg);
                      return ListTile(
                        leading: app.icon != null
                            ? Image.memory(app.icon!, width: 40, height: 40)
                            : const CircleAvatar(
                                child: Icon(Icons.android)),
                        title: Text(app.name ?? pkg),
                        subtitle: Text(
                          pkg,
                          style: const TextStyle(
                              fontSize: 11, color: Colors.white38),
                        ),
                        trailing: Checkbox(
                          value: isBlocked,
                          activeColor: const Color(0xFF6C63FF),
                          onChanged: (_) => _toggleApp(app),
                        ),
                        onTap: () => _toggleApp(app),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
