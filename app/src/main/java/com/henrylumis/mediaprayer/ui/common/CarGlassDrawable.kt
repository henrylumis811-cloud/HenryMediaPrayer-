package com.henrylumis.mediaprayer.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.henrylumis.mediaprayer.R

/** Reusable glass surface that keeps the app's supercar visible behind transient UI. */
class CarGlassDrawable(private val resources: android.content.res.Resources) : Drawable() {
    private val bitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_supercar_default)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Color.argb(105, 255, 255, 255)
    }
    private val path = Path()
    private val rect = RectF()
    private val radius = 22f * resources.displayMetrics.density

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = maxOf(rect.width() / bw, rect.height() / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = rect.centerX() - dw / 2f
        val top = rect.centerY() - dh / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), imagePaint)

        overlayPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            Color.argb(205, 4, 8, 13), Color.argb(225, 5, 7, 10), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, overlayPaint)
        overlayPaint.shader = null
        canvas.drawPath(path, borderPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { imagePaint.alpha = alpha; overlayPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { imagePaint.colorFilter = colorFilter }
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
