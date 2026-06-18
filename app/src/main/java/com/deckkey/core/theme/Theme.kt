package com.deckkey.core.theme

import android.graphics.Color

/**
 * A keyboard color theme. All colors are ARGB ints so they can be applied
 * directly to Paint objects without resource lookups.
 *
 * Themes are pure data; [KeyboardView] reads the active theme when drawing.
 * The set of presets lives in [Themes].
 */
data class Theme(
    val id: String,
    val name: String,
    val background: Int,
    val keyNormal: Int,
    val keyNormalPressed: Int,
    val keySpecial: Int,
    val keySpecialPressed: Int,
    val modifierActive: Int,
    val text: Int,
    val textDim: Int,
    /** True for light backgrounds, so labels/preview can adapt. */
    val isLight: Boolean = false,
)

/**
 * Built-in theme presets. Far more than a single black — dark, light, and
 * several accent colorways. The user picks one in settings.
 */
object Themes {

    private fun c(hex: String): Int = Color.parseColor(hex)

    val MIDNIGHT = Theme(
        id = "midnight", name = "Midnight Black",
        background = c("#0D0D0F"), keyNormal = c("#1E1E24"), keyNormalPressed = c("#34343E"),
        keySpecial = c("#16161B"), keySpecialPressed = c("#2A2A33"),
        modifierActive = c("#3D7EFF"), text = c("#F2F2F5"), textDim = c("#8A8A95"),
    )

    val SLATE = Theme(
        id = "slate", name = "Slate Gray",
        background = c("#22272E"), keyNormal = c("#2D333B"), keyNormalPressed = c("#444C56"),
        keySpecial = c("#262C33"), keySpecialPressed = c("#3A424B"),
        modifierActive = c("#539BF5"), text = c("#ADBAC7"), textDim = c("#768390"),
    )

    val DRACULA = Theme(
        id = "dracula", name = "Dracula",
        background = c("#282A36"), keyNormal = c("#383A4A"), keyNormalPressed = c("#4D5066"),
        keySpecial = c("#2E303D"), keySpecialPressed = c("#44475A"),
        modifierActive = c("#BD93F9"), text = c("#F8F8F2"), textDim = c("#9CA0B0"),
    )

    val NORD = Theme(
        id = "nord", name = "Nord",
        background = c("#2E3440"), keyNormal = c("#3B4252"), keyNormalPressed = c("#4C566A"),
        keySpecial = c("#343B49"), keySpecialPressed = c("#434C5E"),
        modifierActive = c("#88C0D0"), text = c("#ECEFF4"), textDim = c("#9aa4b8"),
    )

    val OCEAN = Theme(
        id = "ocean", name = "Deep Ocean",
        background = c("#0B1E2D"), keyNormal = c("#13314A"), keyNormalPressed = c("#1E486B"),
        keySpecial = c("#0F2538"), keySpecialPressed = c("#1A3B59"),
        modifierActive = c("#28C2FF"), text = c("#E0F2FF"), textDim = c("#6E90A8"),
    )

    val FOREST = Theme(
        id = "forest", name = "Forest",
        background = c("#0F1F14"), keyNormal = c("#1B3322"), keyNormalPressed = c("#2A4D34"),
        keySpecial = c("#152A1B"), keySpecialPressed = c("#23402C"),
        modifierActive = c("#4CD787"), text = c("#E5F5E9"), textDim = c("#7A9684"),
    )

    val SUNSET = Theme(
        id = "sunset", name = "Sunset",
        background = c("#2B1216"), keyNormal = c("#46202A"), keyNormalPressed = c("#69313F"),
        keySpecial = c("#371920"), keySpecialPressed = c("#582935"),
        modifierActive = c("#FF6B6B"), text = c("#FFE8E8"), textDim = c("#B08A8F"),
    )

    val GRAPE = Theme(
        id = "grape", name = "Grape Soda",
        background = c("#1E1230"), keyNormal = c("#32204D"), keyNormalPressed = c("#4A3070"),
        keySpecial = c("#281A40"), keySpecialPressed = c("#3E295F"),
        modifierActive = c("#C77DFF"), text = c("#F3E9FF"), textDim = c("#9583B0"),
    )

    val CARBON = Theme(
        id = "carbon", name = "Carbon + Amber",
        background = c("#121212"), keyNormal = c("#1F1F1F"), keyNormalPressed = c("#333333"),
        keySpecial = c("#191919"), keySpecialPressed = c("#2B2B2B"),
        modifierActive = c("#FFB300"), text = c("#FFFFFF"), textDim = c("#888888"),
    )

    val PAPER = Theme(
        id = "paper", name = "Paper (Light)",
        background = c("#E4E6EB"), keyNormal = c("#FFFFFF"), keyNormalPressed = c("#D5D8DE"),
        keySpecial = c("#D9DCE2"), keySpecialPressed = c("#C3C7CF"),
        modifierActive = c("#3D7EFF"), text = c("#1B1C1F"), textDim = c("#6B6E76"),
        isLight = true,
    )

    val SAND = Theme(
        id = "sand", name = "Warm Sand (Light)",
        background = c("#E8E0D2"), keyNormal = c("#FBF7EF"), keyNormalPressed = c("#DDD3C0"),
        keySpecial = c("#DCD3C2"), keySpecialPressed = c("#C9BEA8"),
        modifierActive = c("#E07A3F"), text = c("#2A2620"), textDim = c("#7C7468"),
        isLight = true,
    )

    val all: List<Theme> = listOf(
        MIDNIGHT, SLATE, DRACULA, NORD, OCEAN, FOREST, SUNSET, GRAPE, CARBON, PAPER, SAND,
    )

    val default = MIDNIGHT

    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: default
}
