package com.sameerakhtari.riddle.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.max

class ReplyInkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var fullText: String = ""
    private var revealProgress: Float = 0f
    private var animator: ValueAnimator? = null
    private var layout: StaticLayout? = null
    private var inkBitmap: Bitmap? = null
    private var replyTopPx = 0f
    private val pageRect = RectF()

    var inkColor: Int = Color.rgb(31, 18, 10)
        set(value) { field = value; rebuildInkLayer(); invalidate() }

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("cursive", Typeface.BOLD)
        textSize = sp(30f)
        textScaleX = 0.99f
        textSkewX = -0.075f
        strokeWidth = 0.62f
        letterSpacing = 0.012f
    }
    private val nibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 20, 10); style = Paint.Style.FILL }

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updatePageRect(w, h); rebuildInkLayer() }

    fun showReply(text: String, millisecondsPerCharacter: Long, @Suppress("UNUSED_PARAMETER") userInkBottomFraction: Float = 0f) {
        animator?.cancel()
        fullText = text.replace(Regex("\\s+"), " ").trim()
        revealProgress = 0f
        visibility = if (fullText.isBlank()) GONE else VISIBLE
        contentDescription = fullText
        rebuildInkLayer()
        if (fullText.isBlank()) { invalidate(); return }
        val lineCount = layout?.lineCount?.coerceAtLeast(1) ?: 1
        val writingTime = fullText.length * millisecondsPerCharacter.coerceIn(22L, 160L)
        val linePauses = (lineCount - 1).coerceAtLeast(0) * 260L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (writingTime + linePauses).coerceIn(2_800L, 18_000L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { revealProgress = (it.animatedValue as Float).coerceIn(0f, 1f); invalidate() }
            start()
        }
    }

    fun clearReply() { animator?.cancel(); animator = null; fullText = ""; revealProgress = 0f; visibility = GONE; rebuildInkLayer(); invalidate() }
    override fun onDetachedFromWindow() { animator?.cancel(); inkBitmap?.recycle(); inkBitmap = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = inkBitmap ?: return
        val textLayout = layout ?: return
        if (fullText.isBlank() || revealProgress <= 0f) return
        val lineCount = textLayout.lineCount.coerceAtLeast(1)
        val totalUnits = lineCount + (lineCount - 1).coerceAtLeast(0) * LINE_PAUSE_UNITS
        val currentUnits = revealProgress * totalUnits
        val src = Rect()
        val dst = RectF()
        var activeNibX = 0f
        var activeNibY = 0f
        var hasNib = false
        for (line in 0 until lineCount) {
            val lineStartUnits = line * (1f + LINE_PAUSE_UNITS)
            val lineProgress = (currentUnits - lineStartUnits).coerceIn(0f, 1f)
            if (lineProgress <= 0f) continue
            val left = textLayout.getLineLeft(line).toInt().coerceAtLeast(0)
            val right = textLayout.getLineRight(line).toInt().coerceAtLeast(left + 1)
            val top = textLayout.getLineTop(line).coerceAtLeast(0)
            val bottom = textLayout.getLineBottom(line).coerceAtLeast(top + 1)
            val revealRight = (left + (right - left) * lineProgress).toInt().coerceIn(left + 1, right)
            src.set(left, top, revealRight, bottom)
            dst.set(pageRect.left + dp(27f) + left, replyTopPx + top, pageRect.left + dp(27f) + revealRight, replyTopPx + bottom)
            canvas.drawBitmap(bitmap, src, dst, null)
            if (lineProgress < 0.995f) { activeNibX = dst.right; activeNibY = dst.bottom - dp(6f); hasNib = true }
        }
        if (hasNib) {
            nibPaint.alpha = 105
            nibPaint.setShadowLayer(dp(2.2f), 0f, dp(0.8f), Color.argb(90, 24, 9, 3))
            canvas.save(); canvas.rotate(-38f, activeNibX, activeNibY)
            canvas.drawOval(activeNibX - dp(3.5f), activeNibY - dp(1.2f), activeNibX + dp(3.5f), activeNibY + dp(1.2f), nibPaint)
            canvas.restore()
        }
    }

    private fun rebuildInkLayer() {
        inkBitmap?.recycle(); inkBitmap = null; layout = null
        if (width <= 0 || pageRect.width() <= 0 || fullText.isBlank()) return
        val availableWidth = (pageRect.width() - dp(54f)).toInt().coerceAtLeast(1)
        val newLayout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, textPaint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(dp(7f), 1.11f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        layout = newLayout
        val minimumTop = pageRect.top + dp(88f)
        val maximumTop = max(minimumTop, pageRect.bottom - newLayout.height - dp(82f))
        replyTopPx = (pageRect.centerY() - newLayout.height / 2f).coerceIn(minimumTop, maximumTop)
        val bitmap = Bitmap.createBitmap(max(1, availableWidth), max(1, newLayout.height + dp(10f).toInt()), Bitmap.Config.ARGB_8888)
        val inkCanvas = Canvas(bitmap)
        textPaint.color = inkColor; textPaint.style = Paint.Style.FILL_AND_STROKE
        textPaint.alpha = 46; textPaint.strokeWidth = dp(1.15f); textPaint.maskFilter = BlurMaskFilter(dp(0.78f), BlurMaskFilter.Blur.NORMAL)
        inkCanvas.save(); inkCanvas.translate(dp(1.1f), dp(2f)); newLayout.draw(inkCanvas); inkCanvas.restore()
        textPaint.alpha = 178; textPaint.strokeWidth = dp(0.58f); textPaint.maskFilter = null
        inkCanvas.save(); inkCanvas.translate(dp(1.1f), dp(2f)); newLayout.draw(inkCanvas); inkCanvas.restore()
        textPaint.alpha = 242; textPaint.strokeWidth = dp(0.18f); textPaint.setShadowLayer(dp(0.45f), dp(0.15f), dp(0.35f), Color.argb(80, 20, 7, 2))
        inkCanvas.save(); inkCanvas.translate(dp(1.1f), dp(2f)); newLayout.draw(inkCanvas); inkCanvas.restore()
        textPaint.clearShadowLayer(); textPaint.maskFilter = null; inkBitmap = bitmap
    }

    private fun updatePageRect(w: Int, h: Int) { if (w > 0 && h > 0) pageRect.set(dp(3f), dp(3f), w - dp(3f), h - dp(3f)) }
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    companion object { private const val LINE_PAUSE_UNITS = 0.17f }
}
