package com.deckkey.core.prefs

/** How tap-on-a-modifier behaves. */
enum class ModifierMode {
    /** Tap = apply to next key only, then auto-clear (Android "sticky keys" style). */
    LATCH,
    /** Tap = stays active until tapped again (like a physical toggle). */
    TOGGLE,
}

/** Plain immutable snapshot of user settings. */
data class Settings(
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val previewPopup: Boolean = true,
    val keyHeightDp: Int = 52,
    val repeatInitialDelayMs: Int = 400,
    val repeatIntervalMs: Int = 45,
    val modifierMode: ModifierMode = ModifierMode.LATCH,
) {
    companion object {
        val DEFAULT = Settings()
        const val MIN_KEY_HEIGHT = 38
        const val MAX_KEY_HEIGHT = 80
        const val MIN_REPEAT_DELAY = 150
        const val MAX_REPEAT_DELAY = 800
        const val MIN_REPEAT_INTERVAL = 20
        const val MAX_REPEAT_INTERVAL = 150
    }
}
