package com.recentsshield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * One black box: [box] is a hidden app's preview; [cutouts] are things drawn on top of
 * it (cards stacked in front, and app badges), which are cut out of the black so they
 * stay visible.
 */
data class Shield(val box: Rect, val cutouts: List<Rect>, val cutoutIsIcon: List<Boolean>)

/**
 * A see-through, untouchable full-screen window above everything. Touches pass straight
 * through to whatever is underneath.
 *
 * By default the window is created once and stays up permanently (empty when there's
 * nothing to hide). A window created *during* Samsung's Recents animation gets swept into
 * that animation, and Android holds back its updates until Recents closes, so the box
 * never appears. A window that already exists isn't affected.
 *
 * Two experimental drawing methods can be switched on from Troubleshooting.
 */
class ShieldOverlay(private val ctx: Context, private val wm: WindowManager) {

    private var view: ShieldView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var blackingOut = false

    private var persistent = true
    private var nudge = false
    private var software = false
    private var debug = false

    // Diagnostics: what the window is actually doing.
    @Volatile var adds = 0
        private set
    @Volatile var lastAddError: String? = null
        private set

    fun stats(): String {
        val v = view
        val mode = buildList {
            if (persistent) add("always-up")
            if (nudge) add("nudge")
            if (software) add("no-GPU")
        }.joinToString("+").ifEmpty { "on-demand" }
        return "window mode=$mode, adds=$adds" + (lastAddError?.let { ", add error=$it" } ?: "") +
            (v?.let { ", draws=${it.draws}, painted boxes=${it.paintedBoxes}, " +
                "visibility=${it.windowVisibilityText}, size=${it.width}x${it.height}" } ?: ", no window now")
    }

    /** (Re)read the drawing settings and set the window up accordingly. Main thread. */
    fun configure() {
        val p = Prefs.get(ctx)
        persistent = p.getBoolean(Prefs.KEY_PERSISTENT_WINDOW, true)
        nudge = p.getBoolean(Prefs.KEY_NUDGE, false)
        software = p.getBoolean(Prefs.KEY_SOFTWARE, false)
        debug = p.getBoolean(Prefs.KEY_DEBUG_OUTLINE, false)
        detach()
        if (persistent) attach()
    }

    /** The shield is on (Recents showing): only affects the debug markers. */
    fun setActive(active: Boolean) {
        if (active && debug) attach()
        view?.setActive(active)
    }

    fun show(shields: List<Shield>) {
        if (shields.isEmpty() && view == null) return
        val v = attach() ?: return
        if (v.setShields(shields) && nudge) nudgeWindow()
    }

    /** Cover the whole screen in black (on) or go back to normal (off). */
    fun blackout(on: Boolean) {
        blackingOut = on
        if (on) {
            val v = attach() ?: return
            if (v.setFullBlack(true) && nudge) nudgeWindow()
        } else {
            val v = view ?: return
            if (v.setFullBlack(false) && nudge) nudgeWindow()
            if (!persistent && !v.hasShields()) detach()
        }
    }

    /** Clear the boxes. In always-up mode (or during a blackout) the window stays. */
    fun hide() {
        val v = view ?: return
        v.setActive(false)
        if (persistent || blackingOut) {
            if (v.setShields(emptyList()) && nudge) nudgeWindow()
        } else {
            detach()
        }
    }

