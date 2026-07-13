package com.sameerakhtari.riddle.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.model.InkTool
import com.sameerakhtari.riddle.model.Stroke
import com.sameerakhtari.riddle.model.StrokePoint
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class DiaryCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    private var activeStroke: Stroke? = null
    private var activeStartedAt = 0L
    private var backgroundBitmap: Bitmap? = null
    private var inkCacheBitmap: Bitmap? = null
    private var inkCacheDirty = true
    private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageRect = RectF()
    private val pagePath = Path()

    var allowFinger: Boolean = false
    var selectedTool: InkTool = InkTool.PEN
    var penWidth: Float = AppSettings.DEFAULT_PEN_WIDTH
    var pressureSensitivity: Float = 0.82f
    var inkColor: Int = Color.rgb(40, 24, 13)
    var inkOpacity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var paperTheme: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            rebuildBackground()
            invalidate()
        }

    var onStrokeStarted: (() -> Unit)? = null
    var onStrokeCommitted: ((List<Stroke>) -> Unit)? = null

    private val mainInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bodyInkPaint = Paint(mainInkPaint)
    private val softInkPaint = Paint(mainInkPaint).apply {
        maskFilter = BlurMaskFilter(0.72f * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
    }
    private val highlightInkPaint = Paint(mainInkPaint)
    private val poolPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserPaint = Paint(mainInkPaint).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePageGeometry(w, h)
        invalidateInkCache()
        rebuildBackground()
    }

    override fun onDetachedFromWindow() {
        backgroundBitmap?.recycle()
        backgroundBitmap = null
        inkCacheBitmap?.recycle()
        inkCacheBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            ?: drawBackground(canvas, width, height, paperTheme)
        if (activeStroke?.tool == InkTool.ERASER) {
            renderInk(canvas, pageRect, strokes, inkOpacity)
        } else {
            ensureInkCache()
            cachePaint.alpha = (255f * inkOpacity).toInt().coerceIn(0, 255)
            inkCacheBitmap?.let { canvas.drawBitmap(it, 0f, 0f, cachePaint) }
            activeStroke?.let { renderInk(canvas, pageRect, listOf(it), inkOpacity) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || event.pointerCount == 0) return false
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus && !allowFinger) return false

        val effectiveTool = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> InkTool.ERASER
            event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 -> InkTool.ERASER
            else -> selectedTool
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!pageRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                requestFocus()
                onStrokeStarted?.invoke()
                redoStack.clear()
                activeStartedAt = event.eventTime
                activeStroke = Stroke(
                    tool = effectiveTool,
                    baseWidth = if (effectiveTool == InkTool.ERASER) ERASER_WIDTH else penWidth,
                ).also {
                    addPoint(it, event.x, event.y, event.pressure, event.eventTime)
                    strokes += it
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = activeStroke ?: return false
                for (index in 0 until event.historySize) {
                    addPoint(
                        stroke,
                        event.getHistoricalX(0, index),
                        event.getHistoricalY(0, index),
                        event.getHistoricalPressure(0, index),
                        event.getHistoricalEventTime(index),
                    )
                }
                addPoint(stroke, event.x, event.y, event.pressure, event.eventTime)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeStroke?.let { stroke ->
                    addPoint(stroke, event.x, event.y, event.pressure, event.eventTime)
                    if (stroke.points.size == 1) {
                        stroke.points += stroke.points.first().copy(
                            x = (stroke.points.first().x + 0.0001f).coerceAtMost(1f),
                        )
                    }
                }
                activeStroke = null
                invalidateInkCache()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onStrokeCommitted?.invoke(snapshotStrokes())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setAppearance(
        penWidth: Float,
        pressureSensitivity: Float,
        inkColor: Int,
        paperTheme: Int,
    ) {
        this.penWidth = penWidth.coerceIn(4f, 22f)
        this.pressureSensitivity = pressureSensitivity.coerceIn(0f, 1.5f)
        this.inkColor = inkColor
        this.paperTheme = paperTheme
        invalidateInkCache()
        invalidate()
    }

    fun setStrokes(value: List<Stroke>) {
        strokes.clear()
        strokes += value.map { it.deepCopy() }
        redoStack.clear()
        activeStroke = null
        invalidateInkCache()
        invalidate()
    }

    fun snapshotStrokes(): List<Stroke> = strokes.map { it.deepCopy() }
    fun isBlank(): Boolean = strokes.none { it.points.isNotEmpty() && it.tool == InkTool.PEN }

    fun inkBottomFraction(): Float = strokes
        .asSequence()
        .filter { it.tool == InkTool.PEN }
        .flatMap { it.points.asSequence() }
        .maxOfOrNull { it.y }
        ?.coerceIn(0f, 1f)
        ?: 0f

    fun pageRectCopy(): RectF = RectF(pageRect)

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        redoStack += strokes.removeAt(strokes.lastIndex)
        invalidateInkCache()
        invalidate()
        onStrokeCommitted?.invoke(snapshotStrokes())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        strokes += redoStack.removeAt(redoStack.lastIndex)
        invalidateInkCache()
        invalidate()
        onStrokeCommitted?.invoke(snapshotStrokes())
        return true
    }

    fun clear() {
        if (strokes.isEmpty()) return
        redoStack.clear()
        strokes.clear()
        activeStroke = null
        invalidateInkCache()
        invalidate()
        onStrokeCommitted?.invoke(emptyList())
    }

    /** Exports only the parchment, keeping toolbar chrome out of OCR/model input. */
    fun exportPng(maxDimension: Int = 1280): ByteArray {
        check(pageRect.width() > 0 && pageRect.height() > 0) { "The writing surface is not ready yet." }
        val scale = minOf(1f, maxDimension.toFloat() / max(pageRect.width(), pageRect.height()))
        val targetWidth = max(1, (pageRect.width() * scale).toInt())
        val targetHeight = max(1, (pageRect.height() * scale).toInt())
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val exportCanvas = Canvas(bitmap)
        val exportRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        drawParchment(exportCanvas, exportRect, paperTheme, includeShadow = false)
        renderInk(exportCanvas, exportRect, strokes, 1f)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun addPoint(
        stroke: Stroke,
        rawX: Float,
        rawY: Float,
        pressure: Float,
        eventTime: Long,
    ) {
        if (pageRect.width() <= 0 || pageRect.height() <= 0) return
        val point = StrokePoint(
            x = ((rawX - pageRect.left) / pageRect.width()).coerceIn(0f, 1f),
            y = ((rawY - pageRect.top) / pageRect.height()).coerceIn(0f, 1f),
            pressure = pressure.coerceIn(0.05f, 1.5f),
            timeDeltaMs = (eventTime - activeStartedAt).coerceAtLeast(0L),
        )
        val previous = stroke.points.lastOrNull()
        if (stroke.points.size >= MAX_POINTS_PER_STROKE) return
        if (previous == null || abs(previous.x - point.x) > 0.00038f ||
            abs(previous.y - point.y) > 0.00038f
        ) {
            stroke.points += point
        }
    }

    private fun renderInk(
        canvas: Canvas,
        rect: RectF,
        sourceStrokes: List<Stroke> = strokes,
        opacity: Float = inkOpacity,
    ) {
        val layer = canvas.saveLayer(rect, null)
        sourceStrokes.forEach { stroke ->
            if (stroke.tool == InkTool.ERASER) {
                drawStroke(canvas, stroke, eraserPaint, rect, 1f, false)
            } else {
                softInkPaint.color = inkColor
                softInkPaint.alpha = (38f * opacity).toInt()
                bodyInkPaint.color = inkColor
                bodyInkPaint.alpha = (116f * opacity).toInt()
                mainInkPaint.color = inkColor
                mainInkPaint.alpha = (238f * opacity).toInt()
                highlightInkPaint.color = Color.rgb(92, 55, 29)
                highlightInkPaint.alpha = (42f * opacity).toInt()

                drawStroke(canvas, stroke, softInkPaint, rect, 1.62f, true)
                drawStroke(canvas, stroke, bodyInkPaint, rect, 1.23f, true)
                drawStroke(canvas, stroke, mainInkPaint, rect, 1f, true)
                drawStroke(canvas, stroke, highlightInkPaint, rect, 0.42f, true, offset = -0.30f)
                drawPools(canvas, stroke, rect, opacity)
            }
        }
        canvas.restoreToCount(layer)
    }

    private fun ensureInkCache() {
        if (!inkCacheDirty && inkCacheBitmap?.width == width && inkCacheBitmap?.height == height) return
        if (width <= 0 || height <= 0) return
        inkCacheBitmap?.recycle()
        inkCacheBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val committed = if (activeStroke == null) strokes else strokes.filter { it !== activeStroke }
            renderInk(Canvas(bitmap), pageRect, committed, 1f)
        }
        inkCacheDirty = false
    }

    private fun invalidateInkCache() {
        inkCacheDirty = true
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: Stroke,
        paint: Paint,
        rect: RectF,
        widthMultiplier: Float,
        fountainNib: Boolean,
        offset: Float = 0f,
    ) {
        if (stroke.points.isEmpty()) return
        if (stroke.points.size == 1) {
            val point = stroke.points.first()
            paint.strokeWidth = strokeWidth(stroke, point, null, rect.width(), fountainNib) * widthMultiplier
            canvas.drawPoint(rect.left + point.x * rect.width(), rect.top + point.y * rect.height(), paint)
            return
        }

        for (index in 1 until stroke.points.size) {
            val previous = stroke.points[index - 1]
            val current = stroke.points[index]
            paint.strokeWidth = strokeWidth(stroke, current, previous, rect.width(), fountainNib) * widthMultiplier
            val startX = rect.left + previous.x * rect.width()
            val startY = rect.top + previous.y * rect.height()
            val currentX = rect.left + current.x * rect.width()
            val currentY = rect.top + current.y * rect.height()
            val angle = atan2(currentY - startY, currentX - startX)
            val normalX = -kotlin.math.sin(angle) * offset * paint.strokeWidth
            val normalY = cos(angle) * offset * paint.strokeWidth
            val path = Path().apply {
                moveTo(startX + normalX, startY + normalY)
                if (index < stroke.points.lastIndex) {
                    val next = stroke.points[index + 1]
                    val endX = rect.left + (current.x + next.x) * 0.5f * rect.width()
                    val endY = rect.top + (current.y + next.y) * 0.5f * rect.height()
                    quadTo(currentX + normalX, currentY + normalY, endX + normalX, endY + normalY)
                } else {
                    lineTo(currentX + normalX, currentY + normalY)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawPools(canvas: Canvas, stroke: Stroke, rect: RectF, opacity: Float) {
        if (stroke.points.isEmpty()) return
        poolPaint.color = inkColor
        poolPaint.alpha = (65f * opacity).toInt()
        listOfNotNull(stroke.points.firstOrNull(), stroke.points.lastOrNull()).forEach { point ->
            val radius = strokeWidth(stroke, point, null, rect.width(), true) * 0.44f
            canvas.drawOval(
                rect.left + point.x * rect.width() - radius,
                rect.top + point.y * rect.height() - radius * 0.60f,
                rect.left + point.x * rect.width() + radius,
                rect.top + point.y * rect.height() + radius * 0.60f,
                poolPaint,
            )
        }
    }

    private fun strokeWidth(
        stroke: Stroke,
        point: StrokePoint,
        previous: StrokePoint?,
        targetWidth: Float,
        fountainNib: Boolean,
    ): Float {
        val screenScale = targetWidth / max(1f, pageRect.width())
        return when (stroke.tool) {
            InkTool.ERASER -> stroke.baseWidth * screenScale
            InkTool.PEN -> {
                val normalized = point.pressure.coerceIn(0.05f, 1.2f)
                val pressureFactor =
                    (0.90f + pressureSensitivity * (normalized - 0.30f)).coerceIn(0.76f, 1.82f)
                val effectiveBase = max(stroke.baseWidth, penWidth * 0.90f)
                val speedFactor = if (previous == null) 1.05f else {
                    val distance = hypot(point.x - previous.x, point.y - previous.y)
                    val delta = (point.timeDeltaMs - previous.timeDeltaMs).coerceAtLeast(1L)
                    val speed = distance / delta.toFloat()
                    (1.16f - speed * 720f).coerceIn(0.78f, 1.24f)
                }
                val nibFactor = if (!fountainNib || previous == null) 1f else {
                    val angle = atan2(point.y - previous.y, point.x - previous.x)
                    (0.78f + 0.30f * abs(cos(angle - NIB_ANGLE))).coerceIn(0.78f, 1.08f)
                }
                effectiveBase * screenScale * pressureFactor * speedFactor * nibFactor
            }
        }.coerceAtLeast(1.5f)
    }

    private fun updatePageGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val side = dp(3f)
        val top = dp(3f)
        val bottom = dp(3f)
        pageRect.set(side, top, w - side, h - bottom)
        buildRoughPagePath(pageRect, pagePath)
    }

    private fun rebuildBackground() {
        if (width <= 0 || height <= 0) return
        backgroundBitmap?.recycle()
        backgroundBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawBackground(Canvas(it), width, height, paperTheme)
        }
    }

    private fun drawBackground(canvas: Canvas, w: Int, h: Int, theme: Int) {
        if (w <= 0 || h <= 0) return
        val leather = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(Color.rgb(5, 5, 4), Color.rgb(22, 16, 11), Color.rgb(4, 4, 3)),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), leather)
        val random = Random(0x52494444)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat((w * h / 5_000).coerceIn(260, 1_200)) {
            grain.color = if (random.nextBoolean()) {
                Color.argb(random.nextInt(4, 16), 145, 105, 57)
            } else {
                Color.argb(random.nextInt(4, 14), 0, 0, 0)
            }
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            canvas.drawOval(x, y, x + random.nextFloat() * 8f + 1f, y + random.nextFloat() * 2f + 0.5f, grain)
        }
        drawParchment(canvas, pageRect, theme, includeShadow = true)
    }

    private fun drawParchment(canvas: Canvas, rect: RectF, theme: Int, includeShadow: Boolean) {
        val path = if (rect === pageRect) pagePath else Path().also { buildRoughPagePath(rect, it) }
        if (includeShadow) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(205, 0, 0, 0)
                maskFilter = BlurMaskFilter(dp(9f), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(0f, dp(5f))
            canvas.drawPath(path, shadow)
            canvas.restore()
        }

        val palette = when (theme.coerceIn(0, 2)) {
            1 -> intArrayOf(Color.rgb(172, 105, 50), Color.rgb(205, 150, 82), Color.rgb(145, 76, 33))
            2 -> intArrayOf(Color.rgb(218, 197, 157), Color.rgb(239, 221, 180), Color.rgb(189, 157, 108))
            else -> intArrayOf(Color.rgb(184, 122, 59), Color.rgb(221, 169, 95), Color.rgb(159, 88, 38))
        }
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                palette,
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, paper)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                rect.centerX(),
                rect.top + rect.height() * 0.38f,
                rect.height() * 0.74f,
                intArrayOf(Color.argb(46, 255, 235, 182), Color.TRANSPARENT, Color.argb(92, 52, 22, 6)),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, glow)

        canvas.save()
        canvas.clipPath(path)
        val random = Random(0x50414745 + theme)
        val fleck = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat((rect.width() * rect.height() / 5_600).toInt().coerceIn(280, 1_300)) {
            val pale = random.nextBoolean()
            fleck.color = if (pale) {
                Color.argb(random.nextInt(3, 13), 255, 235, 181)
            } else {
                Color.argb(random.nextInt(4, 19), 73, 31, 9)
            }
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            canvas.drawCircle(x, y, random.nextFloat() * dp(0.9f) + 0.2f, fleck)
        }

        val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.55f)
            color = Color.argb(24, 73, 35, 13)
        }
        repeat(12) {
            val x = rect.left + random.nextFloat() * rect.width()
            val y = rect.top + random.nextFloat() * rect.height()
            canvas.drawLine(x, y, x + random.nextFloat() * dp(45f) - dp(22f), y + random.nextFloat() * dp(18f), crease)
        }
        canvas.restore()

        val burntEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4.8f)
            color = Color.argb(95, 68, 30, 10)
            maskFilter = BlurMaskFilter(dp(1.4f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, burntEdge)
        val sharpEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.1f)
            color = Color.argb(150, 78, 37, 13)
        }
        canvas.drawPath(path, sharpEdge)
    }

    private fun buildRoughPagePath(rect: RectF, target: Path) {
        target.reset()
        val random = Random(0x544F4D52 + rect.width().toInt() + rect.height().toInt())
        val step = max(dp(12f), rect.width() / 28f)
        target.moveTo(rect.left + dp(6f), rect.top)
        var x = rect.left + dp(6f)
        while (x < rect.right - dp(6f)) {
            target.lineTo(x, rect.top + random.nextFloat() * dp(5f) - dp(2.5f))
            x += step
        }
        target.lineTo(rect.right, rect.top + dp(7f))
        var y = rect.top + dp(7f)
        while (y < rect.bottom - dp(7f)) {
            target.lineTo(rect.right + random.nextFloat() * dp(5f) - dp(2.5f), y)
            y += step
        }
        target.lineTo(rect.right - dp(7f), rect.bottom)
        x = rect.right - dp(7f)
        while (x > rect.left + dp(7f)) {
            target.lineTo(x, rect.bottom + random.nextFloat() * dp(6f) - dp(3f))
            x -= step
        }
        target.lineTo(rect.left, rect.bottom - dp(8f))
        y = rect.bottom - dp(8f)
        while (y > rect.top + dp(8f)) {
            target.lineTo(rect.left + random.nextFloat() * dp(5f) - dp(2.5f), y)
            y -= step
        }
        target.close()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_POINTS_PER_STROKE = 4_096
        private const val ERASER_WIDTH = 42f
        private const val NIB_ANGLE = -0.78f
    }
}
