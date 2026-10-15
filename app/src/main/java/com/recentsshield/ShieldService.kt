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
import kotlin.math.hypot

/**
 * Two threads, so drawing can never be held up:
 *
 *  WORKER thread: everything that asks another app something (window list, Recents cards,
 *  card positions). Those questions can wait up to several seconds when the other app is
 *  busy animating, which is exactly when Recents opens. The worker:
 *    - decides on/off from what's on screen: an app in front -> off; Recents showing with
 *      cards -> on. Events (button taps, window changes) only tell it to look closely.
 *    - while on, re-reads card positions ~30 times a second (a full rescan every 250 ms)
 *      and publishes the boxes plus how fast each card is moving.
 *
 *  MAIN thread: only draws. Every display frame it takes the latest published boxes and
 *  moves them ahead by each card's speed, so they keep up with scrolling. It never waits
 *  on another app.
 */
class ShieldService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("recents-shield-worker").apply { start() }
    private val worker = Handler(workerThread.looper)

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

    // ---- worker-thread state (only touched on the worker, except reads of `active`)
    @Volatile private var active = false
    private var armedUntil = 0L
    private var misses = 0
    private var cards: List<Card> = emptyList()
    private var lastFullScan = 0L
    private var tickScheduled = false
    private val motion = HashMap<AccessibilityNodeInfo, Motion>()
    private var ticks = 0
    private var ticksWithBoxes = 0
    private var failedReads = 0
    private var lastDiag = 0L

    private class Motion(var x: Float, var t: Long, var vx: Float)

    /** Boxes published by the worker for the main thread to draw. [vx] is px/ms per shield. */
    private class Snapshot(val shields: List<Shield>, val vx: FloatArray, val time: Long)

    @Volatile private var snapshot: Snapshot? = null

    // ---- main-thread state
    private var drawing = false
    private var lastHomeEvent = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_HIDDEN || key == Schedules.KEY) worker.post { loadSettings() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = ShieldOverlay(this, getSystemService(WINDOW_SERVICE) as WindowManager)
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        homePackages = findHomePackages()
        loadSettings()
        Diag.log(this, "Service started")
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

    // ---------------------------------------------------------------- events (main thread, no waiting)

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        guarded("event") { handleEvent(e) }
    }

    private fun handleEvent(e: AccessibilityEvent) {
        // Only cheap fields of the event are read here. Nothing that asks another app.
        val pkg = e.packageName?.toString() ?: return
        val type = e.eventType
        val now = SystemClock.uptimeMillis()

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg == SYSTEM_UI) {
            // A navigation button (or something else in System UI) was tapped: look closely.
            worker.post { guarded("arm") { arm(); scheduleDiagnostics() } }
            return
        }
        if (pkg in homePackages) {
            // While on: refresh the card list soon. While off: one quick check (in case Recents
            // was opened some other way), throttled so a busy home screen costs little.
            val interval = if (active) HOME_EVENT_MS else IDLE_HOME_EVENT_MS
            if (now - lastHomeEvent > interval) {
                lastHomeEvent = now
                worker.post {
                    guarded("home") {
                        if (active) lastFullScan = 0L else if (hiddenNow().isNotEmpty()) scheduleTick(0)
                    }
                }
            }
            return
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != SYSTEM_UI && pkg != packageName) {
            worker.post { guarded("window") { if (active || SystemClock.uptimeMillis() < armedUntil) arm() } }
        }
    }

    // ---------------------------------------------------------------- worker thread

    /** Check right away, then every 30 ms for a little while. */
    private fun arm() {
        if (hiddenNow().isEmpty() && !active) return
        armedUntil = SystemClock.uptimeMillis() + ARMED_MS
        lastFullScan = 0L
        worker.removeCallbacks(tick)
        tickScheduled = false
        scheduleTick(0)
    }

    private fun scheduleTick(delay: Long) {
        if (tickScheduled) return
        tickScheduled = true
        worker.postDelayed(tick, delay)
    }

    private val tick = Runnable {
        tickScheduled = false
        guarded("tick") { doTick() }
        val now = SystemClock.uptimeMillis()
        if (active || now < armedUntil) scheduleTick(TICK_MS)
    }

    private fun doTick() {
        val now = SystemClock.uptimeMillis()
        val hidden = hiddenNow()
        if (hidden.isEmpty()) {
            if (active) turnOff(now, "nothing to hide right now")
            return
        }

        val ws = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }

        // 1) Is a regular app in front? Then Recents isn't showing.
        val front = frontApp(ws)
        if (front != null) {
            if (active) turnOff(now, "$front is in front")
            return
        }

        // 2) Full read of the Recents screen when starting, and every 250 ms while on.
        if (!active || cards.isEmpty() || now - lastFullScan >= FULL_SCAN_MS) {
            lastFullScan = now
            val res = readRecents(ws, hidden)
            if (res == null || !res.isRecents) {
                if (active && ++misses >= 3) {
                    turnOff(now, "Recents no longer showing (" + (if (res == null) "couldn't read the screen" else "no Recents cards found") + ")")
                }
                return
            }
            misses = 0
            cards = res.cards
            if (!active) {
                active = true
                ticks = 0; ticksWithBoxes = 0; failedReads = 0; framesDrawn = 0
                Diag.log(this, "Shield on")
                main.post { startDrawing() }
            }
        }

        // 3) Fresh positions for the boxes.
        publish(hidden, now)
    }

    // Which app each window belongs to, so we ask each window only once.
    private val windowPkg = HashMap<Int, String>()

    private fun frontApp(ws: List<AccessibilityWindowInfo>): String? {
        if (windowPkg.size > 64) windowPkg.clear()
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isFocused) continue
            val pkg = windowPkg[w.id] ?: (try { w.root?.packageName?.toString() } catch (_: Exception) { null })
                ?.also { windowPkg[w.id] = it } ?: continue
            if (pkg !in homePackages && pkg != packageName) return pkg
        }
        return null
    }

    /** Reads the home app's window(s). Null if none could be read. */
    private fun readRecents(ws: List<AccessibilityWindowInfo>, hidden: List<HiddenApp>): RecentsScanner.Result? {
        val roots = ArrayList<Pair<AccessibilityNodeInfo, Boolean>>()
        for (w in ws) {
            val root = try { w.root } catch (_: Exception) { null } ?: continue
            if (root.packageName?.toString()?.let { it in homePackages } == true) roots.add(root to w.isFocused)
        }
        if (roots.isEmpty()) {
            val r = try { rootInActiveWindow } catch (_: Exception) { null }
            if (r != null && r.packageName?.toString()?.let { it in homePackages } == true) roots.add(r to true)
        }
        var result: RecentsScanner.Result? = null
        for ((root, _) in roots.sortedByDescending { it.second }) {
            val r = RecentsScanner.scan(root, hidden)
            if (r.isRecents) return r
            if (result == null) result = r
        }
        return result
    }

    private class Live(val hidden: Boolean, val scene: Rect, val icon: Rect?, val z: Float, val vx: Float)

    /** Re-read card positions and publish the boxes for drawing. */
    private fun publish(hidden: List<HiddenApp>, now: Long) {
        ticks++
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f

        val lives = ArrayList<Live>(cards.size)
        for (c in cards) {
            val ok = try { c.node.refresh() && c.node.isVisibleToUser } catch (_: Exception) { false }
            var r = Rect()
            if (ok) c.node.getBoundsInScreen(r)
            val label: String
            var icon: Rect? = null
            if (ok && r.width() > 0 && r.height() > 0) {
                // Re-read the name too: scrolling can recycle this view for another app.
                label = RecentsScanner.labelOf(c.node).ifEmpty { c.label }
                if (c.icon != null) {
                    val iok = try { c.icon.refresh() && c.icon.isVisibleToUser } catch (_: Exception) { false }
                    if (iok) {
                        val ir = Rect().also { c.icon.getBoundsInScreen(it) }
                        if (ir.width() > 0 && ir.height() > 0) icon = ir
                    }
                }
            } else {
                // Couldn't re-read (Samsung can swap the card's view): use the latest scan.
                failedReads++
                if (now - c.scannedAt > SCAN_FALLBACK_MS) continue
                r = Rect(c.scanBounds)
                label = c.label
                icon = c.scanIconBounds?.let { Rect(it) }
            }
            r.top += (r.height() * c.sceneTopRatio).toInt()

            // Speed of the card (px/ms), for the main thread to move the box ahead.
            val x = r.exactCenterX()
            val m = motion[c.node]
            val vx: Float
            if (m == null) {
                motion[c.node] = Motion(x, now, 0f)
                vx = 0f
            } else {
                val dt = (now - m.t).coerceAtLeast(1L)
                m.vx = if (dt > 150) 0f else m.vx * 0.3f + ((x - m.x) / dt) * 0.7f
                m.x = x
                m.t = now
                vx = m.vx
            }

            // Cards in front are drawn bigger (the middle of the carousel); ties go to the
            // one nearer the middle, then to drawing order.
            val dist = hypot(r.exactCenterX() - cx, r.exactCenterY() - cy)
            val z = r.height() * 1000f - dist + c.drawingOrder * 0.001f
            lives.add(Live(RecentsScanner.isHidden(label, hidden), r, icon, z, vx))
        }

        val shields = ArrayList<Shield>()
        val speeds = ArrayList<Float>()
        for (h in lives) {
            if (!h.hidden) continue
            val cuts = ArrayList<Rect>()
            val isIcon = ArrayList<Boolean>()
            h.icon?.let { cuts.add(it); isIcon.add(true) } // keep its own badge visible
            for (o in lives) {
                if (o === h || o.z <= h.z) continue
                if (!o.hidden && Rect.intersects(o.scene, h.scene)) { cuts.add(o.scene); isIcon.add(false) }
                o.icon?.let { if (Rect.intersects(it, h.scene)) { cuts.add(it); isIcon.add(true) } }
            }
            shields.add(Shield(h.scene, cuts, isIcon))
            speeds.add(h.vx)
        }
        if (shields.isNotEmpty()) ticksWithBoxes++
        snapshot = Snapshot(shields, speeds.toFloatArray(), now)
    }

    /** Switch off, but keep checking for a moment: if that was a misread, it comes straight back on. */
    private fun turnOff(now: Long, reason: String) {
        val ov = if (::overlay.isInitialized) overlay.stats() else "no overlay"
        Diag.log(this, "Shield off: $reason [checks=$ticks, with boxes=$ticksWithBoxes, failed re-reads=$failedReads, " +
            "frames=$framesDrawn, $ov]")
        active = false
        misses = 0
        cards = emptyList()
        motion.clear()
        snapshot = null
        main.post { stopDrawing() }
        armedUntil = now + RECHECK_AFTER_OFF_MS
    }

    // ---------------------------------------------------------------- drawing (main thread)

    private fun startDrawing() {
        if (drawing) return
        drawing = true
        if (prefs.getBoolean(Prefs.KEY_DEBUG_OUTLINE, false)) overlay.ensureAttached()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopDrawing() {
        drawing = false
        Choreographer.getInstance().removeFrameCallback(frame)
        overlay.hide()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!drawing) return
            guarded("draw") { drawLatest() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Volatile private var framesDrawn = 0

    private fun drawLatest() {
        framesDrawn++
        val s = snapshot ?: return
        val now = SystemClock.uptimeMillis()
        // Move each box ahead by its card's speed: time since the positions were read,
        // plus one frame. Capped, so a stalled read can't send a box flying.
        val dt = (now - s.time + LEAD_MS).coerceIn(0L, MAX_EXTRAPOLATE_MS).toFloat()
        val maxShift = resources.displayMetrics.widthPixels * 0.15f
        val out = ArrayList<Shield>(s.shields.size)
        for (i in s.shields.indices) {
            val sh = s.shields[i]
            val dx = (s.vx[i] * dt).coerceIn(-maxShift, maxShift).toInt()
            if (dx == 0) {
                out.add(sh)
            } else {
                out.add(Shield(Rect(sh.box).apply { offset(dx, 0) }, sh.cutouts.map { Rect(it).apply { offset(dx, 0) } }, sh.cutoutIsIcon))
            }
        }
        overlay.show(out)
    }

    // ---------------------------------------------------------------- helpers

    private fun findHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.settings" }
            .toMutableSet()
        found.add("com.sec.android.app.launcher") // Samsung One UI Home
        return found
    }

    private fun scheduleDiagnostics() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDiag < 1500) return
        lastDiag = now
        worker.postDelayed({ guarded("diagnostics") { writeDiagnostics() } }, DIAG_DELAY_MS)
    }

    /** Saves what the Recents screen looked like, so problems can be diagnosed. Worker thread. */
    private fun writeDiagnostics() {
        val hidden = hiddenNow()
        val ws = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val r = readRecents(ws, hidden)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        if (r == null) {
            Prefs.setSummary(this, "$time: Recents button seen, but the Recents screen couldn't be read.")
            Prefs.dumpFile(this).writeText("No home-app window found. Home packages: $homePackages\n")
            return
        }
        val found = r.cards.filter { RecentsScanner.isHidden(it.label, hidden) }.map { it.label }
        val foundText = if (found.isEmpty()) "none" else found.joinToString(", ")
        Prefs.setSummary(
            this,
            "$time: Recents screen recognised: ${if (r.isRecents) "yes" else "NO"}. Hidden cards on screen: $foundText.",
        )
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val root = r.cards.firstOrNull()?.node?.let { n ->
            var cur: AccessibilityNodeInfo = n
            while (true) { cur = try { cur.parent } catch (_: Exception) { null } ?: break }
            cur
        }
        val header = buildString {
            append("Version: $version\n")
            append("Shield active: $active\n")
            append("Recents recognised: ${r.isRecents}\n")
            append("Windows (front to back):\n")
            for (w in ws) {
                val wp = try { w.root?.packageName } catch (_: Exception) { null }
                append("  type=${w.type} pkg=$wp focused=${w.isFocused} active=${w.isActive} layer=${w.layer}\n")
            }
            append("Hidden now: ${hidden.joinToString { "${it.pkg} ${it.labels}" }}\n")
            append("Always: $always\n")
            append("Schedules: ${schedules.joinToString { "${it.name} on=${it.enabled} days=${it.days} ${it.startMin}-${it.endMin} apps=${it.apps}" }}\n")
            append("Cards:\n")
            for (c in r.cards) {
                append("  ${c.label} card=${c.scanBounds.toShortString()} sceneTop=${c.sceneTopRatio} z=${c.drawingOrder}\n")
            }
            append('\n')
        }
        Prefs.dumpFile(this).writeText(header + (root?.let { RecentsScanner.dump(it) } ?: ""))
    }

    /** Run [block]; log instead of crashing (a crash stops the shield until the app is reopened). */
    private inline fun guarded(where: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logError(where, t) }
    }

    @Volatile private var lastErrorLog = 0L

    private fun logError(where: String, t: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorLog < 5_000) return // don't flood the log from a repeating error
        lastErrorLog = now
        Diag.log(this, "Error in $where", t)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log(this, "Service stopped (turned off, or Android unbound it)")
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun cleanup() {
        worker.removeCallbacksAndMessages(null)
        active = false
        snapshot = null
        main.post { if (::overlay.isInitialized) stopDrawing() }
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val TICK_MS = 33L               // ~30 position reads per second while on
        private const val FULL_SCAN_MS = 250L
        private const val HOME_EVENT_MS = 60L
        private const val IDLE_HOME_EVENT_MS = 250L
        private const val ARMED_MS = 1200L
        private const val RECHECK_AFTER_OFF_MS = 1500L
        private const val SCAN_FALLBACK_MS = 400L
        private const val LEAD_MS = 16L               // predict one frame ahead
        private const val MAX_EXTRAPOLATE_MS = 120L
        private const val DIAG_DELAY_MS = 700L
    }
}
