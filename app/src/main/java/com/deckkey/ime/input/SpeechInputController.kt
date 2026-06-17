package com.deckkey.ime.input

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputConnection

/**
 * On-device speech-to-text for the mic key.
 *
 * Wraps Android's [SpeechRecognizer]. The recognized text is committed to the
 * focused field through the supplied [InputConnection] provider.
 *
 * Requires the RECORD_AUDIO permission to have been granted (the settings activity
 * asks for it; [isAvailable] + a permission check gate usage). An IME cannot prompt
 * for permissions itself, so callers should surface a hint if recognition fails to start.
 */
class SpeechInputController(
    private val context: Context,
    private val icProvider: () -> InputConnection?,
    private val onState: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun toggle() {
        if (isListening) stop() else start()
    }

    fun start() {
        if (isListening) return
        if (!isAvailable()) {
            onError("Speech recognition not available on this device")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
            isListening = true
            onState(true)
        } catch (t: Throwable) {
            onError("Could not start mic: ${t.message}")
            stop()
        }
    }

    fun stop() {
        isListening = false
        onState(false)
        recognizer?.run {
            try { stopListening() } catch (_: Throwable) {}
            try { destroy() } catch (_: Throwable) {}
        }
        recognizer = null
    }

    private fun commit(text: String) {
        if (text.isBlank()) return
        icProvider()?.commitText(text, 1)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val best = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            commit(if (best.isNotBlank()) "$best " else "")
            stop()
        }

        override fun onError(error: Int) {
            onError(errorText(error))
            stop()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission not granted — enable it in DeckKey app"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Speech error ($code)"
    }
}
