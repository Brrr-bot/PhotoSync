package com.photosync.client.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * FrameLayout with a comet-tail pulse on the card border.
 * The comet head emits a neon glow bloom that spills OUTSIDE the card as it sweeps past.
 * All instances share one ValueAnimator so every card pulses identically in sync.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        private val sharedPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 3000L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(2f)
            start()
        }
    }

    private val density  = resources.displayMetrics.density
    private val cornerPx = 16f * density
    // Extra canvas space around card so outer glow can bleed outside without clipping
    private val glowPad  = (20f * density).toInt()

    private var glowColor = Color.argb(100, 0x22, 0xd3, 0xee)

    private val pulseOverlay = PulseOverlay(context).also {
        it.visibility = GONE
        addView(it, LayoutParams(0, 0))
    }

    private var pulseListener: ValueAnimator.AnimatorUpdateListener? = null

    init {
        clipChildren  = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        removeView(pulseOverlay)
        addView(pulseOverlay, LayoutParams(0, 0))
    }

    fun setGlowColor(color: Int) {
        glowColor = color
        pulseOverlay.setAccent(color)
    }

    fun startPulse() {
        if (pulseListener != null) return
        pulseOverlay.visibility = VISIBLE
        val listener = ValueAnimator.AnimatorUpdateListener { anim ->
            pulseOverlay.setProgress(anim.animatedValue as Float)
        }
        pulseListener = listener
        sharedPulse.addUpdateListener(listener)
    }

    fun stopPulse() {
        pulseListener?.let { sharedPulse.removeUpdateListener(it) }
        pulseListener = null
        pulseOverlay.visibility = GONE
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l; val h = b - t
        // Overlay is larger than the card so the outer bloom has room to render
        pulseOverlay.layout(-glowPad, -glowPad, w + glowPad, h + glowPad)
        if (changed) pulseOverlay.buildPath(w, h, glowPad)
    }

    // ── Comet-tail overlay ─────────────────────────────────────────────────────

    private inner class PulseOverlay(ctx: Context) : View(ctx) {
        private val strokePx  = 1.5f * density

        private val path = Path()
        private val pm   = PathMeasure()
        private var progress = 0f
        private val tailFraction = 0.975f

        // Outer neon bloom — wide, very blurred, spills outside the card
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 18f * density
            maskFilter  = BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL)
        }
        // Medium halo ring
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx * 4f
            maskFilter  = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        // Crisp core line on the border itself
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) {
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            outerGlowPaint.color = Color.argb(255, r, g, b)
            haloPaint.color      = Color.argb(255, r, g, b)
            corePaint.color      = Color.argb(255, r, g, b)
        }

        fun setProgress(p: Float) { progress = p; invalidate() }

        fun buildPath(cardW: Int, cardH: Int, pad: Int) {
            path.reset()
            val off = pad.toFloat()
            val inset = strokePx / 2f
            path.addRoundRect(
                RectF(off + inset, off + inset, cardW + off - inset, cardH + off - inset),
                cornerPx, cornerPx, Path.Direction.CW
            )
            pm.setPath(path, false)
            val r = Color.red(glowColor); val g = Color.green(glowColor); val b = Color.blue(glowColor)
            outerGlowPaint.color = Color.argb(255, r, g, b)
            haloPaint.color      = Color.argb(255, r, g, b)
            corePaint.color      = Color.argb(255, r, g, b)
        }

        override fun onDraw(canvas: Canvas) {
            val len = pm.length
            if (len == 0f) return

            val headPos = progress * len
            val N = 64

            for (i in 0 until N) {
                val behind = i.toFloat() / N * tailFraction
                val t = 1f - (behind / tailFraction)
                val alpha = (t * t * t * t * 255f).toInt().coerceIn(0, 255)
                if (alpha < 2) continue

                val segLen = len * tailFraction / N
                val sEnd   = ((headPos - len * behind) + len * 10f) % len
                val sStart = ((sEnd - segLen) + len) % len

                val seg = Path()
                if (sStart < sEnd) {
                    pm.getSegment(sStart, sEnd, seg, true)
                } else {
                    pm.getSegment(sStart, len, seg, true)
                    val wrap = Path(); pm.getSegment(0f, sEnd, wrap, true)
                    seg.addPath(wrap)
                }

                // Outer bloom: only near the head (first 40% of tail), drops off fast
                val outerT = (t - 0.6f).coerceAtLeast(0f) / 0.4f
                val outerAlpha = (outerT * outerT * 130f).toInt()
                if (outerAlpha > 1) {
                    outerGlowPaint.alpha = outerAlpha
                    canvas.drawPath(seg, outerGlowPaint)
                }

                haloPaint.alpha  = (alpha * 0.45f).toInt()
                corePaint.alpha  = alpha
                canvas.drawPath(seg, haloPaint)
                canvas.drawPath(seg, corePaint)
            }
        }
    }
}