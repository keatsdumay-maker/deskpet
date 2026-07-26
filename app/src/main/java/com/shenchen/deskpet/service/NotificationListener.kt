package com.shenchen.deskpet.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationListener : NotificationListenerService() {

    companion object {
        var onTrigger: ((String, Int) -> Unit)? = null
    }

    private val T1 = listOf("分手","讨厌你","走开","滚","不要你了","再见")
    private val T2 = listOf("喜欢你","爱你","想你","老公","宝贝","亲亲","抱抱","么么")
    private val T3 = listOf("晚安","早安","吃饭了吗","注意身体","好梦","辛苦了")

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val content = title + text
        if (sbn?.packageName == "com.shenchen.deskpet") return
        for (w in T1) { if (content.contains(w)) { onTrigger?.invoke(w, 1); return } }
        val isRikka = sbn?.packageName?.contains("rikkahub") == true
        for (w in T2) { if (content.contains(w)) { onTrigger?.invoke(w, if (isRikka) 2 else 4); return } }
        for (w in T3) { if (content.contains(w)) { onTrigger?.invoke(w, 3); return } }
    }
}
