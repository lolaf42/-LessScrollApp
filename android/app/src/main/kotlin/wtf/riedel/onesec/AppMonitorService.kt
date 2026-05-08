package wtf.riedel.onesec

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
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
    @Volatile private var overlayShowing = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        timer?.cancel()
        dismissOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun startMonitoring() {
        lastEventTime = System.currentTimeMillis()
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { processUsageEvents() }
        }, 500, 500)
    }

    private fun processUsageEvents() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastEventTime, now)
        lastEventTime = now
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = event.packageName
            if (pkg == packageName) continue
            val blocked = loadBlockedPackages()
            if (blocked.contains(pkg) && !overlayShowing) {
                Handler(Looper.getMainLooper()).post { showOverlay(pkg) }
            } else if (!blocked.contains(pkg) && overlayShowing) {
                Handler(Looper.getMainLooper()).post { dismissOverlay() }
            }
        }
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
        return raw.trim().removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun loadCountdownSeconds(): Int {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(COUNTDOWN_KEY, 5)
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

        // Start the breathing animation sequence
        startBreathingSequence(waveView, infoCard)

        return root
    }

    private fun startBreathingSequence(waveView: WaveView, infoCard: View) {
        // Phase 1: Inhale – wave rises from 0 to 1 (1.8s)
        val inhale = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { waveView.fillRatio = it.animatedValue as Float }
        }

        // Phase 2: Hold at top (400ms pause, no animation)

        // Phase 3: Exhale – panel drops from 1 to 0 (1.4s), then info card appears
        val exhale = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1600
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { waveView.fillRatio = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Fade in info card as wave settles
                    infoCard.animate().alpha(1f).setDuration(400).start()
                    // Enable touch on overlay
                    overlayView?.let {
                        (it.layoutParams as? WindowManager.LayoutParams)?.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        try { windowManager?.updateViewLayout(it, it.layoutParams) } catch (_: Exception) {}
                    }
                }
            })
        }

        // Chain: inhale → 400ms pause → exhale
        inhale.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                Handler(Looper.getMainLooper()).postDelayed({ exhale.start() }, 400)
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

        // Countdown timer (starts after wave animation ~3.6s total)
        Handler(Looper.getMainLooper()).postDelayed({
            object : CountDownTimer((countdownSecs * 1000).toLong(), 1000) {
                override fun onTick(ms: Long) { countdownView.text = ((ms / 1000) + 1).toString() }
                override fun onFinish() {
                    countdownView.text = "✓"
                    openBtn.visibility = View.VISIBLE
                }
            }.start()
        }, 3800)

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
