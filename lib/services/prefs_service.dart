import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/blocked_app.dart';

class PrefsService {
  static const _blockedKey = 'blocked_apps';
  static const _countdownKey = 'countdown_seconds';

  static Future<Set<String>> loadBlockedPackages() async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_blockedKey) ?? [];
    return list.toSet();
  }

  static Future<List<BlockedApp>> loadBlockedApps() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('${_blockedKey}_meta') ?? '[]';
    final list = jsonDecode(raw) as List;
    return list.map((e) => BlockedApp.fromJson(e as Map<String, dynamic>)).toList();
  }

  static Future<void> saveBlockedApps(List<BlockedApp> apps) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_blockedKey, apps.map((a) => a.packageName).toList());
    await prefs.setString('${_blockedKey}_meta', jsonEncode(apps.map((a) => a.toJson()).toList()));
  }

  static Future<int> loadCountdown() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_countdownKey) ?? 5;
  }

  static Future<void> saveCountdown(int seconds) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_countdownKey, seconds);
  }

  static Future<bool> wasServiceActive() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool('service_active') ?? false;
  }
}
