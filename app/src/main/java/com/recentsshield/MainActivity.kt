package com.recentsshield

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_ALWAYS = 1
    }

    private lateinit var status: TextView
    private lateinit var batteryRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var hiddenNow: TextView
    private lateinit var alwaysText: TextView
    private lateinit var scheduleList: LinearLayout

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(24))
        }

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, weight())
        buttons.addView(Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener { copyDiagnostics() }
        }, weight())
        root.addView(buttons)

        // Battery warning (only shown when the app can be put to sleep)
        batteryRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, px(4))
        }
        batteryRow.addView(TextView(this).apply {
            text = "⚠️ Battery optimization is on, so Samsung may put Recents Shield to sleep and the " +
                "shield will stop working until you reopen this app."
            textSize = 14f
        })
        batteryRow.addView(Button(this).apply {
            text = "Let it run in the background"
            setOnClickListener { requestBatteryExemption() }
        })
        root.addView(batteryRow)

        summary = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            setPadding(0, px(4), 0, px(4))
        }
        root.addView(summary)

        hiddenNow = TextView(this).apply {
            textSize = 15f
            setPadding(0, px(8), 0, 0)
        }
        root.addView(hiddenNow)

        // Always hidden
        root.addView(sectionTitle("Always hidden"))
        val alwaysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alwaysText = TextView(this).apply { textSize = 15f }
        alwaysRow.addView(alwaysText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alwaysRow.addView(Button(this).apply {
            text = "Choose"
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    AppPickerActivity.intent(this@MainActivity, "Always hidden", Prefs.hidden(this@MainActivity)),
                    REQ_ALWAYS,
                )
            }
        })
        root.addView(alwaysRow)

        // Schedules
        root.addView(sectionTitle("Schedules"))
        root.addView(TextView(this).apply {
            text = "Hide apps only on certain days and hours."
            textSize = 13f
            alpha = 0.75f
        })
        scheduleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scheduleList)
        root.addView(Button(this).apply {
            text = "+ Add schedule"
            setOnClickListener { startActivity(ScheduleActivity.intent(this@MainActivity, null)) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = if (isServiceOn()) {
            "✅ Recents Shield is ON"
        } else {
            "⚠️ Recents Shield is OFF. Tap Accessibility settings → Installed apps → Recents Shield → turn it on."
        }
        batteryRow.visibility = if (isBatteryOptimized()) View.VISIBLE else View.GONE
        summary.text = Prefs.summary(this)
            ?: "After turning it on, open Recents once. A short check result will appear here."

        val always = Prefs.hidden(this)
        alwaysText.text = if (always.isEmpty()) "None" else appNames(this, always)

        val schedules = Schedules.load(this)
        val now = Schedules.activePackages(always, schedules)
        hiddenNow.text = if (now.isEmpty()) "Hidden right now: nothing" else "Hidden right now: ${appNames(this, now, 5)}"

        scheduleList.removeAllViews()
        if (schedules.isEmpty()) {
            scheduleList.addView(TextView(this).apply {
                text = "No schedules yet."
                textSize = 14f
                setPadding(0, px(8), 0, px(4))
            })
        }
        for (s in schedules) scheduleList.addView(scheduleRow(s))
    }

    private fun scheduleRow(s: Schedule): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10))
            isClickable = true
            setOnClickListener { startActivity(ScheduleActivity.intent(this@MainActivity, s.id)) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val activeNow = s.isActive(java.util.Calendar.getInstance())
        texts.addView(TextView(this).apply {
            text = (s.name.ifBlank { "Schedule" }) + if (activeNow) "  • active now" else ""
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = "${Schedules.daysText(s.days)} · ${Schedules.rangeText(this@MainActivity, s)}"
            textSize = 14f
        })
        texts.addView(TextView(this).apply {
            text = if (s.apps.isEmpty()) "No apps" else appNames(this@MainActivity, s.apps)
            textSize = 13f
            alpha = 0.75f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = s.enabled
            setOnCheckedChangeListener { _, on ->
                Schedules.upsert(this@MainActivity, s.copy(enabled = on))
                refresh()
            }
        })
        return row
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ALWAYS && resultCode == RESULT_OK) {
            AppPickerActivity.result(data)?.let { Prefs.setHidden(this, it) }
            refresh()
        }
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(20), 0, px(4))
    }

    private fun isServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ShieldService::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Fall back to this app's settings page (Battery → Unrestricted).
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun copyDiagnostics() {
        val f = Prefs.dumpFile(this)
        val dump = if (f.exists()) f.readText() else "(No Recents snapshot yet. Open Recents once with the service on.)"
        val text = buildString {
            append("Service on: ${isServiceOn()}, battery optimized: ${isBatteryOptimized()}\n\n")
            append("=== Event log ===\n")
            append(Diag.read(this@MainActivity).takeLast(12_000).ifEmpty { "(empty)\n" })
            append("\n=== Last Recents snapshot ===\n")
            append(dump)
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Recents Shield diagnostics", text))
        Toast.makeText(this, "Copied. Paste it to Claude.", Toast.LENGTH_SHORT).show()
    }
}
