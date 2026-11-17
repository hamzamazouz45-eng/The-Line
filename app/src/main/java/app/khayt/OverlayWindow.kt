package app.khayt

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.VpnService
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class OverlayWindow(private val svc: VpnService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var web: WebView? = null
    private var big = false

    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show() {
        if (root != null || !Settings.canDrawOverlays(svc)) return
        val dm = svc.resources.displayMetrics

        val w = WebView(svc).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(Bridge(svc), "Android")
            loadUrl("file:///android_asset/map.html")
        }
        web = w

        fun barButton(label: String, onClick: () -> Unit) = TextView(svc).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

        val title = TextView(svc).apply {
            text = "خيط · خريطة اتصالاتك"
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        val bar = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            setBackgroundColor(0xFF1B1F26.toInt())
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val lp = WindowManager.LayoutParams(
            minOf(dm.widthPixels - dp(16), dp(420)),
            dp(400),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(80)
        }

        val container = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2101318.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(1, 0x33FFFFFF)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        bar.addView(barButton("⤢") {
            big = !big
            lp.height = if (big) (dm.heightPixels * 0.8).toInt() else dp(400)
            wm.updateViewLayout(container, lp)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(barButton("✕") { svc.stopSelf() }, LinearLayout.LayoutParams(dp(40), dp(40)))

        container.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        container.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        bar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(container, lp)
                    true
                }
                else -> false
            }
        }

        wm.addView(container, lp)
        root = container
    }

    fun hide() {
        try {
            root?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        web?.destroy()
        root = null
        web = null
    }
}

class Bridge(private val ctx: Context) {

    @JavascriptInterface
    fun events(since: Double): String =
        JSONArray(EventStore.since(since.toLong(), 1500).map { it.json() }).toString()

    @JavascriptInterface
    fun history(): String = JSONArray(EventStore.history().map { it.json() }).toString()

    @JavascriptInterface
    fun blocked(): String = JSONArray(Blocklist.all().toList()).toString()

    @JavascriptInterface
    fun setBlocked(domain: String, on: Boolean) {
        Blocklist.setBlocked(ctx, domain, on)
    }

    @JavascriptInterface
    fun geoReady(): Boolean = Geo.available

    @JavascriptInterface
    fun me(): String {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val lm = ctx.getSystemService(LocationManager::class.java)
                val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                if (best != null) return "${best.latitude},${best.longitude}"
            }
        } catch (e: Exception) {
        }
        return "${Config.DEFAULT_LAT},${Config.DEFAULT_LON}"
    }
}
