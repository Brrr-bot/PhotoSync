package com.photosync.hub.ui

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
 * Card wrapper with two exclusive animation modes:
 *   Breathing — full-perimeter glow that pulses in/out (5 s each way). Default state.
 *   Comet     — fast head + tail sweeps the border. Active-work state.
 *
 * Rules:
 *  - The two overlays are NEVER both visible at the same time.
 *  - Transitions wait for a natural boundary:
 *      Breath → Comet : waits until breath alpha is at its floor (glow nearly off).
 *      Comet → Breath : waits until the comet head completes a full lap (progress wraps to ~0).
 *  - Breathing never fades fully to black; a floor alpha keeps a faint ambient glow.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        // 25s cycle: 0-15s hold at full, 15-20s fade out, 20-25s fade in
        private val sharedBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 25000L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        internal val sharedPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 6000L
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

    // State machine
    private var isPulsing    = false
    private var pulsePending = false   // want to start pulse — waiting for breath floor
    private var breathPending = false  // want to return to breath — waiting for pulse wrap
    private var lastPulseProgress = 0f
    private var alertMode = false

    init {
        clipChildren  = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
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

    // ── Public API ────────────────────────────────────────────────────────────

    fun startBreathing() {
        if (breathListener != null) return
        breathingOverlay.visibility = VISIBLE
        val l = ValueAnimator.AnimatorUpdateListener { anim ->
            val frac = anim.animatedValue as Float
            // Map linear fraction to breath: hold(0-0.6) → fade-out(0.6-0.8) → fade-in(0.8-1.0)
            val b = when {
                frac < 0.6f -> 1f
                frac < 0.8f -> { val t = (frac - 0.6f) / 0.2f; 1f - t * t }
                else        -> { val t = (frac - 0.8f) / 0.2f; t * t }
            }
            breathingOverlay.setBreath(b)
            // Transition to pulse when glow is near its minimum (bottom of fade-out)
            if (pulsePending && b < 0.2f) {
                pulsePending = false
                isPulsing = true
                breathingOverlay.visibility = GONE
                pulseOverlay.visibility = VISIBLE
            }
        }
        breathListener = l
        sharedBreath.addUpdateListener(l)
    }

    fun stopBreathing() {
        breathListener?.let { sharedBreath.removeUpdateListener(it) }
        breathListener = null
        breathingOverlay.visibility = GONE
    }

    fun startPulse() {
        if (isPulsing || pulsePending) return
        if (!alertMode) pulseOverlay.setAccent(glowColor)
        if (pulseListener == null) {
            val l = ValueAnimator.AnimatorUpdateListener { anim ->
                val p = anim.animatedValue as Float
                // Detect wrap-around (new cycle start)
                if (breathPending && p < 0.08f && lastPulseProgress > 0.85f) {
                    breathPending = false
                    isPulsing = false
                    pulseOverlay.visibility = GONE
                    breathingOverlay.visibility = VISIBLE
                } else if (isPulsing) {
                    pulseOverlay.setProgress(p)
                }
                lastPulseProgress = p
            }
            pulseListener = l
            sharedPulse.addUpdateListener(l)
        }
        // Request transition at next breath floor
        pulsePending = true
        pulseOverlay.visibility = GONE  // keep hidden until breath hands off
    }

    fun stopPulse() {
        if (!isPulsing && !pulsePending) return
        pulsePending = false
        if (isPulsing) {
            breathPending = true  // wait for comet to complete its lap
        } else {
            // Never started; just reset
            breathingOverlay.visibility = VISIBLE
        }
    }


    fun forcePulseNow() {
        breathingOverlay.visibility = GONE
        if (!alertMode) pulseOverlay.setAccent(glowColor)
        pulseOverlay.visibility = VISIBLE
        isPulsing = true
        pulsePending = false
        if (pulseListener == null) {
            val l = ValueAnimator.AnimatorUpdateListener { anim ->
                val p = anim.animatedValue as Float
                if (isPulsing) pulseOverlay.setProgress(p)
                lastPulseProgress = p
            }
            pulseListener = l
            sharedPulse.addUpdateListener(l)
        }
    }

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
        private val strokePx  = 1.5f * density
        private val path      = Path()
        private var breath    = 0f
        private val alphaFloor = 18   // never fully dark
        private val alphaCeil  = 75

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 16f * density
            maskFilter  = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setAccent(color: Int) { glowPaint.color = color or 0xFF000000.toInt() }
        fun setBreath(b: Float)   { breath = b; invalidate() }

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
            glowPaint.alpha = (alphaFloor + breath * (alphaCeil - alphaFloor)).toInt()
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
