package com.recentsshield

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.DateFormat
import java.util.Date

/**
 * How it works:
 *  1. Tapping the Recents button (3-button nav) fires a click event from System UI.
 *     We immediately cover the WHOLE screen in black, before Recents has even drawn.
 *  2. We then poll the home app's accessibility tree every ~30 ms. Once the Recents
 *     screen is recognised, the full-screen black is swapped for black boxes over only
 *     the cards of hidden apps, which keep following the cards as you scroll.
 *  3. When another app comes to the front (or Recents goes away), the overlay is removed.
 */
class ShieldService : AccessibilityService() {

    private enum class Mode { IDLE, PENDING, RECENTS }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: ShieldOverlay
    private lateinit var prefs: SharedPreferences

    private var homePackages: Set<String> = emptySet()
    @Volatile private var hidden: List<HiddenApp> = emptyList()

    private var mode = Mode.IDLE
    private var pendingSince = 0L
    private var misses = 0
    private var lastIdleScan = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_HIDDEN) loadHidden()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (mode == Mode.IDLE) return
            scan()
            if (mode != Mode.IDLE) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = ShieldOverlay(this, getSystemService(WINDOW_SERVICE) as WindowManager)
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        homePackages = findHomePackages()
        loadHidden()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        val pkg = e.packageName?.toString() ?: return

        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg == SYSTEM_UI && isRecentsButton(e)) {
            onRecentsButton()
            return
        }

        if (pkg in homePackages) {
            when {
                mode != Mode.IDLE -> scan() // react to scrolls/changes right away, not just on the next poll
                e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scan()
                else -> {
                    // Recents opened some other way (e.g. from the home screen). Throttled.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastIdleScan > IDLE_SCAN_MS) {
                        lastIdleScan = now
                        scan()
                    }
                }
            }
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != SYSTEM_UI && pkg != packageName && mode == Mode.RECENTS
        ) {
            // An app was opened from Recents.
            goIdle()
        }
    }

    private fun isRecentsButton(e: AccessibilityEvent): Boolean {
        val id = try { e.source?.viewIdResourceName } catch (_: Exception) { null } ?: ""
        if (id.endsWith("/recent_apps") || id.endsWith("/recents")) return true
        val desc = (e.contentDescription?.toString() ?: e.text.joinToString(" ")).lowercase()
        return "recent" in desc || "overview" in desc
    }

    private fun onRecentsButton() {
        handler.postDelayed({ writeDiagnostics() }, DIAG_DELAY_MS)

        if (mode == Mode.RECENTS) {
            // Pressing the button while in Recents jumps back to the last app;
            // polling notices Recents going away and clears the overlay.
            return
        }
        if (hidden.isEmpty()) return

        // Black out everything first; reveal safe cards once we can see where they are.
        overlay.showFull()
        mode = Mode.PENDING
        pendingSince = SystemClock.uptimeMillis()
        misses = 0
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun scan() {
        val root = findHomeRoot()
        val result = if (root != null) {
            try { RecentsScanner.scan(root, hidden) } catch (_: Exception) { null }
        } else null

        if (result != null && result.isRecents) {
            misses = 0
            if (hidden.isEmpty()) { goIdle(); return }
            overlay.showRects(result.rects)
            if (mode == Mode.IDLE) {
                mode = Mode.RECENTS
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, POLL_MS)
            } else {
                mode = Mode.RECENTS
            }
            return
        }

        when (mode) {
            Mode.PENDING ->
                if (SystemClock.uptimeMillis() - pendingSince > PENDING_TIMEOUT_MS) goIdle()
            Mode.RECENTS ->
                if (++misses >= 2) goIdle()
            Mode.IDLE -> Unit
        }
    }

    private fun goIdle() {
        mode = Mode.IDLE
        misses = 0
        handler.removeCallbacks(poll)
        overlay.hide()
    }

    private fun findHomeRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString()?.let { it in homePackages } == true) return r
            }
        } catch (_: Exception) {}
        val r = try { rootInActiveWindow } catch (_: Exception) { null }
        return if (r?.packageName?.toString()?.let { it in homePackages } == true) r else null
    }

    private fun findHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.settings" }
            .toMutableSet()
        found.add("com.sec.android.app.launcher") // Samsung One UI Home
        return found
    }

    private fun loadHidden() {
        val pm = packageManager
        hidden = Prefs.hidden(this).mapNotNull { pkg ->
            val labels = LinkedHashSet<String>()
            try {
                labels.add(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim())
            } catch (_: Exception) {}
            try {
                pm.getLaunchIntentForPackage(pkg)?.let { li ->
                    pm.resolveActivity(li, 0)?.loadLabel(pm)?.toString()?.trim()?.let { labels.add(it) }
                }
            } catch (_: Exception) {}
            labels.removeAll { it.isBlank() }
            if (labels.isEmpty()) null else HiddenApp(pkg, labels.toList())
        }
    }

    /** Saves what the Recents screen looked like, so problems can be diagnosed. */
    private fun writeDiagnostics() {
        try {
            val root = findHomeRoot()
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            if (root == null) {
                Prefs.setSummary(this, "$time: Recents button seen, but the Recents screen couldn't be read.")
                Prefs.dumpFile(this).writeText("No home-app window found. Home packages: $homePackages\n")
                return
            }
            val r = RecentsScanner.scan(root, hidden)
            val found = if (r.matched.isEmpty()) "none" else r.matched.joinToString(", ")
            Prefs.setSummary(
                this,
                "$time: Recents screen recognised: ${if (r.isRecents) "yes" else "NO"}. Hidden cards covered: $found.",
            )
            val header = buildString {
                append("Recents recognised: ${r.isRecents}\n")
                append("Hidden apps: ${hidden.joinToString { "${it.pkg} ${it.labels}" }}\n")
                append("Covered: $found\n")
                append("Boxes: ${r.rects.joinToString { it.toShortString() }}\n\n")
            }
            Prefs.dumpFile(this).writeText(header + RecentsScanner.dump(root))
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        mode = Mode.IDLE
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val POLL_MS = 30L
        private const val IDLE_SCAN_MS = 150L
        private const val PENDING_TIMEOUT_MS = 1500L
        private const val DIAG_DELAY_MS = 700L
    }
}
