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

    fun show(shields: List<Shield>) = attach().setShields(shields)

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private fun attach(): ShieldView {
        view?.let { return it }
        val v = ShieldView(ctx)
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
        wm.addView(v, lp)
        view = v
        return v
    }
}

private class ShieldView(ctx: Context) : View(ctx) {
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
        if (shields.isEmpty()) return
        getLocationOnScreen(loc)
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
