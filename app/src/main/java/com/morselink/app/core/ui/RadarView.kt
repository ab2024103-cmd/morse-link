package com.morselink.app.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.morselink.app.R

/**
 * Cosmetic radar animation on the dashboard (spec Section 10.3). Purely
 * decorative: importantForAccessibility is set by callers; discovered devices
 * are always also listed as tappable rows — the radar is never the only way to
 * pick a peer.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var sweepAngle = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                sweepAngle = anim.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = Math.min(cx, cy) * 0.92f
        val accent = com.morselink.app.core.util.ThemeColors.accent(context)
        ringPaint.color = accent and 0x66FFFFFF.toInt()
        sweepPaint.color = accent
        dotPaint.color = accent

        for (i in 1..3) {
            canvas.drawCircle(cx, cy, maxR * i / 3f, ringPaint)
        }

        val sweepAlpha = 140
        sweepPaint.alpha = sweepAlpha
        canvas.drawArc(cx - maxR, cy - maxR, cx + maxR, cy + maxR, sweepAngle, 34f, true, sweepPaint)
        sweepPaint.alpha = 60
        canvas.drawArc(cx - maxR, cy - maxR, cx + maxR, cy + maxR, sweepAngle, 70f, true, sweepPaint)

        dotPaint.alpha = 230
        canvas.drawCircle(cx, cy, maxR * 0.06f, dotPaint)
    }
}
