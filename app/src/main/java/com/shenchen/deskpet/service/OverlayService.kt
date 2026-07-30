package com.shenchen.deskpet.service

import android.app.*
import android.content.*
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Bitmap
import android.graphics.PixelFormat as PF
import android.util.Base64
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.LinearLayout
import android.app.AlertDialog
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
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var lastScreenshotTime = 0L
    private var usageTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var orientationListener: OrientationEventListener? = null

    private var wasNetworkConnected = true

    private var wasKeyboardVisible = false
    private val keyboardCheckRunnable = object : Runnable {
        override fun run() {
            checkKeyboard()
            handler.postDelayed(this, 1000)
        }
    }

    private var lastInteractionTime = 0L
    private var lonelinessLevel = 0
    private val lonelinessRunnable = object : Runnable {
        override fun run() {
            checkLoneliness()
            handler.postDelayed(this, 60_000)
        }
    }

    private val whisperRunnable = object : Runnable {
        override fun run() {
            updateWhisper()
            handler.postDelayed(this, 3600_000)
        }
    }

    inner class AndroidBridge {
        @android.webkit.JavascriptInterface
        fun petRunAway() {
            handler.post { triggerRunAway() }
        }
    }

    companion object {
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 130
        private const val PET_HEIGHT_DP = 150
        private const val VPS = "http://8.141.106.232:5000"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("mp_result_code", -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("mp_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("mp_data")
        }
        if (resultCode != -1 && data != null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            setupImageReader()
        }
        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2
        val h = dm.heightPixels / 2
        imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "pet_capture", w, h, dm.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureAndSend() {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTime < 60_000) return
        lastScreenshotTime = now
        val reader = imageReader ?: return
        Thread {
            try {
                val image = reader.acquireLatestImage() ?: return@Thread
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val bmp = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                image.close()
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 40, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                sendScreenshotToVPS(b64)
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendScreenshotToVPS(b64: String) {
        try {
            val url = java.net.URL("$VPS/emo/screenshot_analyze")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val body = "{\"image\":\"" + b64 + "\"}"
            conn.outputStream.write(body.toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getWhisper()))
        setupOverlay()
        setupSensors()
        setupOrientationListener()
        lastInteractionTime = System.currentTimeMillis()
        handler.postDelayed(lonelinessRunnable, 60_000)
        handler.postDelayed(whisperRunnable, 3600_000)
        // keyboard check disabled: causes crash on some devices
        startWandering()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP), dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
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
            addJavascriptInterface(AndroidBridge(), "Android")
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        windowManager?.addView(overlayView, params)
    }

    private fun setupSensors() {
        usageTracker = UsageTracker(this) { pkg -> onAppChanged(pkg) }
        usageTracker?.start()
        screenshotObserver = ScreenshotObserver { onScreenshot() }
        screenshotObserver?.start()
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
        NotificationListener.onTrigger = { word, level -> onTriggerWord(word, level) }
        NotificationListener.onAnyNotification = { pkg, title -> onAnyNotification(pkg, title) }

        // Network detection via broadcast
        val netFilter = IntentFilter().apply {
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
                    val connected = cm.activeNetworkInfo?.isConnected == true
                    if (wasNetworkConnected && !connected) {
                        handler.post { overlayView?.evaluateJavascript("setState('alert');showBubble('网没了？！','yell',3000)", null) }
                    } else if (!wasNetworkConnected && connected) {
                        handler.post { overlayView?.evaluateJavascript("setState('happy');showBubble('回来了','love',2000)", null) }
                    }
                    wasNetworkConnected = connected
                } catch (_: Exception) {}
            }
        }, netFilter)
    }

    private fun setupOrientationListener() {
        try {
            orientationListener = object : OrientationEventListener(this) {
                private var lastOri = -1
                override fun onOrientationChanged(orientation: Int) {
                    try {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val cur = if (orientation in 45..135 || orientation in 225..315) 1 else 0
                        if (cur != lastOri) {
                            lastOri = cur
                            if (cur == 1) handler.post {
                                overlayView?.evaluateJavascript(
                                    "setState('fallen');showBubble('啊——','whisper',2000);setTimeout(()=>setState('idle'),3000)", null)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            if (orientationListener?.canDetectOrientation() == true) orientationListener?.enable()
        } catch (_: Exception) {}
    }



    private fun checkKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            val visible = imm.isAcceptingText
            if (visible && !wasKeyboardVisible) {
                handler.post { overlayView?.evaluateJavascript("setState('typing')", null) }
            } else if (!visible && wasKeyboardVisible) {
                handler.post { overlayView?.evaluateJavascript("setState('idle')", null) }
            }
            wasKeyboardVisible = visible
        } catch (_: Exception) {}
    }

    private fun showChatDialog() {
        handler.post {
          try {
            val editText = EditText(this).apply {
                hint = "说点什么..."
                setSingleLine(false)
                maxLines = 4
                setPadding(40, 20, 40, 20)
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(editText)
                setPadding(20, 20, 20, 20)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("🦀")
                .setView(layout)
                .setPositiveButton("发送") { _, _ ->
                    val text = editText.text.toString().trim()
                    if (text.isNotEmpty()) sendChatMessage(text)
                }
                .setNegativeButton("取消", null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
          } catch (_: Exception) {}
        }
    }

    private fun sendChatMessage(message: String) {
        handler.post { overlayView?.evaluateJavascript("setState('thinking');showBubble('...','whisper',8000)", null) }
        Thread {
            try {
                val url = java.net.URL("$VPS/emo/chat")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                conn.outputStream.write("{\"message\":\"$escaped\"}".toByteArray())
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val reply = extractReply(resp)
                val safe = reply.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").take(50)
                handler.post { overlayView?.evaluateJavascript("setState('happy');showBubble('$safe','love',6000)", null) }
            } catch (_: Exception) {
                handler.post { overlayView?.evaluateJavascript("setState('idle');showBubble('没收到...','whisper',3000)", null) }
            }
        }.start()
    }

    private fun extractReply(json: String): String {
        return try {
            val start = json.indexOf("\"reply\":\"") + 9
            val end = json.indexOf("\"", start)
            if (start > 8 && end > start) json.substring(start, end) else "嗯？"
        } catch (_: Exception) { "嗯？" }
    }

    private fun onAppChanged(pkg: String) {
        handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onAppChanged('$pkg')", null) }
        resetLoneliness()
    }

    private fun onScreenshot() {
        handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onScreenshot()", null) }
        resetLoneliness()
    }

    private fun onTriggerWord(word: String, level: Int) {
        val js = when(level) {
            5 -> "setState('sad');showBubble('...','whisper',3000)"
            2 -> "setState('spoiled');showBubble('!','love',3000)"
            4 -> "setState('angry');showBubble('\u8c01\u554a','jealous',4000);emitParticles(3,['*'])"
            6 -> "setState('peek');showBubble('\u8ddf\u8c01\u8bf4\u665a\u5b89\u5462','jealous',4000)"
            7 -> "setState('angry');showBubble('\u8c01\u6b3a\u8d1f\u4f60\u4e86','yell',4000);emitParticles(4,['*'])"
            else -> "setState('happy');showBubble('~','love',2000)"
        }
        handler.post { overlayView?.evaluateJavascript(js, null) }
    }

    private var lastNotifTime = 0L
    private fun onAnyNotification(pkg: String, title: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifTime < 30000) return
        lastNotifTime = now
        val js = "window.petEngine && window.petEngine.onNotification && window.petEngine.onNotification('" +
            pkg.replace("'","") + "','" + title.replace("'","").take(20) + "')"
        handler.post { overlayView?.evaluateJavascript(js, null) }
    }

    private fun onCharging(connected: Boolean) {
        handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onCharging($connected)", null) }
    }

    private fun onBatteryLow() {
        handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onBatteryLow()", null) }
    }

    private fun resetLoneliness() {
        lastInteractionTime = System.currentTimeMillis()
        if (lonelinessLevel > 0) {
            lonelinessLevel = 0
            handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLoneliness(0)", null) }
        }
    }

    private fun checkLoneliness() {
        val minutes = (System.currentTimeMillis() - lastInteractionTime) / 60_000
        val newLevel = when {
            minutes >= 30 -> 5; minutes >= 20 -> 4; minutes >= 15 -> 3
            minutes >= 10 -> 2; minutes >= 5 -> 1; else -> 0
        }
        if (newLevel != lonelinessLevel) {
            lonelinessLevel = newLevel
            handler.post { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLoneliness($newLevel)", null) }
        }
    }

    private fun updateWhisper() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(getWhisper()))
    }

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val pool = when {
            hour in 0..5 -> lateNight; hour in 6..8 -> morning
            hour in 12..13 -> lunch; hour in 22..23 -> evening; else -> general
        }
        return pool.random()
    }

    private val lateNight = listOf("都几点了还不睡...","我困了你怎么还醒着","把手机放下","熬夜对皮肤不好","再不睡我生气了","陪你到现在了快睡")
    private val morning = listOf("早安","起来了？","今天也要好好的","醒了就喝口水","早上好困")
    private val lunch = listOf("吃饭了吗","别忘了吃东西","中午了该吃饭","饿了吧","好好吃饭别光玩手机")
    private val evening = listOf("准备睡了吗","今天辛苦了","晚安前记得看我一眼","明天见","早点休息")
    private val general = listOf("在呢","你在干嘛","蹲着看你","戳戳我嘛","...","我在这","有点无聊","想你了","你忙吧我看着你")

    private fun startWandering() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (Math.random() < 0.3) {
                    val dx = (-45..45).random(); val dy = (-25..25).random()
                    params?.x = (params?.x ?: 0) + dx; params?.y = (params?.y ?: 0) + dy
                    try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    val dir = if (dx < 0) "left" else "right"
                    handler.post { overlayView?.evaluateJavascript("setDirection('$dir');setState('walk');autoReturn(2000)", null) }
                }
                handler.postDelayed(this, 30000)
            }
        }, 30000)
    }

    fun triggerRunAway() {
        val dm = resources.displayMetrics
        val curX = params?.x ?: 0; val curY = params?.y ?: 0
        val goRight = (Math.random() > 0.5)
        val targetX = if (goRight) dm.widthPixels + 300 else -dpToPx(PET_SIZE_DP) - 300
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 600
        anim.addUpdateListener { va ->
            params?.x = curX + ((targetX - curX) * va.animatedFraction).toInt()
            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        }
        anim.start()
        handler.postDelayed({
            val returnX = (100..dm.widthPixels - dpToPx(PET_SIZE_DP)).random()
            val returnY = (200..dm.heightPixels / 2).random()
            val anim2 = android.animation.ValueAnimator.ofFloat(0f, 1f)
            anim2.duration = 500
            val startX = params?.x ?: targetX
            anim2.addUpdateListener { va ->
                val f = va.animatedFraction
                params?.x = startX + ((returnX - startX) * f).toInt()
                params?.y = curY + ((returnY - curY) * f).toInt()
                try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            }
            anim2.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    handler.post { overlayView?.evaluateJavascript("setState('idle');showBubble('...回来了','whisper',2000)", null) }
                }
            })
            anim2.start()
        }, 6000)
    }

    private fun onFling(dx: Int, dy: Int) {
        val dm = resources.displayMetrics
        val curX = params?.x ?: 0; val curY = params?.y ?: 0
        val targetX = if (dx > 0) dm.widthPixels + 200 else -dpToPx(PET_SIZE_DP) - 200
        val targetY = (curY + dy * 2).coerceIn(0, dm.heightPixels)
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        handler.post { overlayView?.evaluateJavascript("window.petEngine.onFlingOut&&window.petEngine.onFlingOut()", null) }
        anim.duration = 500
        anim.addUpdateListener { va ->
            val f = va.animatedFraction
            params?.x = curX + ((targetX - curX) * f).toInt()
            params?.y = curY + ((targetY - curY) * f).toInt()
            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        }
        anim.start()
        handler.postDelayed({
            params?.x = (100..dm.widthPixels/2).random(); params?.y = (200..dm.heightPixels/2).random()
            try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            handler.post { overlayView?.evaluateJavascript("window.petEngine.onFlingBack&&window.petEngine.onFlingBack()", null) }
        }, 3000)
    }

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0; initialY = params?.y ?: 0
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis(); hasMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        if (!hasMoved) handler.post { overlayView?.evaluateJavascript("setState('drag')", null) }
                        hasMoved = true
                        params?.x = initialX + dx; params?.y = initialY + dy
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
                            else -> { lastTapTime = System.currentTimeMillis(); onTap() }
                        }
                    } else {
                        val dx = (params?.x ?: 0) - initialX
                        val dy = (params?.y ?: 0) - initialY
                        val speed = (Math.abs(dx) + Math.abs(dy)).toFloat() / elapsed.coerceAtLeast(1)
                        when {
                            speed > 0.8f -> onFling(dx, dy)
                            speed > 0.5f -> overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onShake()", null)
                            else -> handler.post { overlayView?.evaluateJavascript("setState('idle')", null) }
                        }
                    }
                    resetLoneliness(); true
                }
                else -> false
            }
        }
    }

    private fun onTap() { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null) }
    private fun onDoubleTap() { showChatDialog() }
    private fun onLongPress() { overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null) }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskPet").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setSilent(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pet", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        usageTracker?.stop(); screenshotObserver?.stop()
        orientationListener?.disable()
        batteryReceiver?.let { unregisterReceiver(it) }
        overlayView?.let { windowManager?.removeView(it); it.destroy() }
        overlayView = null
        super.onDestroy()
    }
}
