package com.deckkey.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.deckkey.R

/**
 * The small bubble that pops above a pressed character key — the classic
 * "you definitely hit this key" confirmation seen on desktop/phone keyboards.
 *
 * Implemented as a lightweight [PopupWindow] hosting a tiny self-drawing View,
 * so it can render outside the keyboard's bounds (above the top row).
 */
class KeyPreviewPopup(context: Context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val content = BubbleView(context)
    private val popup = PopupWindow(content, ViewGroupLayoutWrap, ViewGroupLayoutWrap, false).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
    }

    private val location = IntArray(2)

    fun show(anchor: View, pk: PositionedKey) {
        val text = pk.key.let { k ->
            if (k.shiftLabel != null) k.label else k.baseOutput
        }
        content.text = text

        val w = dp(54f).toInt()
        val h = dp(64f).toInt()
        anchor.getLocationInWindow(location)

        val keyCenterX = location[0] + pk.bounds.centerX()
        val x = (keyCenterX - w / 2f).toInt()
        val y = (location[1] + pk.bounds.top - h + dp(6f)).toInt()

        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        if (popup.isShowing) {
            popup.update(x, y, w, h)
        } else {
            popup.width = w
            popup.height = h
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private inner class BubbleView(context: Context) : View(context) {
        var text: String = ""
            set(value) { field = value; invalidate() }

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.preview_bg)
        }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.white)
            textAlign = Paint.Align.CENTER
            textSize = dp(26f)
        }

        override fun onDraw(canvas: Canvas) {
            val r = RectF(dp(3f), dp(3f), width - dp(3f), height - dp(10f))
            canvas.drawRoundRect(r, dp(8f), dp(8f), bg)
            val baseline = r.centerY() - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(text, r.centerX(), baseline, tp)
        }
    }

    private companion object {
        const val ViewGroupLayoutWrap = -2 // WRAP_CONTENT
    }
}
