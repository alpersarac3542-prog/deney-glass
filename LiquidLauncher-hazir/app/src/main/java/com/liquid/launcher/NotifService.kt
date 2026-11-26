package com.liquid.launcher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.webkit.WebView
import org.json.JSONObject

object Bus {
    var web: WebView? = null
    var last: String = "{}"
    val replies = HashMap<String, StatusBarNotification>()

    fun msg(pkg: String, title: String, text: String, reply: Boolean) {
        val o = JSONObject()
        o.put("p", pkg)
        o.put("t", title)
        o.put("x", text)
        o.put("r", reply)
        val js = o.toString()
        Handler(Looper.getMainLooper()).post {
            web?.evaluateJavascript("window.onMsg&&onMsg(" + js + ")", null)
        }
    }

    fun send(json: String) {
        last = json
        Handler(Looper.getMainLooper()).post {
            web?.evaluateJavascript("window.onNotif&&onNotif(" + json + ")", null)
        }
    }
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() { push() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        push()
        try {
            if (sbn == null) return
            val n = sbn.notification
            if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            if (sbn.packageName == packageName) return
            var reply = false
            val acts = n.actions
            if (acts != null) {
                for (a in acts) {
                    val ri = a.remoteInputs
                    if (ri != null && ri.isNotEmpty()) reply = true
                }
            }
            if (reply) Bus.replies[sbn.packageName] = sbn
            val ex = n.extras
            val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
            val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
            Bus.msg(sbn.packageName, title, text, reply)
        } catch (e: Exception) { }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { push() }

    private fun push() {
        try {
            val counts = HashMap<String, Int>()
            val list = activeNotifications
            if (list != null) {
                for (n in list) {
                    val p = n.packageName
                    counts[p] = (counts[p] ?: 0) + 1
                }
            }
            val o = JSONObject()
            for ((k, v) in counts) o.put(k, v)
            Bus.send(o.toString())
        } catch (e: Exception) { }
    }
}
