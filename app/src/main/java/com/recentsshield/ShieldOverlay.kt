package com.recentsshield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A see-through, untouchable window drawn above everything (including Recents).
 * It either fills the whole screen with black or draws black boxes over given rects.
 * Touches pass straight through to the cards underneath.
 */
class ShieldOverlay(private val ctx: Context, private val wm: WindowManager) {

    private var view: ShieldView? = null

    fun showFull() = attach().setFull()

    fun showRects(rects: List<Rect>) = attach().setRects(rects)

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
    private val radius = 24f * ctx.resources.displayMetrics.density
    private var full = false
    private var rects: List<Rect> = emptyList()
    private val loc = IntArray(2)

    fun setFull() {
        if (full) return
        full = true
        rects = emptyList()
        invalidate()
    }

    fun setRects(r: List<Rect>) {
        if (!full && r == rects) return
        full = false
        rects = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (full) {
            canvas.drawColor(Color.BLACK)
            return
        }
        // Rects are in screen coordinates; shift by wherever this window actually sits.
        getLocationOnScreen(loc)
        for (r in rects) {
            canvas.drawRoundRect(
                (r.left - loc[0]).toFloat(), (r.top - loc[1]).toFloat(),
                (r.right - loc[0]).toFloat(), (r.bottom - loc[1]).toFloat(),
                radius, radius, paint,
            )
        }
    }
}
