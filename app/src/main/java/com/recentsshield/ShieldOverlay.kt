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
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * One black box: [box] is a hidden app's preview; [cutouts] are things drawn on top of
 * it (cards stacked in front, and app badges), which are cut out of the black so they
 * stay visible.
 */
class Shield(val box: Rect, val cutouts: List<Rect>, val cutoutIsIcon: List<Boolean>)

/**
 * A see-through, untouchable window drawn above everything (including Recents).
 * Touches pass straight through to the cards underneath.
 */
class ShieldOverlay(private val ctx: Context, private val wm: WindowManager) {

    private var view: ShieldView? = null

    // Diagnostics: what the window is actually doing.
    @Volatile var adds = 0
        private set
    @Volatile var lastAddError: String? = null
        private set
    @Volatile private var lastView: ShieldView? = null

    fun stats(): String {
        val v = lastView
        return "window adds=$adds" + (lastAddError?.let { ", add error=$it" } ?: "") +
            (v?.let { ", draws=${it.draws}, painted boxes=${it.paintedBoxes}, attached=${it.isAttachedToWindow}, " +
                "visibility=${it.windowVisibilityText}, size=${it.width}x${it.height}, at=${it.lastLoc}" } ?: ", no window yet")
    }

    fun show(shields: List<Shield>) {
        // Nothing to draw and nothing showing: don't create a window at all.
        if (shields.isEmpty() && view == null) return
        val v = attach() ?: return
        v.setShields(shields)
    }

    /** Put the window up even with nothing to draw (used by the debug red border). */
    fun ensureAttached() {
        attach()
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private var lastFailure = 0L

    private fun attach(): ShieldView? {
        view?.let { return it }
        val v = ShieldView(ctx, Prefs.get(ctx).getBoolean(Prefs.KEY_DEBUG_OUTLINE, false))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.fitInsetsTypes = 0
        }
        return try {
            wm.addView(v, lp)
            view = v
            lastView = v
            adds++
            v
        } catch (e: Exception) {
            lastAddError = "${e.javaClass.simpleName}: ${e.message}"
            // Android can briefly refuse the overlay (e.g. while the service reconnects).
            // That used to crash the service; now we skip this frame and try again.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFailure > 10_000) Diag.log(ctx, "Overlay couldn't be shown: ${e.javaClass.simpleName}: ${e.message}")
            lastFailure = now
            null
        }
    }
}

private class ShieldView(ctx: Context, private val debugOutline: Boolean) : View(ctx) {
    @Volatile var draws = 0
    @Volatile var paintedBoxes = 0
    @Volatile var lastLoc = "?"
    @Volatile var windowVisibilityText = "unknown"
    private val debugPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f * ctx.resources.displayMetrics.density
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisibilityText = when (visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> visibility.toString()
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val cardRadius = 22f * ctx.resources.displayMetrics.density
    private var shields: List<Shield> = emptyList()
    private val loc = IntArray(2)
    private val boxPath = Path()
    private val cutPath = Path()
    private val rf = RectF()

    fun setShields(s: List<Shield>) {
        if (s.isEmpty() && shields.isEmpty()) return
        shields = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        draws++
        if (debugOutline) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), debugPaint)
        if (shields.isEmpty()) return
        paintedBoxes += shields.size
        getLocationOnScreen(loc)
        lastLoc = "${loc[0]},${loc[1]}"
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        // Screen edges, in screen coordinates.
        val left = ox
        val right = ox + width
        for (s in shields) {
            boxPath.reset()
            toRectF(s.box, ox, oy, left, right, cardRadius)
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
