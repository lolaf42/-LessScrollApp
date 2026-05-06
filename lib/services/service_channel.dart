import 'package:flutter/services.dart';

class ServiceChannel {
  static const _channel = MethodChannel('lessscroll/service');

  static Future<void> startService() => _channel.invokeMethod('startService');
  static Future<void> stopService() => _channel.invokeMethod('stopService');
  static Future<bool> isServiceRunning() async =>
      await _channel.invokeMethod<bool>('isServiceRunning') ?? false;
  static Future<bool> hasUsageStatsPermission() async =>
      await _channel.invokeMethod<bool>('hasUsageStatsPermission') ?? false;
  static Future<bool> hasOverlayPermission() async =>
      await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
  static Future<void> requestUsageStatsPermission() =>
      _channel.invokeMethod('requestUsageStatsPermission');
  static Future<void> requestOverlayPermission() =>
      _channel.invokeMethod('requestOverlayPermission');
}
