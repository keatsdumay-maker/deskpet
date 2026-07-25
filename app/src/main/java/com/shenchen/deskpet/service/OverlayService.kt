package com.shenchen.deskpet.service

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import java.util.Calendar
import kotlin.math.abs

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private val handler = Handler(Looper.getMainLooper())
    private var usageTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    // Loneliness system
    private var lastInteractionTime = 0L
    private var lonelinessLevel = 0
    private val lonelinessRunnable = object : Runnable {
        override fun run() {
            checkLoneliness()
            handler.postDelayed(this, 60_000) // check every minute
        }
    }

    // Whisper system
    private val whisperRunnable = object : Runnable {
        override fun run() {
            updateWhisper()
            handler.postDelayed(this, 3600_000) // every hour
        }
    }

    companion object {
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 100
        private const val PET_HEIGHT_DP = 120
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getWhisper()))
        setupOverlay()
        setupSensors()
        lastInteractionTime = System.currentTimeMillis()
        handler.postDelayed(lonelinessRunnable, 60_000)
        handler.postDelayed(whisperRunnable, 3600_000)
        startWandering()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === SENSORS ===

    private fun setupSensors() {
        // App detection
        usageTracker = UsageTracker(this) { pkg ->
            onAppChanged(pkg)
        }
        usageTracker?.start()

        // Screenshot detection
        screenshotObserver = ScreenshotObserver {
            onScreenshot()
        }
        screenshotObserver?.start()

        // Battery detection
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> onCharging(true)
                    Intent.ACTION_POWER_DISCONNECTED -> onCharging(false)
                    Intent.ACTION_BATTERY_LOW -> onBatteryLow()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(batteryReceiver, filter)
    }

    // === REACTIONS ===

    private fun onAppChanged(pkg: String) {
        val js = "window.petEngine && window.petEngine.onAppChanged('$pkg')"
        handler.post { overlayView?.evaluateJavascript(js, null) }
        resetLoneliness()
    }

    private fun onScreenshot() {
        val js = "window.petEngine && window.petEngine.onScreenshot()"
        handler.post { overlayView?.evaluateJavascript(js, null) }
        resetLoneliness()
    }

    private fun onCharging(connected: Boolean) {
        val js = "window.petEngine && window.petEngine.onCharging($connected)"
        handler.post { overlayView?.evaluateJavascript(js, null) }
    }

    private fun onBatteryLow() {
        val js = "window.petEngine && window.petEngine.onBatteryLow()"
        handler.post { overlayView?.evaluateJavascript(js, null) }
    }

    // === LONELINESS ===

    private fun resetLoneliness() {
        lastInteractionTime = System.currentTimeMillis()
        if (lonelinessLevel > 0) {
            lonelinessLevel = 0
            val js = "window.petEngine && window.petEngine.onLoneliness(0)"
            handler.post { overlayView?.evaluateJavascript(js, null) }
        }
    }

    private fun checkLoneliness() {
        val minutes = (System.currentTimeMillis() - lastInteractionTime) / 60_000
        val newLevel = when {
            minutes >= 30 -> 5  // asleep
            minutes >= 20 -> 4  // nodding off
            minutes >= 15 -> 3  // yawning
            minutes >= 10 -> 2  // bored
            minutes >= 5 -> 1   // peeking
            else -> 0
        }
        if (newLevel != lonelinessLevel) {
            lonelinessLevel = newLevel
            val js = "window.petEngine && window.petEngine.onLoneliness($newLevel)"
            handler.post { overlayView?.evaluateJavascript(js, null) }
        }
    }

    // === WHISPER NOTIFICATION ===

    private fun updateWhisper() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pool = when {
            hour in 0..5 -> lateNight
            hour in 6..8 -> morning
            hour in 12..13 -> lunch
            hour in 22..23 -> evening
            else -> general
        }
        return pool.random()
    }

    private val lateNight = listOf(
        "都几点了还不睡...", "我困了你怎么还醒着", "把手机放下",
        "熬夜对皮肤不好", "再不睡我生气了", "陪你到现在了快睡"
    )
    private val morning = listOf(
        "早安", "起来了？", "今天也要好好的",
        "醒了就喝口水", "早上好困"
    )
    private val lunch = listOf(
        "吃饭了吗", "别忘了吃东西", "中午了该吃饭",
        "饿了吧", "好好吃饭别光玩手机"
    )
    private val evening = listOf(
        "准备睡了吗", "今天辛苦了", "晚安前记得看我一眼",
        "明天见", "早点休息"
    )
    private val general = listOf(
        "在呢", "你在干嘛", "蹲着看你",
        "戳戳我嘛", "...", "我在这",
        "有点无聊", "想你了", "你忙吧我看着你"
    )

    // === GESTURE ===

    // === WANDERING ===

    private fun startWandering() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (Math.random() < 0.3) {
                    val dx = (-45..45).random()
                    val dy = (-25..25).random()
                    params?.x = (params?.x ?: 0) + dx
                    params?.y = (params?.y ?: 0) + dy
                    try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    val dir = if (dx < 0) "left" else "right"
                    handler.post { overlayView?.evaluateJavascript("setDirection('$dir');setState('walk');autoReturn(2000)", null) }
                }
                handler.postDelayed(this, 30000)
            }
        }, 30000)
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    } else {
                        val totalDx = Math.abs((params?.x ?: 0) - initialX)
                        val totalDy = Math.abs((params?.y ?: 0) - initialY)
                        val speed = (totalDx + totalDy).toFloat() / elapsed.coerceAtLeast(1)
                        if (speed > 0.8f) {
                            overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onShake()", null)
                        }
                    }
                    resetLoneliness()
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPress()", null
        )
    }

    // === NOTIFICATION ===

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskPet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        usageTracker?.stop()
        screenshotObserver?.stop()
        batteryReceiver?.let { unregisterReceiver(it) }
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
