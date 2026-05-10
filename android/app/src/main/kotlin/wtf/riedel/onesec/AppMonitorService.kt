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
        const val COUNTDOWN_KEY = "flutter.countdown_seconds"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var timer: Timer? = null
    private var lastEventTime = 0L
    private var lastForegroundPkg = ""
    @Volatile private var overlayShowing = false

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
        lastForegroundPkg = pkg

        val blocked = loadBlockedPackages()
        if (blocked.contains(pkg) && !overlayShowing) {
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

    private fun loadCountdownSeconds(): Int {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Flutter stores int/double values as Long in SharedPreferences
        return try { prefs.getLong(COUNTDOWN_KEY, 5L).toInt() } catch (_: Exception) { prefs.getInt(COUNTDOWN_KEY, 5) }
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
        windowManager?.addView(view, params)
    }

    private fun dismissOverlay() {
        overlayShowing = false
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        // Reset so the same app triggers the overlay again on next open
        lastForegroundPkg = ""
    }

    // ── Overlay View ──────────────────────────────────────────────────────────

    private fun buildOverlayView(packageName: String): View {
        val appName = getAppName(packageName)
        val openCount = getOpenCount(packageName)
        val countdownSecs = loadCountdownSeconds()

        val root = FrameLayout(this)

        // Almost opaque black – app barely visible behind
        root.setBackgroundColor(Color.argb(242, 8, 8, 12))

        // Wave view behind everything
        val waveView = WaveView(this)
        root.addView(waveView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Info card – starts invisible, fades in after wave descends
        val infoCard = buildInfoCard(appName, openCount, countdownSecs, packageName)
        infoCard.alpha = 0f
        root.addView(infoCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.BOTTOM })

        // Start animation only after the view is attached to the window.
        // ValueAnimator needs Choreographer vsync signals which only flow once
        // the overlay window is active — starting before addView causes the
        // animation to stall until the first touch event.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                startBreathingSequence(waveView, infoCard)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        return root
    }

    private fun startBreathingSequence(waveView: WaveView, infoCard: View) {
        // Natural human breathing follows a sine curve: slow start, faster in middle, slow end.
        // Exhale is always longer than inhale. Hold at top mimics the natural pause after a full breath.
        val sineIn = PathInterpolator(0.37f, 0f, 0.63f, 1f)   // cubic bezier ≈ sin
        val sineOut = PathInterpolator(0.37f, 0f, 0.63f, 1f)

        // Phase 1: Inhale – 3.5s, slow-fast-slow rise
        val inhale = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3500
            interpolator = sineIn
            addUpdateListener { waveView.fillRatio = it.animatedValue as Float }
        }

        // Phase 2: Hold at top 1.2s – the natural pause after a full inhale

        // Phase 3: Exhale – 4.5s (longer than inhale), slow-fast-slow descent
        val exhale = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 4500
            interpolator = sineOut
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

        // Chain: inhale → 1.2s natural hold → exhale
        inhale.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                Handler(Looper.getMainLooper()).postDelayed({ exhale.start() }, 1200)
            }
        })

        inhale.start()
    }

    private fun buildInfoCard(
        appName: String,
        openCount: Int,
        countdownSecs: Int,
        packageName: String
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(32), dpToPx(48), dpToPx(32), dpToPx(64))
        }

        // App icon
        val iconView = ImageView(this).apply {
            try { setImageDrawable(packageManager.getApplicationIcon(packageName)) }
            catch (_: Exception) {}
            val s = dpToPx(64)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { bottomMargin = dpToPx(16) }
        }

        // App name
        val nameView = TextView(this).apply {
            text = appName
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12) }
        }

        // Usage stat
        val usageText = when {
            openCount == 0 -> "Heute noch nicht geöffnet"
            openCount == 1 -> "Heute bereits 1× geöffnet"
            else -> "Heute bereits ${openCount}× geöffnet"
        }
        val usageView = TextView(this).apply {
            text = usageText
            textSize = 15f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        // Question
        val questionView = TextView(this).apply {
            text = "Möchtest du die App wirklich öffnen?"
            textSize = 13f
            setTextColor(Color.argb(150, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(36) }
        }

        // Countdown text
        val countdownView = TextView(this).apply {
            text = countdownSecs.toString()
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(32) }
        }

        // Open button (hidden until countdown ends)
        val openBtn = buildButton("Trotzdem öffnen", Color.argb(60, 255, 255, 255)).apply {
            visibility = View.INVISIBLE
            setOnClickListener { dismissOverlay() }
        }

        // Back button
        val backBtn = buildButton("Zurück zum Startbildschirm", Color.TRANSPARENT).apply {
            setTextColor(Color.argb(180, 255, 255, 255))
            setOnClickListener {
                dismissOverlay()
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }

        card.addView(iconView)
        card.addView(nameView)
        card.addView(usageView)
        card.addView(questionView)
        card.addView(countdownView)
        card.addView(openBtn)
        card.addView(backBtn)

        // Countdown timer starts after breathing animation completes (~9.2s total)
        Handler(Looper.getMainLooper()).postDelayed({
            object : CountDownTimer((countdownSecs * 1000).toLong(), 1000) {
                override fun onTick(ms: Long) { countdownView.text = ((ms / 1000) + 1).toString() }
                override fun onFinish() {
                    countdownView.text = "✓"
                    openBtn.visibility = View.VISIBLE
                }
            }.start()
        }, 9200)

        return card
    }

    private fun buildButton(label: String, bgColor: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(260), dpToPx(48)
            ).apply { bottomMargin = dpToPx(12) }
        }
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
