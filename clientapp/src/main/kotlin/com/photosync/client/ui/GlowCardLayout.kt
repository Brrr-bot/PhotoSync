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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * FrameLayout with two visual layers:
 *  1. Static GPU-blurred neon halo behind children (RenderEffect API 31+, BlurMaskFilter fallback).
 *  2. Animated perimeter pulse on top of children — call startPulse()/stopPulse().
 * Parent must have clipChildren=false for the halo to bleed outside bounds.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val density   = resources.displayMetrics.density
    private val cornerPx  = 16f * density
    private val blurSigma = 12f * density

    private var glowColor = Color.argb(100, 0x22, 0xd3, 0xee)

    // glowView sits behind content (added at index 0 before XML children inflate)
    private val glowView = View(context).also { addView(it, 0, LayoutParams(0, 0)) }

    // pulseOverlay is moved to the TOP (last child) in onFinishInflate so it renders over content
    private val pulseOverlay = PulseOverlay(context).also {
        it.visibility = GONE
        addView(it, LayoutParams(0, 0))
    }

    private var pulseAnimator: ValueAnimator? = null

    init {
        clipChildren  = false
        clipToPadding = false
        applyGlow()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Re-attach pulse overlay as last child so it draws over the card surface
        removeView(pulseOverlay)
        addView(pulseOverlay, LayoutParams(0, 0))
    }

    fun setGlowColor(color: Int) {
        glowColor = color
        pulseOverlay.setAccent(color)
        applyGlow()
    }

    fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseOverlay.visibility = VISIBLE
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 3500L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { pulseOverlay.setProgress(it.animatedValue as Float) }
            start()
        }
    }

    fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
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

    // ── Pulse overlay ──────────────────────────────────────────────────────

    private inner class PulseOverlay(ctx: Context) : View(ctx) {
        private val strokePx = 1.5f * density
        private val path     = Path()
        private val segPath  = Path()
        private val pm       = PathMeasure()
        private var progress = 0f

        // Soft glow halo around the arc
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx * 2f
            maskFilter  = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        // Crisp bright core line
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        // Bright white tip
        private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx * 1.5f
            color       = Color.argb(220, 255, 255, 255)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) {
            // Force full alpha for the stroke colours — alpha on glowColor is for the halo, not pulse
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            glowPaint.color  = Color.argb(80, r, g, b)
            corePaint.color  = Color.argb(255, r, g, b)
        }

        fun setProgress(p: Float) { progress = p; invalidate() }

        fun buildPath(w: Int, h: Int) {
            val inset = 0.375f * density
            path.reset()
            path.addRoundRect(RectF(inset, inset, w - inset, h - inset),
                cornerPx, cornerPx, Path.Direction.CW)
            pm.setPath(path, false)
            // Refresh accent in case setAccent wasn't called yet
            val r = Color.red(glowColor); val g = Color.green(glowColor); val b = Color.blue(glowColor)
            glowPaint.color = Color.argb(80, r, g, b)
            corePaint.color = Color.argb(255, r, g, b)
        }

        override fun onDraw(canvas: Canvas) {
            val len = pm.length
            if (len == 0f) return
            val segLen = len * 0.09f
            val start  = progress * len
            val N = 32  // number of fade steps

            // Draw comet tail: N segments from tail (alpha~0) to tip (alpha~255)
            for (i in 0 until N) {
                val frac     = i.toFloat() / N
                val fracNext = (i + 1).toFloat() / N
                // Cubic ease-in: tail is barely visible, tip is full brightness
                val alpha     = (fracNext * fracNext * fracNext * 255f).toInt().coerceIn(0, 255)
                val glowAlpha = (fracNext * fracNext * fracNext * 80f).toInt().coerceIn(0, 255)

                val sStart = (start + segLen * frac) % len
                val sEnd   = (start + segLen * fracNext) % len
                val seg = Path()
                if (sStart <= sEnd) {
                    pm.getSegment(sStart, sEnd, seg, true)
                } else {
                    pm.getSegment(sStart, len, seg, true)
                    val wrap = Path(); pm.getSegment(0f, sEnd, wrap, true); seg.addPath(wrap)
                }

                glowPaint.alpha = glowAlpha
                corePaint.alpha = alpha
                canvas.drawPath(seg, glowPaint)
                canvas.drawPath(seg, corePaint)
            }

            // Bright white flash at the very tip
            val tipSeg = Path()
            val tipS = (start + segLen * 0.92f) % len
            val tipE = (start + segLen) % len
            if (tipS <= tipE) pm.getSegment(tipS, tipE, tipSeg, true)
            else { pm.getSegment(tipS, len, tipSeg, true); val w=Path(); pm.getSegment(0f,tipE,w,true); tipSeg.addPath(w) }
            canvas.drawPath(tipSeg, tipPaint)
        }
    }

    // ── Soft-render halo fallback (API < 31) ──────────────────────────────

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
