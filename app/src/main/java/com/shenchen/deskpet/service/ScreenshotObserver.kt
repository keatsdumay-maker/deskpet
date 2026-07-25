package com.shenchen.deskpet.service

import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

class ScreenshotObserver(private val onScreenshot: () -> Unit) {
    private val observers = mutableListOf<FileObserver>()
    private val handler = Handler(Looper.getMainLooper())

    private val paths = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .resolve("Screenshots").absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            .resolve("Screenshots").absolutePath
    )

    fun start() {
        for (path in paths.distinct()) {
            val dir = File(path)
            if (!dir.exists()) continue
            val observer = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isImage(path)) {
                        handler.post { onScreenshot() }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private fun isImage(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}
