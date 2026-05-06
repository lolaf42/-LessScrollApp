import 'package:flutter/material.dart';
import 'screens/home_screen.dart';

void main() {
  runApp(const LessScroll());
}

class LessScroll extends StatelessWidget {
  const LessScroll({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'LessScroll',
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
