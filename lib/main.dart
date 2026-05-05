import 'package:flutter/material.dart';
import 'screens/home_screen.dart';

void main() {
  runApp(const OneSec());
}

class OneSec extends StatelessWidget {
  const OneSec({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'one sec',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFF0F0F14),
      ),
      home: const HomeScreen(),
    );
  }
}
