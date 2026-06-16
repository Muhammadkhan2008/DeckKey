package com.deckkey.ime.feedback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Centralizes tactile + audible key feedback so the view stays focused on drawing.
 * Both channels are independently toggleable via settings.
 */
class FeedbackController(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var hapticsEnabled = true
    var soundEnabled = false

    /** Light tick on key-down. [view] is used for the system haptic fallback. */
    fun keyDown(view: View) {
        if (hapticsEnabled) vibrateTick(view)
        if (soundEnabled) audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.6f)
    }

    private fun vibrateTick(view: View) {
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(12, 40))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(12)
            }
        } else {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
    }
}
