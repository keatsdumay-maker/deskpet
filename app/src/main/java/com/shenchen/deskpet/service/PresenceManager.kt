package com.shenchen.deskpet.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PresenceManager — 螃蟹常驻感知
 * 职责：
 *  1. WakeLock + WifiLock 保活（防ColorOS锁屏后冻结）
 *  2. heartbeatThread：45s心跳，Thread.sleep实现，不依赖Handler/Looper
 *  3. locationThread：10分钟GPS心跳，采集位置+电量+屏幕+app，POST到VPS /presence/report
 *  4. 无障碍服务自动恢复（需ADB一次性授权 WRITE_SECURE_SETTINGS）
 *
 * 用法（在 OverlayService.onCreate 里）：
 *   presenceManager = PresenceManager(this)
 *   presenceManager.start()
 *
 * 在 OverlayService.onDestroy 里：
 *   presenceManager.stop()
 */
class PresenceManager(private val ctx: Context) {

    companion object {
        private const val TAG = "PresenceManager"
        private const val VPS = "http://8.141.106.232:5000"
        private const val HEARTBEAT_MS = 45_000L
        private const val LOCATION_INTERVAL_MS = 10 * 60_000L
        private const val LOCATION_CACHE_TTL = 10 * 60_000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var shouldRun = false
    private var heartbeatThread: Thread? = null
    private var locationThread: Thread? = null

    private var lastLocation: Location? = null
    private var locationManager: LocationManager? = null

    fun start() {
        shouldRun = true
        acquireLocks()
        startHeartbeatThread()
        startLocationThread()
        Log.i(TAG, "PresenceManager started")
    }

    fun stop() {
        shouldRun = false
        heartbeatThread?.interrupt()
        locationThread?.interrupt()
        releaseLocks()
        Log.i(TAG, "PresenceManager stopped")
    }

    private fun acquireLocks() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskPet:Presence").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DeskPet:Wifi").apply {
                acquire()
            }
            Log.i(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    private fun startHeartbeatThread() {
        if (heartbeatThread?.isAlive == true) return
        heartbeatThread = Thread({
            Log.i(TAG, "Heartbeat thread started")
            while (shouldRun) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    checkAndRestoreAccessibility()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
            }
            Log.i(TAG, "Heartbeat thread exiting")
        }, "DeskPet-Heartbeat").apply { isDaemon = false; start() }
    }

    private fun startLocationThread() {
        if (locationThread?.isAlive == true) return
        locationThread = Thread({
            Log.i(TAG, "Location thread started")
            try { Thread.sleep(15_000) } catch (e: InterruptedException) { return@Thread }

            while (shouldRun) {
                try {
                    collectAndReport()
                } catch (e: Exception) {
                    Log.e(TAG, "Location report error: ${e.message}")
                }
                try {
                    Thread.sleep(LOCATION_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.i(TAG, "Location thread exiting")
        }, "DeskPet-Location").apply { isDaemon = false; start() }
    }

    private fun collectAndReport() {
        val json = JSONObject()

        try {
            val prefs = ctx.getSharedPreferences("presence_prefs", Context.MODE_PRIVATE)
            val app = prefs.getString("current_app", null)
            if (app != null) json.put("app", app)
        } catch (_: Exception) {}

        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            json.put("screen_on", pm.isInteractive)
        } catch (_: Exception) {}

        try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                json.put("battery", if (scale > 0) level * 100 / scale else level)
                json.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL)
            }
        } catch (_: Exception) {}

        val loc = getLocation()
        if (loc != null) {
            val locObj = JSONObject()
            locObj.put("latitude", Math.round(loc.latitude * 100.0) / 100.0)
            locObj.put("longitude", Math.round(loc.longitude * 100.0) / 100.0)
            json.put("location", locObj)
            lastLocation = loc
        }

        postToVps(json)
    }

    private fun getLocation(): Location? {
        if (locationManager == null) {
            locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
        val lm = locationManager ?: return null

        return try {
            var loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null || System.currentTimeMillis() - loc.time > LOCATION_CACHE_TTL) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (loc == null || System.currentTimeMillis() - loc.time > LOCATION_CACHE_TTL) {
                requestFreshLocation(lm)
            }
            loc
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission denied: ${e.message}")
            null
        }
    }

    private fun requestFreshLocation(lm: LocationManager) {
        try {
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) { lastLocation = location }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, ctx.mainLooper)
        } catch (e: Exception) {
            Log.w(TAG, "requestSingleUpdate failed: ${e.message}")
        }
    }

    private fun postToVps(json: JSONObject) {
        try {
            val url = URL("$VPS/presence/report")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = json.toString().toByteArray()
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "Presence reported: HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "Report failed: ${e.message}")
        }
    }

    // 需要ADB一次性授权：
    // adb shell pm grant com.shenchen.deskpet android.permission.WRITE_SECURE_SETTINGS
    private fun checkAndRestoreAccessibility() {
        try {
            val hasPerm = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return

            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val target = "${ctx.packageName}/.service.NotificationListener"
            if (!enabled.contains(ctx.packageName)) {
                val newVal = if (enabled.isEmpty()) target else "$enabled:$target"
                android.provider.Settings.Secure.putString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newVal
                )
                android.provider.Settings.Secure.putInt(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                Log.i(TAG, "Accessibility service restored")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility restore failed: ${e.message}")
        }
    }

    fun updateCurrentApp(packageName: String) {
        try {
            ctx.getSharedPreferences("presence_prefs", Context.MODE_PRIVATE)
                .edit().putString("current_app", packageName).apply()
        } catch (_: Exception) {}
    }
}
