package app.khayt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "خيط"
            textSize = 32f
        })
        col.addView(TextView(this).apply {
            text = "يرسم لك خريطة بكل ما يتصل به هاتفك، ويتيح لك قطع أي خط."
            textSize = 15f
            setPadding(0, 0, 0, pad / 2)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        button("١. السماح بالعرض فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("٢. السماح بالموقع التقريبي") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        button("٣. تشغيل الرصد") {
            val i = VpnService.prepare(this)
            if (i != null) startActivityForResult(i, 7) else startMonitor()
        }
        button("إيقاف") {
            startService(Intent(this, KhaytVpnService::class.java).setAction(KhaytVpnService.ACTION_STOP))
        }

        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val loc = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val geo = assets.list("")?.contains("GeoLite2-City.mmdb") == true
        status.text = buildString {
            append(if (overlay) "✓ العرض فوق التطبيقات" else "✗ العرض فوق التطبيقات")
            append("\n")
            append(if (loc) "✓ الموقع التقريبي" else "✗ الموقع التقريبي (يُستعمل موقع افتراضي من Config.kt)")
            append("\n")
            append(if (geo) "✓ قاعدة المواقع GeoLite2" else "✗ ضع GeoLite2-City.mmdb في app/src/main/assets")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) startMonitor()
    }

    private fun startMonitor() {
        startService(Intent(this, KhaytVpnService::class.java))
    }
}
