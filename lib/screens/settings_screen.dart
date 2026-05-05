import 'package:flutter/material.dart';
import '../services/prefs_service.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  int _countdown = 5;

  @override
  void initState() {
    super.initState();
    PrefsService.loadCountdown().then((v) {
      if (mounted) setState(() => _countdown = v);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text('Einstellungen'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'Pause-Dauer',
            style: TextStyle(
                color: Colors.white70,
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 1),
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: const Color(0xFF1A1A24),
              borderRadius: BorderRadius.circular(16),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text('Sekunden vor dem Öffnen',
                        style: TextStyle(color: Colors.white)),
                    Text(
                      '$_countdown s',
                      style: const TextStyle(
                          color: Color(0xFF6C63FF),
                          fontWeight: FontWeight.bold,
                          fontSize: 18),
                    ),
                  ],
                ),
                Slider(
                  value: _countdown.toDouble(),
                  min: 1,
                  max: 30,
                  divisions: 29,
                  activeColor: const Color(0xFF6C63FF),
                  onChanged: (v) async {
                    final secs = v.round();
                    setState(() => _countdown = secs);
                    await PrefsService.saveCountdown(secs);
                  },
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('1s', style: TextStyle(color: Colors.white.withOpacity(0.4), fontSize: 12)),
                    Text('30s', style: TextStyle(color: Colors.white.withOpacity(0.4), fontSize: 12)),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          const Text(
            'Über die App',
            style: TextStyle(
                color: Colors.white70,
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 1),
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: const Color(0xFF1A1A24),
              borderRadius: BorderRadius.circular(16),
            ),
            padding: const EdgeInsets.all(20),
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('one sec', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
                SizedBox(height: 8),
                Text(
                  'Erzeugt eine kleine Pause, bevor du ablenkende Apps öffnest – damit du bewusster entscheidest.',
                  style: TextStyle(color: Colors.white60, height: 1.5),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
