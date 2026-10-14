package com.recentsshield

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
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

/** Pick a set of apps. Returns the chosen package names in [EXTRA_SELECTED]. */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_TITLE = "title"

        fun intent(c: Context, title: String, selected: Set<String>) =
            Intent(c, AppPickerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))

        fun result(data: Intent?): Set<String>? =
            data?.getStringArrayListExtra(EXTRA_SELECTED)?.toSet()
    }

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var search: EditText
    private lateinit var count: TextView
    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Choose apps"
        selected.addAll(
            savedInstanceState?.getStringArrayList(EXTRA_SELECTED)
                ?: intent.getStringArrayListExtra(EXTRA_SELECTED) ?: emptyList()
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
        }

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
            adapter = this@AppPickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos].pkg) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        count = TextView(this).apply { textSize = 14f }
        bottom.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { finishWithResult() }
        })
        root.addView(bottom)

        setContentView(root)
        updateCount()
        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXTRA_SELECTED, ArrayList(selected))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back also keeps the selection, like most Android pickers.
        finishWithResult()
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)))
        finish()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            runOnUiThread {
                // Selected apps first, so it's easy to see what's chosen.
                allApps = apps.sortedWith(compareBy<AppEntry>({ it.pkg !in selected }, { it.label.lowercase() }))
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text?.toString()?.trim()?.lowercase() ?: ""
        shown = allApps.filter { q.isEmpty() || q in it.label.lowercase() || q in it.pkg.lowercase() }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        if (!selected.remove(pkg)) selected.add(pkg)
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        count.text = when (selected.size) {
            0 -> "No apps selected"
            1 -> "1 app selected"
            else -> "${selected.size} apps selected"
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
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
            (row.getChildAt(2) as CheckBox).isChecked = app.pkg in selected
            return row
        }
    }
}
