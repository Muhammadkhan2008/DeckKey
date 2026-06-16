package com.deckkey.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "deckkey_settings")

/**
 * Reads/writes [Settings] backed by Jetpack DataStore.
 * The IME service collects [settings] as a Flow so changes apply live.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val HAPTICS = booleanPreferencesKey("haptics")
        val SOUND = booleanPreferencesKey("sound")
        val PREVIEW = booleanPreferencesKey("preview_popup")
        val KEY_HEIGHT = intPreferencesKey("key_height_dp")
        val REPEAT_DELAY = intPreferencesKey("repeat_initial_delay")
        val REPEAT_INTERVAL = intPreferencesKey("repeat_interval")
        val MODIFIER_MODE = stringPreferencesKey("modifier_mode")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            haptics = p[Keys.HAPTICS] ?: Settings.DEFAULT.haptics,
            sound = p[Keys.SOUND] ?: Settings.DEFAULT.sound,
            previewPopup = p[Keys.PREVIEW] ?: Settings.DEFAULT.previewPopup,
            keyHeightDp = p[Keys.KEY_HEIGHT] ?: Settings.DEFAULT.keyHeightDp,
            repeatInitialDelayMs = p[Keys.REPEAT_DELAY] ?: Settings.DEFAULT.repeatInitialDelayMs,
            repeatIntervalMs = p[Keys.REPEAT_INTERVAL] ?: Settings.DEFAULT.repeatIntervalMs,
            modifierMode = runCatching {
                ModifierMode.valueOf(p[Keys.MODIFIER_MODE] ?: "")
            }.getOrDefault(Settings.DEFAULT.modifierMode),
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val current = Settings(
                haptics = p[Keys.HAPTICS] ?: Settings.DEFAULT.haptics,
                sound = p[Keys.SOUND] ?: Settings.DEFAULT.sound,
                previewPopup = p[Keys.PREVIEW] ?: Settings.DEFAULT.previewPopup,
                keyHeightDp = p[Keys.KEY_HEIGHT] ?: Settings.DEFAULT.keyHeightDp,
                repeatInitialDelayMs = p[Keys.REPEAT_DELAY] ?: Settings.DEFAULT.repeatInitialDelayMs,
                repeatIntervalMs = p[Keys.REPEAT_INTERVAL] ?: Settings.DEFAULT.repeatIntervalMs,
                modifierMode = runCatching {
                    ModifierMode.valueOf(p[Keys.MODIFIER_MODE] ?: "")
                }.getOrDefault(Settings.DEFAULT.modifierMode),
            )
            val next = transform(current)
            p[Keys.HAPTICS] = next.haptics
            p[Keys.SOUND] = next.sound
            p[Keys.PREVIEW] = next.previewPopup
            p[Keys.KEY_HEIGHT] = next.keyHeightDp
            p[Keys.REPEAT_DELAY] = next.repeatInitialDelayMs
            p[Keys.REPEAT_INTERVAL] = next.repeatIntervalMs
            p[Keys.MODIFIER_MODE] = next.modifierMode.name
        }
    }
}
