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
import com.deckkey.core.model.KeyType
import com.deckkey.core.model.KeyboardLayout
import com.deckkey.core.model.Row
import com.deckkey.core.model.Modifier
import com.deckkey.core.model.EmojiData
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.view.Gravity
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
    private var lastTextLayoutId: String = "qwerty"
    private var settings: Settings = Settings.DEFAULT
    private val clipboardHistory = ArrayList<String>()

    private lateinit var keyboardContainer: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private lateinit var suggestionTextViews: Array<TextView>
    private var currentEmojiCategory = "smile"
    private var isTranslationVisible = false

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
            onF1 = { openSettingsApp() },
        )
        settingsRepo = SettingsRepository(this)
        currentLayoutId = layouts.defaultLayoutId

        settingsRepo.settings
            .onEach { applySettings(it) }
            .launchIn(scope)
    }

    override fun onCreateInputView(): View {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#1e1f22"))
        }

        // Create Suggestion Bar
        suggestionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * resources.displayMetrics.density).toInt()
            )
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#15161a"))
        }

        // Add 3 suggestion text views
        suggestionTextViews = Array(3) { index ->
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1.0f
                )
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#e8e8e8"))
                textSize = 14f
                isClickable = true
                isFocusable = false
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    onSuggestionClicked(this.text.toString())
                }
            }
        }

        for (tv in suggestionTextViews) {
            suggestionBar.addView(tv)
        }

        container.addView(suggestionBar)

        val view = KeyboardView(context).apply {
            listener = this@DeckKeyService
            modifiers = this@DeckKeyService.modifiers
            previewEnabled = settings.previewPopup
            showHelperLabels = settings.showHelperLabels
            keyHeightPx = settings.keyHeightDp * resources.displayMetrics.density
            setLayout(layouts.load(currentLayoutId))
        }
        keyboardView = view
        container.addView(view)

        keyboardContainer = container
        updateSuggestions()

        return container
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // New field focused: drop transient latches so we don't leak modifier state.
        modifiers.resetTransient()
        repeat.stop()
        keyboardView?.refreshModifierVisuals()

        // Capture clipboard item
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.primaryClip?.let { clip ->
                if (clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank() && !clipboardHistory.contains(text)) {
                        clipboardHistory.add(0, text)
                        if (clipboardHistory.size > 5) {
                            clipboardHistory.removeAt(5)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DeckKey", "Failed to access clipboard", e)
        }
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
            view.showHelperLabels = s.showHelperLabels
            view.keyHeightPx = s.keyHeightDp * resources.displayMetrics.density
            val theme = Themes.byId(s.themeId)
            view.applyTheme(theme)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                window?.window?.let { win ->
                    if (theme.isGlassmorphism) {
                        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        win.attributes.blurBehindRadius = 30
                    } else {
                        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                    win.attributes = win.attributes
                }
            }
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
        val nextId = when (id) {
            "next_lang" -> getNextLanguageId(currentLayoutId)
            "back" -> lastTextLayoutId
            else -> id
        }
        
        if (nextId in listOf("qwerty", "hindi", "urdu", "chinese")) {
            lastTextLayoutId = nextId
        }
        
        currentLayoutId = nextId
        if (nextId == "clipboard") {
            keyboardView?.setLayout(getClipboardLayout())
        } else if (nextId == "emoji") {
            keyboardView?.setLayout(getEmojiLayout())
        } else {
            keyboardView?.setLayout(layouts.load(nextId))
        }
        keyboardContainer.post { updateSuggestions() }
    }

    private fun getNextLanguageId(current: String): String {
        val langs = if (settings.isPro) {
            listOf("qwerty", "hindi", "urdu", "chinese")
        } else {
            listOf("qwerty", "hindi")
        }
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
        if (key.output == "__CLEAR_CLIPBOARD__") {
            clipboardHistory.clear()
            switchLayout("clipboard")
            return
        }
        if (key.output != null && key.output.startsWith("__EMOJI_CAT_")) {
            currentEmojiCategory = key.output.removePrefix("__EMOJI_CAT_").lowercase()
            switchLayout("emoji")
            return
        }

        val ic = currentInputConnection
        if (ic != null && key.baseOutput.length == 1 && (key.baseOutput == " " || key.baseOutput == "\n")) {
            val before = ic.getTextBeforeCursor(30, 0)?.toString() ?: ""
            val lastWordMatch = Regex("([!/][a-zA-Z0-9_]+)$").find(before)
            if (lastWordMatch != null) {
                val trigger = lastWordMatch.value
                val macroValue = settings.macros[trigger]
                if (macroValue != null) {
                    ic.deleteSurroundingText(trigger.length, 0)
                    ic.commitText(macroValue + key.baseOutput, 1)
                    keyboardContainer.post { updateSuggestions() }
                    return
                }
            }
        }

        dispatcher.dispatch(key, ic)
        keyboardContainer.post { updateSuggestions() }
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

    override fun onSpaceDrag(stepsX: Int, stepsY: Int) {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        
        if (stepsX != 0) {
            val keyCodeX = if (stepsX < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            for (i in 0 until Math.abs(stepsX)) {
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCodeX, 0, 0))
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCodeX, 0, 0))
            }
        }
        if (stepsY != 0) {
            val keyCodeY = if (stepsY < 0) android.view.KeyEvent.KEYCODE_DPAD_UP else android.view.KeyEvent.KEYCODE_DPAD_DOWN
            for (i in 0 until Math.abs(stepsY)) {
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCodeY, 0, 0))
                ic.sendKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCodeY, 0, 0))
            }
        }
    }

    override fun onSpaceSwipe(direction: Int) {
        val allowedLangs = if (settings.isPro) {
            listOf("qwerty", "hindi", "urdu", "chinese")
        } else {
            listOf("qwerty", "hindi")
        }
        var idx = allowedLangs.indexOf(currentLayoutId)
        if (idx == -1) idx = 0
        val nextIdx = if (direction < 0) {
            (idx - 1 + allowedLangs.size) % allowedLangs.size
        } else {
            (idx + 1) % allowedLangs.size
        }
        switchLayout(allowedLangs[nextIdx])
        
        val label = when (allowedLangs[nextIdx]) {
            "qwerty" -> "English"
            "hindi" -> "Hindi"
            "urdu" -> "Urdu"
            "chinese" -> "Chinese"
            else -> allowedLangs[nextIdx]
        }
        Toast.makeText(this, "Language: $label", Toast.LENGTH_SHORT).show()
    }

    override fun onSpaceLongPress() {
        translateSelectedText()
    }

    override fun onKeyLongPress(key: Key) {
        if (currentLayoutId == "emoji" && !key.output.isNullOrEmpty() && !key.output.startsWith("__EMOJI_CAT_")) {
            val name = EmojiData.getEmojiName(key.label)
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getEmojiLayout(): KeyboardLayout {
        val rows = ArrayList<Row>()

        // Row 1: Category Selection Tabs
        val headerKeys = listOf(
            Key(label = "← Back", type = KeyType.LAYOUT_SWITCH, switchTo = "back", widthWeight = 1.6f),
            Key(label = "😀 Smile", type = KeyType.CHAR, output = "__EMOJI_CAT_SMILE__", widthWeight = 1.6f),
            Key(label = "🦁 Animal", type = KeyType.CHAR, output = "__EMOJI_CAT_ANIMAL__", widthWeight = 1.6f),
            Key(label = "🍔 Food", type = KeyType.CHAR, output = "__EMOJI_CAT_FOOD__", widthWeight = 1.6f),
            Key(label = "🇵🇰 Flag", type = KeyType.CHAR, output = "__EMOJI_CAT_FLAG__", widthWeight = 1.6f)
        )
        rows.add(Row(keys = headerKeys, heightWeight = 0.8f))

        val emojiList = when (currentEmojiCategory) {
            "animal" -> EmojiData.animals
            "food" -> EmojiData.food
            "flag" -> EmojiData.flags
            else -> EmojiData.smileys
        }

        val emojisPerRow = 10
        val itemsToShow = emojiList.take(60)
        for (i in itemsToShow.indices step emojisPerRow) {
            val rowEmojis = itemsToShow.subList(i, Math.min(i + emojisPerRow, itemsToShow.size))
            val keys = rowEmojis.map { emoji ->
                Key(label = emoji, type = KeyType.CHAR, output = emoji, widthWeight = 0.8f)
            }
            rows.add(Row(keys = keys, heightWeight = 0.9f))
        }

        return KeyboardLayout(
            id = "emoji",
            label = "Emoji",
            rows = rows
        )
    }

    private fun getClipboardLayout(): KeyboardLayout {
        val rows = ArrayList<Row>()

        // Row 1: Header/Navigation
        val headerKeys = listOf(
            Key(label = "← Back", type = KeyType.LAYOUT_SWITCH, switchTo = "back", widthWeight = 2f),
            Key(label = "Clipboard History", type = KeyType.CHAR, output = "", widthWeight = 4f),
            Key(label = "Clear", type = KeyType.CHAR, output = "__CLEAR_CLIPBOARD__", widthWeight = 2f)
        )
        rows.add(Row(keys = headerKeys, heightWeight = 0.8f))

        // Rows 2+: Clipboard items
        if (clipboardHistory.isEmpty()) {
            rows.add(Row(keys = listOf(
                Key(label = "(Clipboard is empty)", type = KeyType.CHAR, output = "", widthWeight = 1f)
            )))
        } else {
            for (item in clipboardHistory.take(4)) {
                val displayLabel = if (item.length > 30) item.take(28) + "..." else item
                rows.add(Row(keys = listOf(
                    Key(label = displayLabel, type = KeyType.CHAR, output = item, widthWeight = 1f)
                )))
            }
        }

        return KeyboardLayout(
            id = "clipboard",
            label = "Clipboard",
            rows = rows
        )
    }

    private fun openSettingsApp() {
        val intent = android.content.Intent(this, com.deckkey.app.MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun updateSuggestions() {
        if (isTranslationVisible) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""

        val lastWordMatch = Regex("([a-zA-Z0-9\\u0600-\\u06FF\\u0900-\\u097F\\u4E00-\\u9FFF]+)$").find(before)
        val lastWord = lastWordMatch?.value ?: ""

        val suggestions = getSuggestionsForWord(lastWord)

        for (i in 0..2) {
            if (i < suggestions.size) {
                suggestionTextViews[i].text = suggestions[i]
                suggestionTextViews[i].visibility = View.VISIBLE
            } else {
                suggestionTextViews[i].text = ""
                suggestionTextViews[i].visibility = View.INVISIBLE
            }
        }
    }

    private fun getSuggestionsForWord(word: String): List<String> {
        val results = ArrayList<String>()
        if (word.isEmpty()) {
            return listOf("the", "and", "you")
        }

        // 1. Emoji suggestions (Free!)
        val emojiSuggestions = getEmojiSuggestions(word)
        results.addAll(emojiSuggestions)

        // 2. Word completion dictionary suggestions
        val wordList = when (currentLayoutId) {
            "urdu" -> listOf("کیا", "ہے", "ہیں", "کو", "نے", "سے", "کا", "کی", "کے", "پر", "میں", "تو", "بھی", "یہ", "وہ", "کر", "نہ", "ہوں", "ہم", "تم", "آپ", "اور", "نہیں", "ہو", "تھا", "تھی", "تھے", "گا", "گی", "گے", "سلام", "کیسے", "امید", "ٹھیک", "شکریہ")
            "hindi" -> listOf("है", "हैं", "को", "ने", "से", "का", "की", "के", "पर", "में", "तो", "भी", "यह", "वह", "कर", "न", "हूं", "हम", "तुम", "आप", "और", "नहीं", "हो", "था", "थी", "थे", "गा", "गी", "गे", "नमस्ते", "कैसे", "ठीक", "धन्यवाद")
            "chinese" -> listOf("的", "一", "是", "在", "不", "了", "有", "人", "我", "他", "这", "个", "们", "中", "来", "上", "大", "为", "和", "国", "地", "到", "以", "说", "时", "要", "会", "自", "出", "下", "你好", "谢谢")
            else -> listOf("the", "be", "to", "of", "and", "a", "in", "that", "have", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "hello", "thanks", "good", "morning", "night", "great", "please")
        }

        val wLower = word.lowercase()
        val wordMatches = wordList.filter { it.lowercase().startsWith(wLower) }.take(3 - results.size)
        results.addAll(wordMatches)

        return results.distinct().take(3)
    }

    private fun getEmojiSuggestions(word: String): List<String> {
        val cleanWord = word.trim().lowercase()
        if (cleanWord.isEmpty()) return emptyList()

        val map = mapOf(
            "haha" to listOf("😂", "😀", "😆"),
            "smile" to listOf("😀", "😊", "🙂"),
            "love" to listOf("❤️", "😍", "🥰"),
            "sad" to listOf("😭", "😢", "😔"),
            "angry" to listOf("😡", "😠", "🤬"),
            "cool" to listOf("😎", "😏"),
            "party" to listOf("🎉", "🥳"),
            "think" to listOf("🤔", "🧐"),
            "thanks" to listOf("🙏", "👍", "🙌"),
            "yes" to listOf("👍", "✅"),
            "no" to listOf("👎", "❌"),
            "hot" to listOf("🔥", "🥵"),
            "cat" to listOf("🐱", "🐈"),
            "dog" to listOf("🐶", "🐕"),
            "lion" to listOf("🦁"),
            "tiger" to listOf("🐯"),
            "panda" to listOf("🐼"),
            "pizza" to listOf("🍕"),
            "burger" to listOf("🍔"),
            "apple" to listOf("🍎", "🍏"),
            "coffee" to listOf("☕"),
            "beer" to listOf("🍺"),
            "pakistan" to listOf("🇵🇰"),
            "pakistani" to listOf("🇵🇰"),
            "india" to listOf("🇮🇳"),
            "indian" to listOf("🇮🇳"),
            "america" to listOf("🇺🇸"),
            "usa" to listOf("🇺🇸"),
            "uk" to listOf("🇬🇧"),
            "england" to listOf("🇬🇧"),
            "saudi" to listOf("🇸🇦"),
            "china" to listOf("🇨🇳"),
            "chinese" to listOf("🇨🇳"),
            "palestine" to listOf("🇵🇸"),
            "turkey" to listOf("🇹🇷"),
            "germany" to listOf("🇩🇪"),
            "france" to listOf("🇫🇷"),
            "japan" to listOf("🇯🇵")
        )

        val results = ArrayList<String>()
        for ((trigger, emojis) in map) {
            if (trigger.startsWith(cleanWord) || cleanWord.startsWith(trigger)) {
                results.addAll(emojis)
            }
        }
        return results.distinct().take(3)
    }

    private fun onSuggestionClicked(suggestionText: String) {
        if (suggestionText.isEmpty()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
        val lastWordMatch = Regex("([a-zA-Z0-9\\u0600-\\u06FF\\u0900-\\u097F\\u4E00-\\u9FFF]+)$").find(before)
        if (lastWordMatch != null) {
            val lastWord = lastWordMatch.value
            ic.deleteSurroundingText(lastWord.length, 0)
        }
        ic.commitText(suggestionText + " ", 1)
        keyboardContainer.post { updateSuggestions() }
    }

    private fun translateSelectedText() {
        val ic = currentInputConnection ?: return
        var textToTranslate = ic.getSelectedText(0)?.toString() ?: ""
        if (textToTranslate.isEmpty()) {
            val before = ic.getTextBeforeCursor(30, 0)?.toString() ?: ""
            val lastWordMatch = Regex("([a-zA-Z0-9\\u0600-\\u06FF\\u0900-\\u097F\\u4E00-\\u9FFF]+)$").find(before)
            textToTranslate = lastWordMatch?.value ?: ""
        }

        if (textToTranslate.isEmpty()) {
            Toast.makeText(this, "No text to translate", Toast.LENGTH_SHORT).show()
            return
        }

        val translation = performOfflineTranslation(textToTranslate)
        showTranslationBar(textToTranslate, translation)
    }

    private fun performOfflineTranslation(text: String): String {
        val dict = mapOf(
            "کیا" to "what", "ہے" to "is / has", "ہیں" to "are", "کو" to "to / for",
            "نے" to "(subject marker)", "سے" to "from / with", "کا" to "of", "کی" to "of",
            "کے" to "of", "پر" to "on", "میں" to "in", "تو" to "then", "بھی" to "also",
            "یہ" to "this / it", "وہ" to "that / he / she", "کر" to "do", "نہ" to "not",
            "ہوں" to "am", "ہم" to "we", "تم" to "you", "آپ" to "you (respectful)",
            "اور" to "and", "نہیں" to "no / not", "ہو" to "be", "تھا" to "was",
            "تھی" to "was", "تھے" to "were", "سلام" to "hello", "کیسے" to "how",
            "امید" to "hope", "ٹھیک" to "fine / okay", "شکریہ" to "thank you",
            "नमस्ते" to "hello / greetings", "धन्यवाद" to "thank you",
            "你" to "you", "好" to "good / well", "你好" to "hello", "谢谢" to "thank you",
            "的" to "of / 's", "我" to "I / me", "是" to "is / am / are", "在" to "at / in"
        )

        val words = text.split(Regex("\\s+"))
        val translatedWords = words.map { w ->
            dict[w] ?: dict[w.lowercase()] ?: "[$w]"
        }
        return translatedWords.joinToString(" ")
    }

    private fun showTranslationBar(original: String, translated: String) {
        isTranslationVisible = true
        suggestionBar.removeAllViews()

        val context = this
        val tv = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            setTextColor(android.graphics.Color.parseColor("#3d6ef5"))
            text = "Translated: $translated"
            textSize = 13f
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }

        val closeButton = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (40 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.RED)
            text = "✖"
            textSize = 16f
            isClickable = true
            setOnClickListener {
                restoreSuggestionBar()
            }
        }

        suggestionBar.addView(tv)
        suggestionBar.addView(closeButton)
    }

    private fun restoreSuggestionBar() {
        isTranslationVisible = false
        suggestionBar.removeAllViews()
        for (tv in suggestionTextViews) {
            suggestionBar.addView(tv)
        }
        updateSuggestions()
    }


    private fun showPowerMenuDialog() {
        Toast.makeText(this, "Power menu: Alt+F4 detected. Use device power button.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        repeat.destroy()
        speech.stop()
        scope.cancel()
        super.onDestroy()
    }
}
