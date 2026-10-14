package com.recentsshield

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val hidden = mutableSetOf<String>()

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hidden.addAll(Prefs.hidden(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), 0)
        }

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener { copyDiagnostics() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        summary = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            setPadding(0, px(4), 0, px(8))
        }
        root.addView(summary)

        root.addView(TextView(this).apply {
            text = "Tick the apps whose Recents preview should be blacked out:"
            textSize = 14f
            setPadding(0, px(4), 0, px(4))
        })

        search = EditText(this).apply {
            hint = "Search apps"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        root.addView(search)

        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos].pkg) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isServiceOn()) {
            "✅ Recents Shield is ON"
        } else {
            "⚠️ Recents Shield is OFF. Tap Accessibility settings → Installed apps → Recents Shield → turn it on."
        }
        summary.text = Prefs.summary(this)
            ?: "After turning it on, open Recents once. A short check result will appear here."
    }

    private fun isServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ShieldService::class.java)
        return enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            runOnUiThread {
                allApps = apps
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase() ?: ""
        shown = allApps
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.pkg.lowercase() }
            .sortedWith(compareBy<AppEntry>({ it.pkg !in hidden }, { it.label.lowercase() }))
        adapter.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        if (!hidden.remove(pkg)) hidden.add(pkg)
        Prefs.setHidden(this, hidden)
        adapter.notifyDataSetChanged()
    }

    private fun copyDiagnostics() {
        val f = Prefs.dumpFile(this)
        if (!f.exists()) {
            Toast.makeText(this, "Nothing yet. Open Recents once with the service on.", Toast.LENGTH_LONG).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Recents Shield diagnostics", f.readText()))
        Toast.makeText(this, "Copied. Paste it to Claude.", Toast.LENGTH_SHORT).show()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, px(8))
                addView(ImageView(context), LinearLayout.LayoutParams(px(40), px(40)))
                addView(TextView(context).apply {
                    textSize = 16f
                    setPadding(px(12), 0, px(8), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(CheckBox(context).apply {
                    isClickable = false
                    isFocusable = false
                })
            }
            val app = shown[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            (row.getChildAt(2) as CheckBox).isChecked = app.pkg in hidden
            return row
        }
    }
}
