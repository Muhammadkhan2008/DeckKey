package com.deckkey.ime.view

import android.content.Context
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
    }

    var listener: Listener? = null
    var modifiers: ModifierStateManager? = null

    var keyHeightPx: Float = dp(52f)
        set(value) { field = value; requestLayout() }

    var previewEnabled: Boolean = true

    private var layout: KeyboardLayout? = null
    private val positioned = ArrayList<PositionedKey>()

    /** pointerId -> the key that pointer is currently pressing. */
    private val activePointers = HashMap<Int, PositionedKey>()

    private val gap = dp(3f)
    private val cornerRadius = dp(7f)

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

    private val colBg = ContextCompat.getColor(context, R.color.kb_background)
    private val colNormal = ContextCompat.getColor(context, R.color.key_normal)
    private val colNormalDown = ContextCompat.getColor(context, R.color.key_normal_pressed)
    private val colSpecial = ContextCompat.getColor(context, R.color.key_special)
    private val colSpecialDown = ContextCompat.getColor(context, R.color.key_special_pressed)
    private val colModActive = ContextCompat.getColor(context, R.color.key_modifier_active)

    private val preview = KeyPreviewPopup(context)

    fun setLayout(newLayout: KeyboardLayout) {
        layout = newLayout
        requestLayout()
        invalidate()
    }

    /** Force a redraw when modifier state changes externally. */
    fun refreshModifierVisuals() = invalidate()

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
            else -> false
        }

        keyFill.color = when {
            modActive -> colModActive
            pressed && special -> colSpecialDown
            pressed -> colNormalDown
            special -> colSpecial
            else -> colNormal
        }
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, keyFill)

        // primary label
        val cx = r.centerX()
        val baseline = r.centerY() - (keyText.descent() + keyText.ascent()) / 2f
        keyText.color = if (modActive) Color.WHITE else ContextCompat.getColor(context, R.color.key_text)
        val label = labelFor(key, mods)
        keyText.textSize = if (key.label.length > 2) dp(13f) else dp(17f)
        canvas.drawText(label, cx, baseline, keyText)

        // hint (corner)
        key.hint?.let { canvas.drawText(it, r.right - dp(9f), r.top + dp(13f), hintText) }

        // lock indicator dot for LOCKED modifiers / caps lock
        val locked = (key.type == KeyType.MODIFIER && key.modifier != null &&
            mods?.state(key.modifier) == ModState.LOCKED) ||
            (key.type == KeyType.CAPS_LOCK && mods?.capsLock == true)
        if (locked) {
            keyFill.color = Color.WHITE
            canvas.drawCircle(r.left + dp(8f), r.top + dp(8f), dp(2.5f), keyFill)
        }
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
        if (previewEnabled && shouldPreview(key)) preview.show(this, pk)
        invalidate()
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        val current = activePointers[pointerId] ?: return
        if (current.bounds.contains(x, y)) return
        // Finger slid off the key: cancel it (and stop any repeat). No commit.
        val key = current.key
        if (key.type != KeyType.MODIFIER && key.repeatable) listener?.onRepeatStop()
        if (shouldPreview(key)) preview.dismiss()
        activePointers.remove(pointerId)
        invalidate()
    }

    private fun handleUp(pointerId: Int) {
        val pk = activePointers.remove(pointerId) ?: return
        val key = pk.key
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
