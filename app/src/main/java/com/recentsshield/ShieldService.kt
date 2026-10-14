package com.recentsshield

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * How it works:
 *  - Tapping the Recents button (or any change on the Recents screen) triggers a scan of
 *    the home app's accessibility tree on a background thread. A scan lists every card.
 *  - While Recents is open, every display frame we re-read just the cards' live positions
 *    and names (cheap), and redraw the black boxes, nudged ahead by the scroll speed so
 *    they don't trail behind. Full rescans keep running in the background to pick up
 *    cards scrolling into view.
 *  - Cards stacked in front of a hidden card, and badges, are cut out of the black.
 *  - Which apps are hidden = the "Always" list plus any schedule active right now.
 *  - When an app opens, Home/Back is pressed, or Recents goes away, the overlay is removed.
 */
class ShieldService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scanThread = HandlerThread("recents-scan").apply { start() }
    private val scanHandler = Handler(scanThread.looper)
    private val scanQueued = AtomicBoolean(false)

    private lateinit var overlay: ShieldOverlay
    private lateinit var prefs: SharedPreferences

    @Volatile private var homePackages: Set<String> = emptySet()

    // What to hide. Labels are looked up once per settings change; which of them are
    // active is recomputed whenever the minute changes (schedules).
    @Volatile private var labelsByPkg: Map<String, List<String>> = emptyMap()
    @Volatile private var always: Set<String> = emptySet()
    @Volatile private var schedules: List<Schedule> = emptyList()
    @Volatile private var activeHidden: List<HiddenApp> = emptyList()
    @Volatile private var activeMinute = -1L

    // State shared with the scan thread
    @Volatile private var active = false
    @Volatile private var armedUntil = 0L    // after a Recents tap, keep trying while Recents opens

    // Main-thread state
    private var suppressUntil = 0L           // just after leaving Recents, ignore stale scans
    private var misses = 0
    private var lastIdleScan = 0L
    private var lastActiveScanRequest = 0L
    private var cards: List<Card> = emptyList()
    private val motion = HashMap<AccessibilityNodeInfo, Motion>()

    private class Motion(var x: Float, var t: Long, var vx: Float)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_HIDDEN || key == Schedules.KEY) loadSettings()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = ShieldOverlay(this, getSystemService(WINDOW_SERVICE) as WindowManager)
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        homePackages = findHomePackages()
        loadSettings()
    }

    // ---------------------------------------------------------------- what to hide

    private fun loadSettings() {
        always = Prefs.hidden(this)
        schedules = Schedules.load(this)
        val pkgs = HashSet(always)
        schedules.forEach { pkgs.addAll(it.apps) }
        val pm = packageManager
        labelsByPkg = pkgs.associateWith { pkg ->
            val labels = LinkedHashSet<String>()
            try {
                labels.add(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim())
            } catch (_: Exception) {}
            try {
                pm.getLaunchIntentForPackage(pkg)?.let { li ->
                    pm.resolveActivity(li, 0)?.loadLabel(pm)?.toString()?.trim()?.let { labels.add(it) }
                }
            } catch (_: Exception) {}
            labels.filter { it.isNotBlank() }
        }
        activeMinute = -1
        hiddenNow()
    }

    /** The apps to hide right now. Cheap: only recomputed when the minute changes. */
    private fun hiddenNow(): List<HiddenApp> {
        val minute = System.currentTimeMillis() / 60_000
        if (minute != activeMinute) {
            val pkgs = Schedules.activePackages(always, schedules, Calendar.getInstance())
            activeHidden = pkgs.mapNotNull { pkg ->
                labelsByPkg[pkg]?.takeIf { it.isNotEmpty() }?.let { HiddenApp(pkg, it) }
            }
            activeMinute = minute
        }
        return activeHidden
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()

        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg == SYSTEM_UI) {
            when (navButton(e)) {
                NavButton.RECENTS -> {
                    scanHandler.postDelayed({ writeDiagnostics() }, DIAG_DELAY_MS)
                    if (active) {
                        // Recents pressed inside Recents jumps back to the last app.
                        leaveRecents(now)
                    } else if (hiddenNow().isNotEmpty()) {
                        armedUntil = now + ARMED_MS
                        suppressUntil = 0
                        requestScan()
                        startPeriodicScans()
                    }
                }
                NavButton.HOME, NavButton.BACK -> if (active || now < armedUntil) leaveRecents(now)
                null -> Unit
            }
            return
        }

        if (pkg in homePackages) {
            if (active) {
                if (now - lastActiveScanRequest > ACTIVE_EVENT_SCAN_MS) {
                    lastActiveScanRequest = now
                    requestScan()
                }
            } else if (now - lastIdleScan > IDLE_SCAN_MS && hiddenNow().isNotEmpty()) {
                lastIdleScan = now
                requestScan()
            }
            return
        }

        // Only a real app window coming to the front means "an app was opened from Recents".
        // Keyboards, popups, edge panels and toasts also send window events and used to
        // cancel the shield by mistake.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != SYSTEM_UI && pkg != packageName && (active || now < armedUntil) &&
            isFrontAppWindow(e.windowId)
        ) {
            leaveRecents(now)
        }
    }

    private enum class NavButton { RECENTS, HOME, BACK }

    private fun navButton(e: AccessibilityEvent): NavButton? {
        val id = try { e.source?.viewIdResourceName } catch (_: Exception) { null }?.substringAfter(":id/") ?: ""
        when (id) {
            "recent_apps", "recents" -> return NavButton.RECENTS
            "home", "home_button" -> return NavButton.HOME
            "back", "back_button" -> return NavButton.BACK
        }
        val desc = (e.contentDescription?.toString() ?: e.text.joinToString(" ")).trim().lowercase()
        return when {
            "recent" in desc || "overview" in desc -> NavButton.RECENTS
            desc == "home" -> NavButton.HOME
            desc == "back" -> NavButton.BACK
            else -> null
        }
    }

    private fun isFrontAppWindow(windowId: Int): Boolean = try {
        windows.firstOrNull { it.id == windowId }?.let {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
        } ?: false
    } catch (_: Exception) { false }

    private fun leaveRecents(now: Long) {
        suppressUntil = now + SUPPRESS_MS
        armedUntil = 0
        goIdle()
    }

    // ---------------------------------------------------------------- scanning (background)

    private fun requestScan() {
        if (!scanQueued.compareAndSet(false, true)) return
        scanHandler.post {
            scanQueued.set(false)
            val root = findHomeRoot()
            val result = if (root != null) {
                try { RecentsScanner.scan(root, hiddenNow()) } catch (_: Exception) { null }
            } else null
            main.post { applyScan(result) }
        }
    }

    private val periodicScan = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (!active && now >= armedUntil) return
            requestScan()
            scanHandler.postDelayed(this, if (now < armedUntil) FAST_SCAN_MS else PERIODIC_SCAN_MS)
        }
    }

    private fun startPeriodicScans() {
        scanHandler.removeCallbacks(periodicScan)
        scanHandler.postDelayed(periodicScan, FAST_SCAN_MS)
    }

    private fun applyScan(result: RecentsScanner.Result?) {
        val now = SystemClock.uptimeMillis()
        if (result != null && result.isRecents) {
            if (now < suppressUntil) return
            misses = 0
            cards = result.cards
            if (!active) {
                active = true
                startPeriodicScans()
                Choreographer.getInstance().postFrameCallback(frame)
            }
            return
        }
        if (active && now >= armedUntil && ++misses >= 2) goIdle()
    }

    private fun goIdle() {
        active = false
        misses = 0
        cards = emptyList()
        motion.clear()
        scanHandler.removeCallbacks(periodicScan)
        Choreographer.getInstance().removeFrameCallback(frame)
        overlay.hide()
    }

    // ---------------------------------------------------------------- per-frame tracking (main)

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            trackAndDraw()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private class Live(val card: Card, val hidden: Boolean, val scene: Rect, val icon: Rect?, val z: Float)

    private fun trackAndDraw() {
        val hidden = hiddenNow()
        val current = cards
        if (hidden.isEmpty() || current.isEmpty()) {
            overlay.show(emptyList())
            return
        }
        val now = SystemClock.uptimeMillis()
        val screen = resources.displayMetrics
        val cx = screen.widthPixels / 2f
        val cy = screen.heightPixels / 2f
        val maxShift = screen.widthPixels * 0.12f

        val live = ArrayList<Live>(current.size)
        for (c in current) {
            val ok = try { c.node.refresh() } catch (_: Exception) { false }
            if (!ok || !c.node.isVisibleToUser) continue
            val r = Rect().also { c.node.getBoundsInScreen(it) }
            if (r.width() <= 0 || r.height() <= 0) continue
            // Re-read the name every frame: scrolling can recycle this view for another app.
            val label = RecentsScanner.labelOf(c.node).ifEmpty { c.label }
            val isHidden = RecentsScanner.isHidden(label, hidden)
            r.top += (r.height() * c.sceneTopRatio).toInt()

            var icon: Rect? = null
            if (c.icon != null) {
                val iok = try { c.icon.refresh() } catch (_: Exception) { false }
                if (iok && c.icon.isVisibleToUser) {
                    val ir = Rect().also { c.icon.getBoundsInScreen(it) }
                    if (ir.width() > 0 && ir.height() > 0) icon = ir
                }
            }

            // Predict where the card will be when this frame is shown, from its scroll speed.
            val m = motion[c.node]
            val x = r.exactCenterX()
            if (m == null) {
                motion[c.node] = Motion(x, now, 0f)
            } else {
                val dt = (now - m.t).coerceAtLeast(1L)
                val inst = (x - m.x) / dt
                m.vx = if (dt > 100) 0f else m.vx * 0.4f + inst * 0.6f
                m.x = x
                m.t = now
                val shift = (m.vx * LEAD_MS).coerceIn(-maxShift, maxShift).toInt()
                if (abs(shift) >= 2) {
                    r.offset(shift, 0)
                    icon?.offset(shift, 0)
                }
            }

            // Cards in front are drawn bigger (the middle of the carousel); ties go to the
            // one nearer the middle, then to drawing order.
            val dist = hypot(r.exactCenterX() - cx, r.exactCenterY() - cy)
            val z = r.height() * 1000f - dist + c.drawingOrder * 0.001f
            live.add(Live(c, isHidden, r, icon, z))
        }

        val shields = ArrayList<Shield>()
        for (h in live) {
            if (!h.hidden) continue
            val cuts = ArrayList<Rect>()
            val isIcon = ArrayList<Boolean>()
            h.icon?.let { cuts.add(it); isIcon.add(true) } // keep its own badge visible
            for (o in live) {
                if (o === h || o.z <= h.z) continue
                if (!o.hidden && Rect.intersects(o.scene, h.scene)) {
                    cuts.add(o.scene); isIcon.add(false)
                }
                o.icon?.let { if (Rect.intersects(it, h.scene)) { cuts.add(it); isIcon.add(true) } }
            }
            shields.add(Shield(h.scene, cuts, isIcon))
        }
        overlay.show(shields)
    }

    // ---------------------------------------------------------------- helpers

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

    /** Saves what the Recents screen looked like, so problems can be diagnosed. Runs on the scan thread. */
    private fun writeDiagnostics() {
        try {
            val hidden = hiddenNow()
            val root = findHomeRoot()
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            if (root == null) {
                Prefs.setSummary(this, "$time: Recents button seen, but the Recents screen couldn't be read.")
                Prefs.dumpFile(this).writeText("No home-app window found. Home packages: $homePackages\n")
                return
            }
            val r = RecentsScanner.scan(root, hidden)
            val found = r.cards.filter { RecentsScanner.isHidden(it.label, hidden) }.map { it.label }
            val foundText = if (found.isEmpty()) "none" else found.joinToString(", ")
            Prefs.setSummary(
                this,
                "$time: Recents screen recognised: ${if (r.isRecents) "yes" else "NO"}. Hidden cards on screen: $foundText.",
            )
            val b = Rect()
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
            val header = buildString {
                append("Version: $version\n")
                append("Recents recognised: ${r.isRecents}\n")
                append("Hidden now: ${hidden.joinToString { "${it.pkg} ${it.labels}" }}\n")
                append("Always: $always\n")
                append("Schedules: ${schedules.joinToString { "${it.name} on=${it.enabled} days=${it.days} ${it.startMin}-${it.endMin} apps=${it.apps}" }}\n")
                append("Cards:\n")
                for (c in r.cards) {
                    c.node.getBoundsInScreen(b)
                    append("  ${c.label} card=${b.toShortString()} sceneTop=${c.sceneTopRatio} z=${c.drawingOrder}\n")
                }
                append('\n')
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
        scanThread.quitSafely()
        super.onDestroy()
    }

    private fun cleanup() {
        main.post { goIdle() }
        scanHandler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val FAST_SCAN_MS = 30L        // while Recents is opening
        private const val PERIODIC_SCAN_MS = 120L   // while Recents is open (positions update every frame regardless)
        private const val ACTIVE_EVENT_SCAN_MS = 60L
        private const val IDLE_SCAN_MS = 150L
        private const val ARMED_MS = 1200L
        private const val SUPPRESS_MS = 600L
        private const val LEAD_MS = 16L             // how far ahead to predict scrolling
        private const val DIAG_DELAY_MS = 700L
    }
}
