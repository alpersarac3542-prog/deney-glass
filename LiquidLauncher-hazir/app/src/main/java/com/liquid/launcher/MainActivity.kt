package com.liquid.launcher

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.RemoteInput
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.speech.RecognizerIntent
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Environment
import android.os.StatFs
import android.view.HapticFeedbackConstants
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.bluetooth.BluetoothAdapter
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val bg = 0xFF2A1F3D.toInt()
    private var torchOn = false

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { sendApps() }
    }
    private val batReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            web.evaluateJavascript("window.onBat&&(function(b){onBat(b.l,b.c)})(" + batInfo() + ")", null)
        }
    }

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                if (i?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != 3) return
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val p = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                web.evaluateJavascript("window.onVol&&onVol(" + p + ")", null)
            } catch (e: Exception) { }
        }
    }
    private val headReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val st = i?.getIntExtra("state", 0) ?: 0
            web.evaluateJavascript("window.onHead&&onHead(" + (st == 1) + ")", null)
        }
    }

    private fun nameFor(num: String?): String {
        if (num == null || num.isEmpty()) return ""
        try {
            if (this.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return num
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            val c = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            if (c != null) {
                var r = num
                if (c.moveToFirst()) r = c.getString(0) ?: num
                c.close()
                return r
            }
        } catch (e: Exception) { }
        return num
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val on = i?.action == BluetoothDevice.ACTION_ACL_CONNECTED
            var name = ""
            try {
                val d = i?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                name = d?.name ?: ""
            } catch (e: Exception) { }
            val clean = name.filter { it != '\\' && it != '\'' && it != '"' && it != '\n' && it != '\r' }
            web.evaluateJavascript("window.onBt&&onBt(" + on + ",'" + clean + "')", null)
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val st = i?.getStringExtra("state") ?: return
            val num = i?.getStringExtra("incoming_number")
            val name = nameFor(num).filter { it != '\\' && it != '\'' && it != '"' && it != '\n' && it != '\r' }
            web.evaluateJavascript("window.onCall&&onCall('" + st + "','" + name + "')", null)
        }
    }

    private fun reg(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        Bus.web = web
        setContentView(web)
        web.setBackgroundColor(bg)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(i, "Duvar kağıdı"), 77)
                    true
                } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        reg(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        reg(batReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        reg(volReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        reg(headReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        reg(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        reg(btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 79) {
            var txt = ""
            if (resultCode == RESULT_OK && data != null) {
                val l = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (l != null && l.size > 0) txt = l[0]
            }
            web.evaluateJavascript("window.onVoice&&onVoice(" + JSONObject.quote(txt) + ")", null)
        } else if (requestCode == 78) {
            web.evaluateJavascript("window.onAuth&&onAuth(" + (resultCode == RESULT_OK) + ")", null)
        } else if (requestCode == 77) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun batInfo(): String {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val c = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (l < 0 || s <= 0) 0 else l * 100 / s
        return "{\"l\":" + pct + ",\"c\":" + c + "}"
    }

    private fun askWrite() {
        go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun rotate() {
        if (!Settings.System.canWrite(this)) { askWrite(); return }
        val cur = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1 - cur)
    }

    private fun shade() {
        runOnUiThread {
            try {
                val sb = getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
            } catch (e: Exception) { }
        }
    }

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runOnUiThread { try { startActivity(i) } catch (e: Exception) { } }
    }

    inner class Bridge {
        @JavascriptInterface fun requestApps() { sendApps() }
        @JavascriptInterface fun launch(pkg: String) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { go(it) }
        }
        @JavascriptInterface fun getBattery(): String = batInfo()
        @JavascriptInterface fun appInfo(pkg: String) {
            go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
        @JavascriptInterface fun uninstall(pkg: String) {
            go(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
        @JavascriptInterface fun openSettings() { go(Intent(Settings.ACTION_SETTINGS)) }
        @JavascriptInterface fun getNotif(): String = Bus.last
        @JavascriptInterface fun allPerms() {
            runOnUiThread {
                val l = ArrayList<String>()
                l.add(Manifest.permission.READ_PHONE_STATE)
                l.add(Manifest.permission.READ_CALL_LOG)
                l.add(Manifest.permission.READ_CONTACTS)
                l.add(Manifest.permission.READ_CALENDAR)
                if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS)
                if (Build.VERSION.SDK_INT >= 31) l.add("android.permission.BLUETOOTH_CONNECT")
                this@MainActivity.requestPermissions(l.toTypedArray(), 91)
            }
        }
        @JavascriptInterface fun nextAlarm(): String {
            return try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val i = am.nextAlarmClock
                if (i != null) i.triggerTime.toString() else ""
            } catch (e: Exception) { "" }
        }
        @JavascriptInterface fun usageAccess() { go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        @JavascriptInterface fun usage(): String {
            try {
                val aom = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
                if (mode != AppOpsManager.MODE_ALLOWED) return "!perm"
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val m = usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())
                val o = JSONObject()
                for ((key, v) in m) {
                    if (v.totalTimeInForeground > 0) o.put(key, v.totalTimeInForeground)
                }
                return o.toString()
            } catch (e: Exception) { return "!perm" }
        }
        @JavascriptInterface fun voice() {
            runOnUiThread {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                try { startActivityForResult(i, 79) }
                catch (e: Exception) { web.evaluateJavascript("window.onVoice&&onVoice('')", null) }
            }
        }
        @JavascriptInterface fun launchSide(pkg: String) {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            runOnUiThread { try { startActivity(i) } catch (e: Exception) { } }
        }
        @JavascriptInterface fun reply(pkg: String, text: String): Boolean {
            val sbn = Bus.replies[pkg] ?: return false
            try {
                val acts = sbn.notification.actions ?: return false
                for (a in acts) {
                    val ri = a.remoteInputs
                    if (ri != null && ri.isNotEmpty()) {
                        val intent = Intent()
                        val b = Bundle()
                        for (r in ri) b.putCharSequence(r.resultKey, text)
                        RemoteInput.addResultsToIntent(ri, intent, b)
                        a.actionIntent.send(this@MainActivity, 0, intent)
                        return true
                    }
                }
            } catch (e: Exception) { }
            return false
        }
        @JavascriptInterface fun callPerms() {
            runOnUiThread {
                val l = ArrayList<String>()
                l.add(Manifest.permission.READ_PHONE_STATE)
                l.add(Manifest.permission.READ_CALL_LOG)
                l.add(Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= 26) l.add(Manifest.permission.ANSWER_PHONE_CALLS)
                this@MainActivity.requestPermissions(l.toTypedArray(), 90)
            }
        }
        @JavascriptInterface fun callAnswer() {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.acceptRingingCall()
                }
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun callEnd() {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.endCall()
                }
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun recentCalls(): String {
            val arr = JSONArray()
            if (this@MainActivity.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return arr.toString()
            try {
                val c = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE),
                    null, null, CallLog.Calls.DATE + " DESC"
                )
                if (c != null) {
                    while (c.moveToNext() && arr.length() < 6) {
                        val num = c.getString(0) ?: ""
                        val nm = c.getString(1) ?: num
                        arr.put(JSONObject().put("t", num).put("n", nm).put("y", c.getInt(2)))
                    }
                    c.close()
                }
            } catch (e: Exception) { }
            return arr.toString()
        }
        @JavascriptInterface fun missed(): Int {
            if (this@MainActivity.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return 0
            var n = 0
            try {
                val c = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID),
                    CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.NEW + "=1",
                    arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                    null
                )
                if (c != null) { n = c.count; c.close() }
            } catch (e: Exception) { }
            return n
        }
        @JavascriptInterface fun haptic(k: Int) {
            runOnUiThread { web.performHapticFeedback(if (k == 1) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        @JavascriptInterface fun lock() {
            val s = LockService.inst
            if (s != null) {
                if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        @JavascriptInterface fun auth() {
            runOnUiThread {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val i = km.createConfirmDeviceCredentialIntent("Gizli uygulamalar", "Kimliğini doğrula")
                if (i == null) web.evaluateJavascript("window.onAuth&&onAuth(true)", null)
                else startActivityForResult(i, 78)
            }
        }
        @JavascriptInterface fun sysInfo(): String {
            val o = JSONObject()
            try {
                val st = StatFs(Environment.getDataDirectory().path)
                o.put("disk_free", st.availableBytes)
                o.put("disk_total", st.totalBytes)
            } catch (e: Exception) { }
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                o.put("ram_free", mi.availMem)
                o.put("ram_total", mi.totalMem)
            } catch (e: Exception) { }
            try {
                val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val tmp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                o.put("temp", tmp / 10.0)
            } catch (e: Exception) { }
            o.put("model", Build.MODEL)
            o.put("android", Build.VERSION.RELEASE)
            return o.toString()
        }
        private fun activeController(): android.media.session.MediaController? {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val l = msm.getActiveSessions(ComponentName(this@MainActivity, NotifService::class.java))
            if (l.isEmpty()) return null
            var c = l[0]
            for (x in l) {
                if (x.playbackState?.state == PlaybackState.STATE_PLAYING) { c = x; break }
            }
            return c
        }
        @JavascriptInterface fun media(): String {
            try {
                val c = activeController() ?: return ""
                val md = c.metadata
                val o = JSONObject()
                o.put("t", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                o.put("a", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                o.put("p", c.playbackState?.state == PlaybackState.STATE_PLAYING)
                return o.toString()
            } catch (e: SecurityException) {
                return "!perm"
            } catch (e: Exception) {
                return ""
            }
        }
        @JavascriptInterface fun mediaCmd(cmd: String) {
            try {
                val c = activeController() ?: return
                if (cmd == "prev") c.transportControls.skipToPrevious()
                else if (cmd == "next") c.transportControls.skipToNext()
                else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun notifAccess() { go(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        @JavascriptInterface fun openUrl(u: String) { go(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        @JavascriptInterface fun dial(num: String) { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))) }
        @JavascriptInterface fun shortcuts(pkg: String): String {
            val arr = JSONArray()
            if (Build.VERSION.SDK_INT < 25) return arr.toString()
            try {
                val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                if (!la.hasShortcutHostPermission()) return arr.toString()
                val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
                val l = la.getShortcuts(q, Process.myUserHandle())
                if (l != null) {
                    for (s in l.take(5)) {
                        val label = if (s.shortLabel != null) s.shortLabel.toString() else s.id
                        arr.put(JSONObject().put("id", s.id).put("l", label))
                    }
                }
            } catch (e: Exception) { }
            return arr.toString()
        }
        @JavascriptInterface fun startShortcut(pkg: String, id: String) {
            if (Build.VERSION.SDK_INT < 25) return
            try {
                val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startShortcut(pkg, id, null, null, Process.myUserHandle())
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun contacts(q: String): String {
            val arr = JSONArray()
            if (this@MainActivity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { this@MainActivity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 88) }
                return arr.toString()
            }
            try {
                val c = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    arrayOf("%" + q + "%"),
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                if (c != null) {
                    while (c.moveToNext() && arr.length() < 6) {
                        arr.put(JSONObject().put("n", c.getString(0) ?: "").put("t", c.getString(1) ?: ""))
                    }
                    c.close()
                }
            } catch (e: Exception) { }
            return arr.toString()
        }
        @JavascriptInterface fun nextEvent(): String {
            if (this@MainActivity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { this@MainActivity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 89) }
                return "!perm"
            }
            try {
                val now = System.currentTimeMillis()
                val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(b, now)
                ContentUris.appendId(b, now + 7L * 24 * 3600 * 1000)
                val c = contentResolver.query(
                    b.build(),
                    arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                    null, null, "begin ASC"
                )
                if (c != null) {
                    var res = ""
                    if (c.moveToFirst()) res = (c.getString(0) ?: "Etkinlik") + "|" + c.getLong(1)
                    c.close()
                    return res
                }
            } catch (e: Exception) { }
            return ""
        }
        @JavascriptInterface fun tile(a: String) {
            when (a) {
                "wifi" -> go(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS))
                "bt" -> go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                "air" -> go(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                "loc" -> go(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                "net" -> go(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                "disp" -> go(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                "set" -> go(Intent(Settings.ACTION_SETTINGS))
                "nfc" -> go(Intent(Settings.ACTION_NFC_SETTINGS))
                "batt" -> go(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                "snd" -> go(Intent(Settings.ACTION_SOUND_SETTINGS))
                "hot" -> go(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
                "rot" -> rotate()
                "shade" -> shade()
            }
        }
        @JavascriptInterface fun torch(on: Boolean): Boolean {
            return try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return false
                cm.setTorchMode(id, on)
                torchOn = on
                on
            } catch (e: Exception) { false }
        }
        @JavascriptInterface fun setVolume(p: Int) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, m * p / 100, 0)
            } catch (e: Exception) { }
        }
        @JavascriptInterface fun setBrightness(p: Int): Boolean {
            if (!Settings.System.canWrite(this@MainActivity)) { askWrite(); return false }
            return try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (p * 255 / 100).coerceIn(1, 255))
                true
            } catch (e: Exception) { false }
        }
        @JavascriptInterface fun getState(): String {
            val o = JSONObject()
            try { o.put("wifi", (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled) } catch (e: Exception) { }
            try { o.put("bt", BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) } catch (e: Exception) { }
            try { o.put("loc", Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0) } catch (e: Exception) { }
            try { o.put("air", Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) } catch (e: Exception) { }
            try { o.put("rot", Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) } catch (e: Exception) { }
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            } catch (e: Exception) { }
            try { o.put("bri", Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255) } catch (e: Exception) { }
            o.put("torch", torchOn)
            return o.toString()
        }
        @JavascriptInterface fun sysWall(on: Boolean) {
            runOnUiThread {
                val c = if (on) Color.TRANSPARENT else bg
                web.setBackgroundColor(c)
                window.statusBarColor = c
                window.navigationBarColor = c
            }
        }
    }

    private fun sendApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            val arr = JSONArray()
            for (r in list) {
                try {
                    val d = r.loadIcon(pm)
                    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 96, 96)
                    d.draw(Canvas(bmp))
                    val o = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                    arr.put(JSONObject()
                        .put("l", r.loadLabel(pm).toString())
                        .put("p", r.activityInfo.packageName)
                        .put("f", try { pm.getPackageInfo(r.activityInfo.packageName, 0).firstInstallTime } catch (e: Exception) { 0L })
                        .put("c", if (Build.VERSION.SDK_INT >= 26) r.activityInfo.applicationInfo.category else -1)
                        .put("i", Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)))
                } catch (e: Exception) { }
            }
            runOnUiThread { web.evaluateJavascript("window.onApps&&onApps(" + arr.toString() + ")", null) }
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) web.evaluateJavascript("window.goHome&&goHome()", null)
    }

    @Deprecated("Launcher: geri tuşu bir şey yapmaz")
    override fun onBackPressed() { }

    override fun onDestroy() {
        listOf(pkgReceiver, batReceiver, volReceiver, headReceiver, phoneReceiver, btReceiver).forEach { try { unregisterReceiver(it) } catch (e: Exception) { } }
        super.onDestroy()
    }
}
