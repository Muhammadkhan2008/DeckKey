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
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        } catch (e: Exception) {
            onError("Failed to create SpeechRecognizer: ${e.message}")
            return
        }
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
            val processed = processVoiceText(best)
            commit(if (processed.isNotBlank()) "$processed " else "")
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

    private fun processVoiceText(text: String): String {
        if (text.isBlank()) return ""
        var result = text.trim()

        // 1. Number word translation (English, Hindi, Urdu, Romanized)
        val numberMap = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "ten" to "10",
            "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4", "पांच" to "5", "छह" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9", "दस" to "10",
            "ایک" to "1", "دو" to "2", "تین" to "3", "چار" to "4", "پانچ" to "5", "چھ" to "6", "سات" to "7", "آٹھ" to "8", "نو" to "9", "دس" to "10",
            "ek" to "1", "do" to "2", "teen" to "3", "chaar" to "4", "paanch" to "5", "che" to "6", "saat" to "7", "aath" to "8", "nau" to "9", "das" to "10"
        )
        for ((word, digit) in numberMap) {
            val regex = "\\b$word\\b".toRegex(RegexOption.IGNORE_CASE)
            result = result.replace(regex, digit)
        }

        // 2. Advanced Punctuation Voice Commands (English + Urdu/Hindi)
        val commands = mapOf(
            "period" to ".", "full stop" to ".", "comma" to ",", 
            "question mark" to "?", "exclamation mark" to "!", 
            "new line" to "\n", "next line" to "\n", "space" to " ",
            "khatma" to "۔", "khatmah" to "۔", 
            "purna viram" to "।", "pooran viram" to "।",
            "sawaal" to "؟", "sawal" to "؟",
            "sakta" to "،", "saktah" to "،"
        )
        for ((phrase, replacement) in commands) {
            val regex = "\\b$phrase\\b".toRegex(RegexOption.IGNORE_CASE)
            result = result.replace(regex, replacement)
        }

        // 3. Auto-capitalize pronoun "I" and contractions
        result = result.replace(Regex("\\bi\\b"), "I")
        result = result.replace(Regex("\\bi'm\\b", RegexOption.IGNORE_CASE), "I'm")
        result = result.replace(Regex("\\bi am\\b", RegexOption.IGNORE_CASE), "I am")

        // 4. Smart Capitalization: Capitalize the first letter of each sentence
        if (result.isNotEmpty()) {
            val sentences = result.split(Regex("(?<=[.!?।۔])\\s+"))
            result = sentences.joinToString(" ") { s ->
                if (s.isNotEmpty()) {
                    s[0].uppercaseChar() + s.substring(1)
                } else {
                    ""
                }
            }
        }

        return result
    }
}
