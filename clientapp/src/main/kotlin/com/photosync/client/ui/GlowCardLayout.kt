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
 * FrameLayout with:
 *  1. Static GPU-blurred neon halo (RenderEffect API 31+, BlurMaskFilter fallback).
 *  2. Comet-tail pulse: a bright head sweeps around the border leaving a short fading
 *     tail (~38% of perimeter) that nearly reaches black before the next head arrives.
 *     All instances share one ValueAnimator so every card pulses identically in sync.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        /** One animator drives every card — they are always perfectly in sync. */
        private val sharedPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 3000L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(2f)  // fast start → slows to crawl
            start()
        }
    }

    private val density   = resources.displayMetrics.density
    private val cornerPx  = 16f * density
    private val blurSigma = 12f * density

    private var glowColor = Color.argb(100, 0x22, 0xd3, 0xee)

    private val glowView     = View(context).also { addView(it, 0, LayoutParams(0, 0)) }
    private val pulseOverlay = PulseOverlay(context).also {
        it.visibility = GONE
        addView(it, LayoutParams(0, 0))
    }

    private var pulseListener: ValueAnimator.AnimatorUpdateListener? = null

    init {
        clipChildren  = false
        clipToPadding = false
        applyGlow()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        removeView(pulseOverlay)
        addView(pulseOverlay, LayoutParams(0, 0))
    }

    fun setGlowColor(color: Int) {
        glowColor = color
        pulseOverlay.setAccent(color)
        applyGlow()
    }

    fun startPulse() {
        if (pulseListener != null) return          // already running
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

    private fun applyGlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glowView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(glowColor)
            }
            @Suppress("NewApi")
            glowView.setRenderEffect(
                RenderEffect.createBlurEffect(blurSigma, blurSigma, Shader.TileMode.DECAL)
            )
        } else {
            glowView.setLayerType(LAYER_TYPE_SOFTWARE, null)
            glowView.background = SoftwareBlurDrawable()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l; val h = b - t
        glowView.layout(0, 0, w, h)
        pulseOverlay.layout(0, 0, w, h)
        if (changed) pulseOverlay.buildPath(w, h)
    }

    // ── Comet-tail pulse overlay ───────────────────────────────────────────────

    private inner class PulseOverlay(ctx: Context) : View(ctx) {
        private val strokePx  = 1.5f * density
        private val pathInset = strokePx / 2f

        private val path = Path()
        private val pm   = PathMeasure()
        private var progress = 0f

        /** Fraction of the perimeter the comet tail covers.
         *  At 0.38, the tail fades to near-black well before the next head arrives. */
        private val tailFraction = 0.38f

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx * 3f
            maskFilter  = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) {
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            glowPaint.color  = Color.argb(255, r, g, b)
            corePaint.color  = Color.argb(255, r, g, b)
        }

        fun setProgress(p: Float) { progress = p; invalidate() }

        fun buildPath(w: Int, h: Int) {
            path.reset()
            path.addRoundRect(
                RectF(pathInset, pathInset, w - pathInset, h - pathInset),
                cornerPx, cornerPx, Path.Direction.CW
            )
            pm.setPath(path, false)
            val r = Color.red(glowColor); val g = Color.green(glowColor); val b = Color.blue(glowColor)
            glowPaint.color  = Color.argb(255, r, g, b)
            corePaint.color  = Color.argb(255, r, g, b)
        }

        override fun onDraw(canvas: Canvas) {
            val len = pm.length
            if (len == 0f) return

            val headPos = progress * len
            val N = 64   // segments for smooth gradient

            for (i in 0 until N) {
                // How far behind the head this segment is, as a fraction of the tail
                val behind = i.toFloat() / N * tailFraction
                // Normalised brightness: 1.0 at head, 0.0 at tail end
                val t = 1f - (behind / tailFraction)
                // Quartic fade — bright near head, drops to near-black quickly
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

                glowPaint.alpha  = (alpha * 0.4f).toInt()
                corePaint.alpha  = alpha
                canvas.drawPath(seg, glowPaint)
                canvas.drawPath(seg, corePaint)
            }
        }
    }

    // ── Soft-render halo fallback (API < 31) ──────────────────────────────────

    private inner class SoftwareBlurDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blurSigma * 0.6f, BlurMaskFilter.Blur.NORMAL)
        }
        override fun draw(canvas: Canvas) {
            paint.color = glowColor
            canvas.drawRoundRect(RectF(bounds), cornerPx, cornerPx, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}