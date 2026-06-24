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
 *
 * IMPROVEMENT: Better keyboard-style sound and haptics for mobile
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
        // Use standard keypress sound effect (FX_KEY doesn't exist in Android SDK)
        if (soundEnabled) {
            audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.8f)
        }
    }

    private fun vibrateTick(view: View) {
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // HD Haptic System: Linear Resonant Actuators using Composition
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
                    .compose()
                v.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Stronger vibration for older devices (15ms, amplitude 100)
                v.vibrate(VibrationEffect.createOneShot(15, 100))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(15)  // Slightly longer vibration
            }
        } else {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        }
    }
}
