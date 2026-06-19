package com.photosync.client.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * FrameLayout that renders a smooth GPU-blurred neon glow behind its children.
 * API 31+: uses RenderEffect (hardware Gaussian blur).
 * API <31: falls back to software BlurMaskFilter.
 * Parent must have clipChildren=false for the halo to bleed outside card bounds.
 */
class GlowCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val density   = resources.displayMetrics.density
    private val cornerPx  = 16f * density
    private val blurSigma = 20f * density   // ~20dp; larger = wider halo

    private var glowColor = Color.argb(100, 0x22, 0xd3, 0xee)   // default cyan

    // Added as child[0] so it sits behind all content; sized in onLayout
    private val glowView = View(context).also { addView(it, 0, LayoutParams(0, 0)) }

    init {
        clipChildren  = false
        clipToPadding = false
        applyGlow()
    }

    /** Call from Activity/Fragment to set the accent colour (alpha controls intensity). */
    fun setGlowColor(color: Int) {
        glowColor = color
        applyGlow()
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
                RenderEffect.createBlurEffect(blurSigma, blurSigma, Shader.TileMode.CLAMP)
            )
        } else {
            glowView.setLayerType(LAYER_TYPE_SOFTWARE, null)
            glowView.background = SoftwareBlurDrawable()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Size glowView to exactly fill this layout after children determine its size
        glowView.layout(0, 0, r - l, b - t)
    }

    private inner class SoftwareBlurDrawable : Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
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
