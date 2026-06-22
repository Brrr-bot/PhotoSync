package com.photosync.client.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ProgressBar

/**
 * ProgressBar that draws a shimmer sweep in perfect sync with GlowCardLayout's
 * comet pulse — same shared ValueAnimator, same DecelerateInterpolator timing.
 */
class PulseProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.progressBarStyleHorizontal
) : ProgressBar(context, attrs, defStyle) {

    private val density      = resources.displayMetrics.density
    private var pulsePos     = 0f
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val listener = ValueAnimator.AnimatorUpdateListener { anim ->
        pulsePos = anim.animatedValue as Float
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        GlowCardLayout.sharedPulse.addUpdateListener(listener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        GlowCardLayout.sharedPulse.removeUpdateListener(listener)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress == 0 || max == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val progressWidth = w * progress.toFloat() / max.toFloat()

        // Shimmer band: full width so it sweeps cleanly left to right
        val band = 120f * density
        val center = pulsePos * (progressWidth + band) - band / 2f

        shimmerPaint.shader = LinearGradient(
            center - band / 2f, 0f,
            center + band / 2f, 0f,
            intArrayOf(0x00FFFFFF, 0x60FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipRect(0f, 0f, progressWidth, h)
        canvas.drawRect(0f, 0f, w, h, shimmerPaint)
        canvas.restore()
    }
}