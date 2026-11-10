package ir.sadegh.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f; text = "برای شروع، دکمه‌ها را به ترتیب بزن." }
        fun btn(t: String, f: () -> Unit): Button = Button(this).apply { text = t; setOnClickListener { f() } }
        root.addView(status)
        root.addView(btn("۱) دسترسی نمایش روی برنامه‌ها (برای باز کردن برنامه از پس‌زمینه)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("۲) غیرفعال‌سازی بهینه‌سازی باتری") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("۳) شروع دستیار صادق") { start() })
        root.addView(btn("توقف دستیار") {
            stopService(Intent(this, AssistantService::class.java)); status.text = "صادق متوقف شد."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, AssistantService::class.java))
            status.text = "صادق فعال شد. بگو: «صادق، کال مامان» یا «صادق، تلگرام را باز کن»"
        } else status.text = "بدون دسترسی میکروفون کار نمی‌کند."
    }
}
