package wtf.riedel.onesec

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

class AppMonitorService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "onesec_monitor"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "FlutterSharedPreferences"
        const val BLOCKED_KEY = "flutter.blocked_apps"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentOverlayPkg = ""
    private var timer: Timer? = null
    private var lastEventTime = 0L
    private var lastForegroundPkg = ""
    @Volatile private var overlayShowing = false
    private val lastLeftPkgTime = mutableMapOf<String, Long>()
    private val GRACE_PERIOD_MS = 15_000L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pauseMonitoring()
                Intent.ACTION_SCREEN_ON  -> resumeMonitoring()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        timer?.cancel()
        dismissOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun pauseMonitoring() {
        timer?.cancel()
        timer = null
        dismissOverlay()
    }

    private fun resumeMonitoring() {
        if (timer == null) {
            lastForegroundPkg = ""
            lastEventTime = System.currentTimeMillis()
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        lastEventTime = System.currentTimeMillis()
        lastForegroundPkg = detectCurrentApp() ?: ""
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { checkForeground() }
        }, 500, 500)
    }

    private fun checkForeground() {
        val now = System.currentTimeMillis()
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Primary: event stream – fast and precise on stock Android
        val events = usm.queryEvents(lastEventTime, now)
        lastEventTime = now
        val event = UsageEvents.Event()
        var detectedPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.packageName != packageName) {
                detectedPkg = event.packageName
            }
        }

        // Fallback: usage stats – covers custom ROMs and delayed event reporting.
        // Only use if lastTimeUsed is very recent (<800ms) to avoid picking up
        // background services (e.g. WhatsApp) that update lastTimeUsed passively.
        if (detectedPkg == null) {
            detectedPkg = usm
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 2000, now)
                ?.maxByOrNull { it.lastTimeUsed }
                ?.takeIf { it.packageName != packageName && (now - it.lastTimeUsed) < 800 }
                ?.packageName
        }

        val pkg = detectedPkg ?: return
        if (pkg == lastForegroundPkg) return

        val blocked = loadBlockedPackages()

        // When leaving a blocked app, record the time for the grace-period check
        if (blocked.contains(lastForegroundPkg)) {
            lastLeftPkgTime[lastForegroundPkg] = now
        }

        lastForegroundPkg = pkg

        if (blocked.contains(pkg) && !overlayShowing) {
            val lastLeft = lastLeftPkgTime[pkg] ?: 0L
            if (now - lastLeft < GRACE_PERIOD_MS) return  // returned within 15s (e.g. via multitasking)
            Handler(Looper.getMainLooper()).post { showOverlay(pkg) }
        } else if (!blocked.contains(pkg) && overlayShowing) {
            Handler(Looper.getMainLooper()).post { dismissOverlay() }
        }
    }

    private fun detectCurrentApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10000, now)
            ?.maxByOrNull { it.lastTimeUsed }
            ?.takeIf { it.packageName != packageName }
            ?.packageName
    }

    private fun getOpenCount(packageName: String): Int {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 24 * 60 * 60 * 1000L, now)
            var count = 0
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == packageName &&
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) count++
            }
            count
        } catch (_: Exception) { 0 }
    }

    private fun loadBlockedPackages(): Set<String> {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(BLOCKED_KEY, null) ?: return emptySet()
        // Flutter stores List<String> with a base64 type-prefix followed by '!'
        val json = if (raw.contains('!')) raw.substringAfter('!') else raw
        return json.trim().removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(packageName: String) {
        if (overlayShowing) return
        overlayShowing = true

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = buildOverlayView(packageName)
        overlayView = view
        currentOverlayPkg = packageName
        windowManager?.addView(view, params)
    }

    private fun dismissOverlay() {
        overlayShowing = false
        if (currentOverlayPkg.isNotEmpty()) {
            lastLeftPkgTime[currentOverlayPkg] = System.currentTimeMillis()
            currentOverlayPkg = ""
        }
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    // ── Overlay View ──────────────────────────────────────────────────────────

    private fun buildOverlayView(packageName: String): View {
        val appName = getAppName(packageName)
        val openCount = getOpenCount(packageName)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(242, 8, 8, 12))

        val introText = TextView(this).apply {
            text = "Es ist Zeit für einen\ntiefen Atemzug...."
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(introText)

        val waveView = WaveView(this)
        root.addView(waveView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val infoCard = buildInfoCard(appName, openCount, packageName)
        infoCard.alpha = 0f
        root.addView(infoCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                startBreathingSequence(waveView, introText, infoCard)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        return root
    }

    private fun startBreathingSequence(waveView: WaveView, introText: View, infoCard: View) {
        val sineInterp = PathInterpolator(0.37f, 0f, 0.63f, 1f)

        // Phase 3: Exhale – 4.5s descent; info card fades in when done
        val exhale = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 4500
            interpolator = sineInterp
            addUpdateListener { waveView.fillRatio = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    infoCard.animate().alpha(1f).setDuration(600).start()
                    overlayView?.let {
                        (it.layoutParams as? WindowManager.LayoutParams)?.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        try { windowManager?.updateViewLayout(it, it.layoutParams) } catch (_: Exception) {}
                    }
                }
            })
        }

        // Phase 1: Inhale – 3.5s rise covering the intro text
        // Phase 2: Hold at top 1.2s, fade out intro text, then start exhale
        val inhale = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3500
            interpolator = sineInterp
            addUpdateListener { waveView.fillRatio = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    introText.animate().alpha(0f).setDuration(400).start()
                    Handler(Looper.getMainLooper()).postDelayed({ exhale.start() }, 1200)
                }
            })
        }

        inhale.start()
    }

    private fun buildInfoCard(appName: String, openCount: Int, packageName: String): View {
        val root = FrameLayout(this).apply {
            setPadding(dpToPx(32), dpToPx(48), dpToPx(32), dpToPx(64))
        }

        // Center: large count number + description text
        val centerGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        centerGroup.addView(TextView(this).apply {
            text = openCount.toString()
            textSize = 88f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        centerGroup.addView(TextView(this).apply {
            text = "attempts to open $appName within the\nlast 24 hours."
            textSize = 16f
            setTextColor(Color.argb(210, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12) }
        })

        root.addView(centerGroup)

        // Bottom: pill button + text link
        val bottomGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        bottomGroup.addView(TextView(this).apply {
            text = "I don't want to open $appName"
            textSize = 16f
            setTextColor(Color.argb(255, 35, 25, 65))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 185, 170, 228))
                cornerRadius = dpToPx(32).toFloat()
            }
            setPadding(dpToPx(24), dpToPx(18), dpToPx(24), dpToPx(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(60)
            )
            setOnClickListener {
                dismissOverlay()
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        })

        bottomGroup.addView(TextView(this).apply {
            text = "Continue on $appName"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
            setOnClickListener { dismissOverlay() }
        })

        root.addView(bottomGroup)
        return root
    }

    private fun getAppName(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) { packageName }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ── WaveView ──────────────────────────────────────────────────────────────

    inner class WaveView(context: Context) : View(context) {

        var fillRatio: Float = 0f
            set(value) { field = value; invalidate() }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(
                    Color.argb(220, 100, 180, 255),  // light blue top
                    Color.argb(200, 60,  120, 220),  // mid blue
                    Color.argb(180, 80,  60,  200)   // purple bottom
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }


        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.argb(220, 120, 200, 255),
                    Color.argb(210, 60,  130, 230),
                    Color.argb(190, 80,  60,  210)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (fillRatio <= 0f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val fillTop = h * (1f - fillRatio)
            canvas.drawRect(0f, fillTop, w, h, fillPaint)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "App-Überwachung", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "one sec läuft im Hintergrund" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("one sec")
            .setContentText("Überwacht deine Apps")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .build()
    }
}
