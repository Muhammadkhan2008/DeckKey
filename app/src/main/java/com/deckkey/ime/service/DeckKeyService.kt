package com.deckkey.ime.service

import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.deckkey.core.model.Key
import com.deckkey.core.model.Modifier
import com.deckkey.core.prefs.Settings
import com.deckkey.core.prefs.SettingsRepository
import com.deckkey.core.theme.Themes
import com.deckkey.ime.feedback.FeedbackController
import com.deckkey.ime.input.KeyDispatcher
import com.deckkey.ime.input.KeyRepeatController
import com.deckkey.ime.input.ModifierStateManager
import com.deckkey.ime.input.SpeechInputController
import com.deckkey.ime.layout.LayoutManager
import com.deckkey.ime.view.KeyboardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The DeckKey input method. Owns the keyboard view and all input subsystems,
 * routes touch events from the view into actual text/key-event output.
 *
 * Lifecycle: Android creates one instance, calls [onCreateInputView] to build the
 * keyboard, and [onStartInput] each time a new field is focused.
 */
class DeckKeyService : InputMethodService(), KeyboardView.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var layouts: LayoutManager
    private lateinit var modifiers: ModifierStateManager
    private lateinit var dispatcher: KeyDispatcher
    private lateinit var repeat: KeyRepeatController
    private lateinit var feedback: FeedbackController
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var speech: SpeechInputController

    private var keyboardView: KeyboardView? = null
    private var currentLayoutId: String = "qwerty"
    private var settings: Settings = Settings.DEFAULT

    override fun onCreate() {
        super.onCreate()
        layouts = LayoutManager(this)
        modifiers = ModifierStateManager(onChanged = { keyboardView?.refreshModifierVisuals() })
        feedback = FeedbackController(this)
        repeat = KeyRepeatController(onRepeat = { key -> onRepeatTick(key) })
        speech = SpeechInputController(
            context = this,
            icProvider = { currentInputConnection },
            onState = { listening -> keyboardView?.setMicActive(listening) },
            onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
        )
        dispatcher = KeyDispatcher(
            modifiers = modifiers,
            onLayoutSwitch = { id -> switchLayout(id) },
            onImeSwitch = { switchToNextIme() },
            onMic = { onMicTapped() },
            onAltF4 = { showPowerMenuDialog() },
        )
        settingsRepo = SettingsRepository(this)
        currentLayoutId = layouts.defaultLayoutId

        settingsRepo.settings
            .onEach { applySettings(it) }
            .launchIn(scope)
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this).apply {
            listener = this@DeckKeyService
            modifiers = this@DeckKeyService.modifiers
            previewEnabled = settings.previewPopup
            keyHeightPx = settings.keyHeightDp * resources.displayMetrics.density
            setLayout(layouts.load(currentLayoutId))
        }
        keyboardView = view
        return view
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // New field focused: drop transient latches so we don't leak modifier state.
        modifiers.resetTransient()
        repeat.stop()
        keyboardView?.refreshModifierVisuals()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        repeat.stop()
        speech.stop()
    }

    private fun applySettings(s: Settings) {
        settings = s
        feedback.hapticsEnabled = s.haptics
        feedback.soundEnabled = s.sound
        repeat.initialDelayMs = s.repeatInitialDelayMs.toLong()
        repeat.intervalMs = s.repeatIntervalMs.toLong()

        // FIX: Check if keyboardView exists before applying settings
        val view = keyboardView
        if (view != null) {
            view.previewEnabled = s.previewPopup
            view.keyHeightPx = s.keyHeightDp * resources.displayMetrics.density
            view.applyTheme(Themes.byId(s.themeId))
            // Load background image if a URI is set
            if (s.backgroundUri.isNotEmpty()) {
                loadBackgroundBitmap(s.backgroundUri, s.backgroundDim)
            } else {
                view.setBackgroundImage(null, 0)
            }
        }
    }

    private fun loadBackgroundBitmap(uriString: String, dim: Int) {
        try {
            val bmp = if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                val uri = android.net.Uri.parse(uriString)
                contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            } else {
                android.graphics.BitmapFactory.decodeFile(uriString)
            }

            if (bmp != null) {
                keyboardView?.setBackgroundImage(bmp, dim)
            } else {
                Log.w("DeckKey", "Failed to decode bitmap from: $uriString")
                keyboardView?.setBackgroundImage(null, 0)
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.w("DeckKey", "Background image file not found: $uriString", e)
            keyboardView?.setBackgroundImage(null, 0)
        } catch (e: SecurityException) {
            Log.w("DeckKey", "Permission denied reading background image: $uriString", e)
            keyboardView?.setBackgroundImage(null, 0)
        } catch (e: Exception) {
            Log.e("DeckKey", "Unexpected error loading background image", e)
            keyboardView?.setBackgroundImage(null, 0)
        }
    }

    private fun switchLayout(id: String) {
        val nextId = if (id == "next_lang") {
            getNextLanguageId(currentLayoutId)
        } else {
            id
        }
        currentLayoutId = nextId
        keyboardView?.setLayout(layouts.load(nextId))
    }

    private fun getNextLanguageId(current: String): String {
        val langs = listOf("qwerty", "hindi", "urdu", "chinese")
        val idx = langs.indexOf(current)
        if (idx == -1) return "qwerty"
        return langs[(idx + 1) % langs.size]
    }

    private fun switchToNextIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.let {
            if (!switchToNextInputMethod(false)) {
                it.showInputMethodPicker()
            }
        }
    }

    private fun onMicTapped() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(
                this,
                "Mic permission needed — open the DeckKey app and grant microphone access",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        speech.toggle()
    }

    // ---- KeyboardView.Listener ----------------------------------------------

    override fun onKeyDown(key: Key) {
        feedback.keyDown(keyboardView ?: return)
    }

    override fun onKeyTap(key: Key) {
        dispatcher.dispatch(key, currentInputConnection)
    }

    override fun onModifierDown(modifier: Modifier) {
        feedback.keyDown(keyboardView ?: return)
        modifiers.onModifierDown(modifier, System.currentTimeMillis())
    }

    override fun onModifierUp(modifier: Modifier) {
        modifiers.onModifierUp(modifier, System.currentTimeMillis())
    }

    override fun onRepeatStart(key: Key) {
        repeat.start(key)
    }

    override fun onRepeatStop() {
        repeat.stop()
    }

    private fun onRepeatTick(key: Key) {
        // Re-dispatch the held key (arrows, backspace, space) at the repeat interval.
        dispatcher.dispatch(key, currentInputConnection)
    }

    private fun showPowerMenuDialog() {
        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("DeckKey Power Options")
        builder.setMessage("Select power action:")
        builder.setPositiveButton("Power Off") { _, _ ->
            Toast.makeText(this, "Shutting down... (Requires System Permissions/Root)", Toast.LENGTH_LONG).show()
            try { Runtime.getRuntime().exec("reboot -p") } catch (_: Exception) {}
        }
        builder.setNegativeButton("Restart") { _, _ ->
            Toast.makeText(this, "Restarting... (Requires System Permissions/Root)", Toast.LENGTH_LONG).show()
            try { Runtime.getRuntime().exec("reboot") } catch (_: Exception) {}
        }
        builder.setNeutralButton("Cancel") { dialog, _ -> dialog.dismiss() }
        val dialog = builder.create()
        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD)
        dialog.show()
    }

    override fun onDestroy() {
        repeat.destroy()  // FIX: Properly cleanup repeat controller
        speech.stop()
        scope.cancel()
        super.onDestroy()
    }
}
