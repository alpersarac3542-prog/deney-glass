package com.liquid.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { sendApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF2A1F3D.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)
    }

    inner class Bridge {
        @JavascriptInterface fun requestApps() { sendApps() }
        @JavascriptInterface fun launch(pkg: String) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
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
                        .put("i", Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)))
                } catch (e: Exception) { }
            }
            runOnUiThread { web.evaluateJavascript("window.onApps&&onApps($arr)", null) }
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) web.evaluateJavascript("window.goHome&&goHome()", null)
    }

    @Deprecated("Launcher: geri tuşu bir şey yapmaz")
    override fun onBackPressed() { }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }
}
