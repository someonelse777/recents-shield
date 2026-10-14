package com.recentsshield

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton

/** Create or edit one schedule. */
class ScheduleActivity : Activity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val REQ_APPS = 1

        fun intent(c: Context, id: String?) =
            Intent(c, ScheduleActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) }
    }

    private lateinit var schedule: Schedule
    private var isNew = true

    private lateinit var name: EditText
    private lateinit var enabled: Switch
    private val dayButtons = LinkedHashMap<Int, ToggleButton>()
    private lateinit var allDay: CheckBox
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var timeRow: LinearLayout
    private lateinit var overnightNote: TextView
    private lateinit var appsText: TextView

    private var startMin = 9 * 60
    private var endMin = 17 * 60
    private var apps: Set<String> = emptySet()

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val existing = id?.let { i -> Schedules.load(this).firstOrNull { it.id == i } }
        isNew = existing == null
        schedule = existing ?: Schedule(days = setOf(2, 3, 4, 5, 6))
        title = if (isNew) "New schedule" else "Edit schedule"
        startMin = schedule.startMin
        endMin = schedule.endMin
        apps = schedule.apps

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(16))
        }

        // Name + on/off
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        name = EditText(this).apply {
            hint = "Name (e.g. Work hours)"
            isSingleLine = true
            setText(schedule.name)
        }
        nameRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        enabled = Switch(this).apply {
            text = "On "
            isChecked = schedule.enabled
        }
        nameRow.addView(enabled)
        root.addView(nameRow)

        // Days
        root.addView(sectionTitle("Days"))
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in Schedules.WEEK_ORDER) {
            val label = Schedules.dayShort(d)
            val b = ToggleButton(this).apply {
                textOn = label
                textOff = label
                isChecked = d in schedule.days
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }
            dayButtons[d] = b
            dayRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(dayRow)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.addView(smallButton("Every day") { setDays((1..7).toSet()) }, weight())
        presets.addView(smallButton("Weekdays") { setDays(setOf(2, 3, 4, 5, 6)) }, weight())
        presets.addView(smallButton("Weekends") { setDays(setOf(1, 7)) }, weight())
        root.addView(presets)

        // Hours
        root.addView(sectionTitle("Hours"))
        allDay = CheckBox(this).apply {
            text = "All day"
            isChecked = schedule.allDay
            setOnCheckedChangeListener { _, _ -> refreshTimes() }
        }
        root.addView(allDay)
        timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startBtn = Button(this).apply { setOnClickListener { pickTime(true) } }
        endBtn = Button(this).apply { setOnClickListener { pickTime(false) } }
        timeRow.addView(TextView(this).apply { text = "From " })
        timeRow.addView(startBtn, weight())
        timeRow.addView(TextView(this).apply { text = "  to " })
        timeRow.addView(endBtn, weight())
        root.addView(timeRow)
        overnightNote = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            text = "Ends the next morning (runs past midnight)."
        }
        root.addView(overnightNote)

        // Apps
        root.addView(sectionTitle("Apps"))
        val appsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsText = TextView(this).apply { textSize = 15f }
        appsRow.addView(appsText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appsRow.addView(Button(this).apply {
            text = "Choose"
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(AppPickerActivity.intent(this@ScheduleActivity, "Apps for this schedule", apps), REQ_APPS)
            }
        })
        root.addView(appsRow)

        // Save / delete
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(24), 0, 0)
        }
        if (!isNew) {
            actions.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener { confirmDelete() }
            }, weight())
        }
        actions.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        }, weight())
        root.addView(actions)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshTimes()
        refreshApps()
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, px(18), 0, px(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun setDays(days: Set<Int>) {
        dayButtons.forEach { (d, b) -> b.isChecked = d in days }
    }

    private fun refreshTimes() {
        val all = allDay.isChecked
        timeRow.isEnabled = !all
        startBtn.isEnabled = !all
        endBtn.isEnabled = !all
        startBtn.text = Schedules.timeText(this, startMin)
        endBtn.text = Schedules.timeText(this, endMin)
        timeRow.alpha = if (all) 0.4f else 1f
        overnightNote.visibility = if (!all && startMin > endMin) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun pickTime(start: Boolean) {
        val cur = if (start) startMin else endMin
        TimePickerDialog(
            this,
            { _, h, m ->
                if (start) startMin = h * 60 + m else endMin = h * 60 + m
                refreshTimes()
            },
            cur / 60, cur % 60,
            android.text.format.DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun refreshApps() {
        appsText.text = if (apps.isEmpty()) "No apps chosen" else appNames(this, apps)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_APPS && resultCode == RESULT_OK) {
            AppPickerActivity.result(data)?.let { apps = it; refreshApps() }
        }
    }

    private fun save() {
        val days = dayButtons.filterValues { it.isChecked }.keys
        if (days.isEmpty()) {
            Toast.makeText(this, "Pick at least one day.", Toast.LENGTH_SHORT).show()
            return
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Choose at least one app.", Toast.LENGTH_SHORT).show()
            return
        }
        val all = allDay.isChecked
        if (!all && startMin == endMin) {
            Toast.makeText(this, "Start and end are the same. Tick \"All day\" instead.", Toast.LENGTH_LONG).show()
            return
        }
        val s = schedule.copy(
            name = name.text.toString().trim(),
            enabled = enabled.isChecked,
            days = days.toSet(),
            startMin = if (all) 0 else startMin,
            endMin = if (all) 0 else endMin,
            apps = apps,
        )
        Schedules.upsert(this, s)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage("Delete this schedule?")
            .setPositiveButton("Delete") { _, _ ->
                Schedules.delete(this, schedule.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** "AP News, Claude and 2 more" */
fun appNames(c: Context, pkgs: Collection<String>, max: Int = 3): String {
    val pm = c.packageManager
    val names = pkgs.map { pkg ->
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    }.sortedBy { it.lowercase() }
    return if (names.size <= max) names.joinToString(", ")
    else names.take(max).joinToString(", ") + " and ${names.size - max} more"
}
