package com.deckkey.ime.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.deckkey.R
import com.deckkey.core.model.Key
import com.deckkey.core.model.KeyType
import com.deckkey.core.model.KeyboardLayout
import com.deckkey.core.model.Modifier
import com.deckkey.core.theme.Theme
import com.deckkey.core.theme.Themes
import com.deckkey.ime.input.ModState
import com.deckkey.ime.input.ModifierStateManager
import kotlin.math.max

/**
 * Custom, Canvas-drawn keyboard surface tuned for low-latency multitouch.
 *
 * Why a raw [View] (not the deprecated KeyboardView, not Compose):
 *  - direct [onTouchEvent] gives the lowest tap-to-emit latency,
 *  - full multitouch lets the user physically hold Shift/Ctrl and tap another key
 *    at the same time (true chording, like a real keyboard),
 *  - complete control of pressed-state visuals for a crisp, "snappy" feel.
 *
 * Rendering is resolution-independent: layouts use relative width/height weights,
 * which are converted to pixels here based on the measured width and key height.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Host callbacks. The service wires these to dispatch + repeat + feedback. */
    interface Listener {
        fun onKeyDown(key: Key)
        fun onKeyTap(key: Key)
        fun onModifierDown(modifier: Modifier)
        fun onModifierUp(modifier: Modifier)
        fun onRepeatStart(key: Key)
        fun onRepeatStop()
        fun onSpaceDrag(steps: Int)
    }

    var listener: Listener? = null
    var modifiers: ModifierStateManager? = null

    // IMPROVEMENT: Mobile-friendly default key height (46dp for standard PC-like ergonomics)
    var keyHeightPx: Float = dp(46f)
        set(value) { field = value; requestLayout() }

    var previewEnabled: Boolean = true

    private var micActive: Boolean = false

    private var layout: KeyboardLayout? = null
    private val positioned = ArrayList<PositionedKey>()

    /** pointerId -> the key that pointer is currently pressing. */
    private val activePointers = HashMap<Int, PositionedKey>()

    private var spacePointerId: Int = -1
    private var spaceDragStartX: Float = 0f
    private var isSpaceDragging: Boolean = false
    private val spaceDragThreshold: Float get() = dp(12f)

    // IMPROVEMENT: Slightly larger gap for thumb-friendly spacing
    private val gap = dp(4f)
    private val cornerRadius = dp(8f)

    /**
     * Movement tolerance. A finger may drift this far outside a key before the
     * press is cancelled — without it, natural finger jitter during a tap kills
     * the keypress, which is the main cause of "irresponsive" keys.
     * IMPROVEMENT: Increased for better touch tolerance on mobile
     */
    private val touchSlop = dp(26f)

    // ---- paints ----
    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.key_text)
        textSize = dp(17f)
    }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.key_text_dim)
        textSize = dp(10f)
    }

    private var theme: Theme = Themes.default
    private var colBg = theme.background
    private var colNormal = theme.keyNormal
    private var colNormalDown = theme.keyNormalPressed
    private var colSpecial = theme.keySpecial
    private var colSpecialDown = theme.keySpecialPressed
    private var colModActive = theme.modifierActive

    /** Optional gallery background drawn behind the keys. */
    private var backgroundBitmap: Bitmap? = null
    private var backgroundDimAlpha: Int = 115 // 0..255, over the image
    private val bgDimPaint = Paint()
    private val bgRect = RectF()

    // FIX: Cache bitmap scaling calculation to avoid recalculation every frame
    private var cachedBitmapRect: RectF? = null

    private val preview = KeyPreviewPopup(context)

    fun setLayout(newLayout: KeyboardLayout) {
        layout = newLayout
        requestLayout()
        invalidate()
    }

    /** Force a redraw when modifier state changes externally. */
    fun refreshModifierVisuals() = invalidate()

    /** Highlight the mic key while speech recognition is active. */
    fun setMicActive(active: Boolean) {
        if (micActive != active) {
            micActive = active
            invalidate()
        }
    }

    /** Apply a color theme and redraw. */
    fun applyTheme(t: Theme) {
        theme = t
        colBg = t.background
        colNormal = t.keyNormal
        colNormalDown = t.keyNormalPressed
        colSpecial = t.keySpecial
        colSpecialDown = t.keySpecialPressed
        colModActive = t.modifierActive
        keyText.color = t.text
        hintText.color = t.textDim
        invalidate()
    }

    /**
     * Set (or clear) the gallery background image. Pass null to remove it.
     * [dimPercent] (0..100) darkens the image so key labels stay legible.
     */
    fun setBackgroundImage(bitmap: Bitmap?, dimPercent: Int) {
        backgroundBitmap = bitmap
        backgroundDimAlpha = (dimPercent.coerceIn(0, 100) * 255 / 100)
        invalidate()
    }

    // ---- measurement & positioning -----------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val totalWeight = layout?.totalHeightWeight ?: 1f
        val height = (keyHeightPx * totalWeight + gap * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reflow(w.toFloat(), h.toFloat())

        // FIX: Recalculate bitmap scaling for new size (cache it)
        backgroundBitmap?.let { bmp ->
            val vw = w.toFloat()
            val vh = h.toFloat()
            val scale = maxOf(vw / bmp.width, vh / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (vw - dw) / 2f
            val top = (vh - dh) / 2f
            cachedBitmapRect = RectF(left, top, left + dw, top + dh)
        }
    }

    /** Convert relative weights into absolute pixel rectangles for the current size. */
    private fun reflow(width: Float, height: Float) {
        positioned.clear()
        val lay = layout ?: return
        val innerW = width - gap
        var y = gap
        for (row in lay.rows) {
            val rowHeight = keyHeightPx * row.heightWeight
            val totalKeyWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
            // Available width for keys after subtracting inter-key gaps.
            val gapsWidth = gap * row.keys.size
            val usableW = innerW - gapsWidth
            var x = gap
            for (key in row.keys) {
                val kw = usableW * (key.widthWeight / totalKeyWeight)
                val rect = RectF(x, y, x + kw, y + rowHeight - gap)
                positioned.add(PositionedKey(key, rect))
                x += kw + gap
            }
            y += rowHeight
        }
    }

    // ---- drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colBg)
        // Optional gallery background image, center-cropped to fill, with a dim overlay.
        backgroundBitmap?.let { bmp ->
            // FIX: Use cached bitmap rect instead of recalculating every frame
            cachedBitmapRect?.let { rect ->
                canvas.drawBitmap(bmp, null, rect, null)
            }
            // dim overlay so labels remain readable over busy photos
            bgDimPaint.color = Color.argb(backgroundDimAlpha, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgDimPaint)
        }
        val mods = modifiers
        for (pk in positioned) {
            val pressed = activePointers.values.any { it === pk }
            drawKey(canvas, pk, pressed, mods)
        }
    }

    private fun drawKey(canvas: Canvas, pk: PositionedKey, pressed: Boolean, mods: ModifierStateManager?) {
        val key = pk.key
        val r = pk.bounds
        val special = key.type != KeyType.CHAR

        val modActive = when {
            key.type == KeyType.MODIFIER && key.modifier != null ->
                mods?.isActive(key.modifier) == true
            key.type == KeyType.CAPS_LOCK -> mods?.capsLock == true
            key.type == KeyType.MIC -> micActive
            else -> false
        }

        val baseColor = when {
            modActive -> colModActive
            pressed && special -> colSpecialDown
            pressed -> colNormalDown
            special -> colSpecial
            else -> colNormal
        }

        // Draw shadow first (3D mechanical key depth effect)
        val shadowHeight = dp(2f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = adjustColorBrightness(baseColor, 0.65f)
        }
        val shadowR = RectF(r.left, r.top + shadowHeight, r.right, r.bottom + shadowHeight)
        canvas.drawRoundRect(shadowR, cornerRadius, cornerRadius, shadowPaint)

        // Draw key face slightly elevated
        val faceR = RectF(r.left, r.top, r.right, r.bottom)
        keyFill.color = baseColor
        val shader = android.graphics.LinearGradient(
            faceR.left, faceR.top, faceR.left, faceR.bottom,
            baseColor,
            adjustColorBrightness(baseColor, 0.82f),
            android.graphics.Shader.TileMode.CLAMP
        )
        keyFill.shader = shader
        canvas.drawRoundRect(faceR, cornerRadius, cornerRadius, keyFill)
        keyFill.shader = null

        // primary label
        val cx = r.centerX()
        val baseline = faceR.centerY() - (keyText.descent() + keyText.ascent()) / 2f
        keyText.color = if (modActive) Color.WHITE else theme.text
        val label = labelFor(key, mods)
        keyText.textSize = if (key.label.length > 2) dp(12f) else dp(16f)
        canvas.drawText(label, cx, baseline, keyText)

        // hint (corner)
        key.hint?.let { canvas.drawText(it, faceR.right - dp(9f), faceR.top + dp(13f), hintText) }

        // lock indicator dot for LOCKED modifiers / caps lock
        val locked = (key.type == KeyType.MODIFIER && key.modifier != null &&
            mods?.state(key.modifier) == ModState.LOCKED) ||
            (key.type == KeyType.CAPS_LOCK && mods?.capsLock == true)
        if (locked) {
            keyFill.color = Color.WHITE
            canvas.drawCircle(faceR.left + dp(8f), faceR.top + dp(8f), dp(2.5f), keyFill)
        }
    }

    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = Math.round(Color.red(color) * factor).coerceIn(0, 255)
        val g = Math.round(Color.green(color) * factor).coerceIn(0, 255)
        val b = Math.round(Color.blue(color) * factor).coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun labelFor(key: Key, mods: ModifierStateManager?): String {
        if (key.type != KeyType.CHAR) return key.label
        val shift = mods?.isShiftActive() == true
        val base = key.baseOutput
        val isLetter = base.length == 1 && base[0].isLetter()
        return when {
            isLetter -> {
                val upper = (mods?.capsLock == true) xor shift
                if (upper) base.uppercase() else base.lowercase()
            }
            shift -> key.shiftLabel ?: key.label
            else -> key.label
        }
    }

    // ---- touch handling (multitouch) ----------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                handleDown(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handleMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                handleUp(event.getPointerId(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (id in activePointers.keys.toList()) cancelPointer(id)
            }
        }
        return true
    }

    private fun handleDown(pointerId: Int, x: Float, y: Float) {
        val pk = hitTest(x, y) ?: return
        activePointers[pointerId] = pk
        val key = pk.key

        if (key.keyCode == 62 || key.label.equals("space", ignoreCase = true) || key.baseOutput == " ") {
            spacePointerId = pointerId
            spaceDragStartX = x
            isSpaceDragging = false
        }

        if (key.type == KeyType.MODIFIER && key.modifier != null) {
            listener?.onModifierDown(key.modifier)
        } else {
            listener?.onKeyDown(key)
            if (key.repeatable) {
                // Desktop feel: fire once instantly on press, then start the
                // delayed auto-repeat. Release will only stop it (no re-emit).
                listener?.onKeyTap(key)
                listener?.onRepeatStart(key)
            }
        }
        if (previewEnabled && shouldPreview(key)) preview.show(this, pk, labelFor(key, modifiers))
        invalidate()
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        val current = activePointers[pointerId] ?: return
        val b = current.bounds

        if (pointerId == spacePointerId) {
            val dx = x - spaceDragStartX
            val threshold = spaceDragThreshold
            if (isSpaceDragging || Math.abs(dx) > threshold) {
                if (!isSpaceDragging) {
                    isSpaceDragging = true
                    listener?.onRepeatStop()
                    preview.dismiss()
                }
                val steps = (dx / threshold).toInt()
                if (steps != 0) {
                    spaceDragStartX += steps * threshold
                    listener?.onSpaceDrag(steps)
                }
                return
            }
        }

        // Still within the key (plus slop tolerance)? Keep the press alive — this
        // stops natural finger jitter from cancelling a tap.
        val isWithinSlop = x >= b.left - touchSlop && x <= b.right + touchSlop &&
                           y >= b.top - touchSlop && y <= b.bottom + touchSlop

        if (isWithinSlop) return  // FIX: Clearer logic

        // Finger moved outside slop. Look for new key nearby.
        val moved = hitTest(x, y)
        val key = current.key
        if (key.repeatable && key.type != KeyType.MODIFIER) listener?.onRepeatStop()
        if (shouldPreview(key)) preview.dismiss()

        // FIX: Only switch to new key if we actually found one nearby
        if (moved != null && moved !== current) {
            activePointers[pointerId] = moved
            val mk = moved.key
            if (mk.type != KeyType.MODIFIER) {
                listener?.onKeyDown(mk)
                if (previewEnabled && shouldPreview(mk)) preview.show(this, moved, labelFor(mk, modifiers))
            }
            invalidate()
        } else {
            // Moved too far away without landing on another key - cancel
            cancelPointer(pointerId)
        }
    }

    private fun handleUp(pointerId: Int) {
        val pk = activePointers.remove(pointerId) ?: return
        val key = pk.key

        if (pointerId == spacePointerId) {
            spacePointerId = -1
            if (isSpaceDragging) {
                isSpaceDragging = false
                if (shouldPreview(key)) preview.dismiss()
                invalidate()
                return
            }
        }

        when {
            key.type == KeyType.MODIFIER && key.modifier != null ->
                listener?.onModifierUp(key.modifier)
            else -> {
                if (key.repeatable) {
                    // Already emitted on down + auto-repeat; release just stops it.
                    listener?.onRepeatStop()
                } else {
                    listener?.onKeyTap(key)
                }
            }
        }
        if (shouldPreview(key)) preview.dismiss()
        invalidate()
    }

    private fun cancelPointer(pointerId: Int) {
        val pk = activePointers.remove(pointerId) ?: return

        if (pointerId == spacePointerId) {
            spacePointerId = -1
            isSpaceDragging = false
        }

        if (pk.key.repeatable) listener?.onRepeatStop()
        preview.dismiss()
        invalidate()
    }

    private fun shouldPreview(key: Key): Boolean =
        key.type == KeyType.CHAR && key.baseOutput.length == 1

    private fun hitTest(x: Float, y: Float): PositionedKey? =
        positioned.firstOrNull { it.bounds.contains(x, y) }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        preview.dismiss()
        listener?.onRepeatStop()
        activePointers.clear()
    }
}
