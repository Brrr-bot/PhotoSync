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
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Card wrapper with two animation layers (both rendered BEHIND the card fill):
 *   1. Breathing — full-perimeter glow that pulses in/out (5s in, 5s out). Always on.
 *   2. Comet pulse — fast head + long tail sweeps the border. On when work is active.
 *      For the status card, call setAlertMode(true) to switch the comet to red.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        /** Shared breath: 5 s fade-in then 5 s fade-out, forever. */
        private val sharedBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 5000L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        /** Shared comet: all cards sweep in lockstep. */
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
    private val glowPad  = (20f * density).toInt()

    var glowColor: Int = Color.argb(100, 0x22, 0xd3, 0xee)
        private set

    private val breathingOverlay = BreathingOverlay(context).also {
        it.visibility = GONE
        addView(it, LayoutParams(0, 0))
    }
    private val pulseOverlay = PulseOverlay(context).also {
        it.visibility = GONE
        addView(it, LayoutParams(0, 0))
    }

    private var breathListener: ValueAnimator.AnimatorUpdateListener? = null
    private var pulseListener:  ValueAnimator.AnimatorUpdateListener? = null
    private var alertMode = false

    init {
        clipChildren  = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Both overlays stay behind all card content
        removeView(pulseOverlay)
        removeView(breathingOverlay)
        addView(breathingOverlay, 0, LayoutParams(0, 0))
        addView(pulseOverlay,     1, LayoutParams(0, 0))
    }

    fun setGlowColor(color: Int) {
        glowColor = color
        breathingOverlay.setAccent(color)
        if (!alertMode) pulseOverlay.setAccent(color)
    }

    // ── Breathing (default, always on) ────────────────────────────────────────

    fun startBreathing() {
        if (breathListener != null) return
        breathingOverlay.visibility = VISIBLE
        val l = ValueAnimator.AnimatorUpdateListener { anim ->
            breathingOverlay.setBreath(anim.animatedValue as Float)
        }
        breathListener = l
        sharedBreath.addUpdateListener(l)
    }

    fun stopBreathing() {
        breathListener?.let { sharedBreath.removeUpdateListener(it) }
        breathListener = null
        breathingOverlay.visibility = GONE
    }

    // ── Comet pulse (active work) ──────────────────────────────────────────────

    fun startPulse() {
        if (pulseListener != null) return
        pulseOverlay.visibility = VISIBLE
        val l = ValueAnimator.AnimatorUpdateListener { anim ->
            pulseOverlay.setProgress(anim.animatedValue as Float)
        }
        pulseListener = l
        sharedPulse.addUpdateListener(l)
    }

    fun stopPulse() {
        pulseListener?.let { sharedPulse.removeUpdateListener(it) }
        pulseListener = null
        if (!alertMode) pulseOverlay.visibility = GONE
    }

    // ── Alert mode (status card: red comet when something needs attention) ─────

    fun setAlertMode(alert: Boolean) {
        alertMode = alert
        if (alert) {
            pulseOverlay.setAccent(0xFFFF3B3B.toInt())
            startPulse()
        } else {
            pulseOverlay.setAccent(glowColor)
            stopPulse()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l; val h = b - t
        breathingOverlay.layout(-glowPad, -glowPad, w + glowPad, h + glowPad)
        pulseOverlay.layout(-glowPad, -glowPad, w + glowPad, h + glowPad)
        if (changed) {
            breathingOverlay.buildPath(w, h, glowPad)
            pulseOverlay.buildPath(w, h, glowPad)
        }
    }

    // ── Breathing overlay ──────────────────────────────────────────────────────

    private inner class BreathingOverlay(ctx: Context) : View(ctx) {
        private val strokePx = 1.5f * density
        private val path = Path()
        private var breath = 0f

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 16f * density
            maskFilter  = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) {
            glowPaint.color = color or 0xFF000000.toInt()
        }

        fun setBreath(b: Float) { breath = b; invalidate() }

        fun buildPath(cardW: Int, cardH: Int, pad: Int) {
            path.reset()
            val off = pad.toFloat(); val inset = strokePx / 2f
            path.addRoundRect(
                RectF(off + inset, off + inset, cardW + off - inset, cardH + off - inset),
                cornerPx, cornerPx, Path.Direction.CW
            )
            val r = Color.red(glowColor); val g = Color.green(glowColor); val b = Color.blue(glowColor)
            glowPaint.color = Color.argb(255, r, g, b)
        }

        override fun onDraw(canvas: Canvas) {
            // Max alpha 70 so it's subtle — just a gentle glow, not overpowering
            glowPaint.alpha = (breath * 70f).toInt()
            canvas.drawPath(path, glowPaint)
        }
    }

    // ── Comet pulse overlay ────────────────────────────────────────────────────

    private inner class PulseOverlay(ctx: Context) : View(ctx) {
        private val strokePx     = 1.5f * density
        private val path         = Path()
        private val pm           = PathMeasure()
        private var progress     = 0f
        private val tailFraction = 0.975f

        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 18f * density
            maskFilter  = BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx * 4f
            maskFilter  = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) {
            outerGlowPaint.color = color
            haloPaint.color      = color
            corePaint.color      = color
        }

        fun setProgress(p: Float) { progress = p; invalidate() }

        fun buildPath(cardW: Int, cardH: Int, pad: Int) {
            path.reset()
            val off = pad.toFloat(); val inset = strokePx / 2f
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
                val outerT = (t - 0.6f).coerceAtLeast(0f) / 0.4f
                val outerAlpha = (outerT * outerT * 130f).toInt()
                if (outerAlpha > 1) { outerGlowPaint.alpha = outerAlpha; canvas.drawPath(seg, outerGlowPaint) }
                haloPaint.alpha  = (alpha * 0.45f).toInt()
                corePaint.alpha  = alpha
                canvas.drawPath(seg, haloPaint)
                canvas.drawPath(seg, corePaint)
            }
        }
    }
}