    fun detach() {
        val v = view ?: return
        view = null
        lp = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    /**
     * Experimental: make the window manager re-apply this window after each change, by
     * flipping its opacity between 100% and 99.9% (not visible to the eye).
     */
    private var nudgeFlip = false

    private fun nudgeWindow() {
        val v = view ?: return
        val p = lp ?: return
        nudgeFlip = !nudgeFlip
        p.alpha = if (nudgeFlip) 0.999f else 1f
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private var lastFailure = 0L

    private fun attach(): ShieldView? {
        view?.let { return it }
        val v = ShieldView(ctx, debug)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!software) flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.windowAnimations = 0
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.fitInsetsTypes = 0
        if (software) v.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        return try {
            wm.addView(v, p)
            view = v
            lp = p
            adds++
            v
        } catch (e: Exception) {
            lastAddError = "${e.javaClass.simpleName}: ${e.message}"
            // Android can briefly refuse the overlay (e.g. while the service reconnects).
            val now = SystemClock.uptimeMillis()
            if (now - lastFailure > 10_000) Diag.log(ctx, "Overlay couldn't be shown: $lastAddError")
            lastFailure = now
            null
        }
    }
}

private class ShieldView(ctx: Context, private val debug: Boolean) : View(ctx) {
    @Volatile var draws = 0
    @Volatile var paintedBoxes = 0
    @Volatile var windowVisibilityText = "unknown"

    private var active = false
    private var fullBlack = false
    private var shields: List<Shield> = emptyList()

    fun hasShields() = shields.isNotEmpty()

    /** Returns true if it changed (and a redraw was requested). */
    fun setFullBlack(on: Boolean): Boolean {
        if (fullBlack == on) return false
        fullBlack = on
        invalidate()
        return true
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val density = ctx.resources.displayMetrics.density
    private val cardRadius = 22f * density
    private val debugPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f * density
    }
    private val blinkPaint = Paint().apply { style = Paint.Style.FILL }
    private val debugMargin = 14f * density
    private val loc = IntArray(2)
    private val boxPath = Path()
    private val cutPath = Path()
    private val rf = RectF()

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisibilityText = when (visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> visibility.toString()
        }
    }

    fun setActive(a: Boolean) {
        if (active == a) return
        active = a
        if (debug) invalidate()
    }

    /** Returns true if anything changed (and a redraw was requested). */
    fun setShields(s: List<Shield>): Boolean {
        if (s == shields) return false // nothing moved: don't produce a new frame
        shields = s
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        draws++
        if (fullBlack) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val showDebug = debug && active
        if (showDebug) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), debugPaint)
            // Blinks red/green twice a second: if it keeps blinking, this window is updating.
            val phase = (SystemClock.uptimeMillis() / 500) % 2 == 0L
            blinkPaint.color = if (phase) Color.RED else Color.GREEN
            val sq = 36f * density
            canvas.drawRect(sq * 0.6f, sq * 2.5f, sq * 1.6f, sq * 3.5f, blinkPaint)
            postInvalidateOnAnimation()
        }
        if (shields.isEmpty()) return
        paintedBoxes += shields.size
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        val left = ox
        val right = ox + width
        for (s in shields) {
            boxPath.reset()
            toRectF(s.box, ox, oy, left, right, cardRadius)
            if (showDebug) rf.inset(-debugMargin, -debugMargin)
            boxPath.addRoundRect(rf, cardRadius, cardRadius, Path.Direction.CW)
            for (i in s.cutouts.indices) {
                val r = s.cutouts[i]
                val isIcon = s.cutoutIsIcon[i]
                val radius = if (isIcon) minOf(r.width(), r.height()) * 0.32f else cardRadius
                cutPath.reset()
                toRectF(r, ox, oy, left, right, if (isIcon) 0f else cardRadius)
                cutPath.addRoundRect(rf, radius, radius, Path.Direction.CW)
                boxPath.op(cutPath, Path.Op.DIFFERENCE)
            }
            canvas.drawPath(boxPath, paint)
        }
    }

    /**
     * Converts a screen rect to view coordinates. A card that runs off the side of the
     * screen gets reported clipped to the edge; push that edge out past the screen so its
     * rounded corners don't leave little gaps at the edge.
     */
    private fun toRectF(r: Rect, ox: Float, oy: Float, left: Float, right: Float, extend: Float) {
        var l = r.left.toFloat()
        var rt = r.right.toFloat()
        if (extend > 0f) {
            if (l <= left + 1) l -= extend
            if (rt >= right - 1) rt += extend
        }
        rf.set(l - ox, r.top - oy, rt - ox, r.bottom - oy)
    }
}
