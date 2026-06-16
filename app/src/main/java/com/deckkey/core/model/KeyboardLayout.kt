package com.deckkey.core.model

/** One horizontal row of keys. */
data class Row(
    val keys: List<Key>,
    /** Relative height of this row (1.0 == standard). F-row can be shorter, e.g. 0.8. */
    val heightWeight: Float = 1f,
)

/**
 * A complete keyboard page/layout (e.g. "qwerty", "symbols", "terminal").
 */
data class KeyboardLayout(
    val id: String,
    val label: String,
    val rows: List<Row>,
) {
    /** Total relative height of all rows — used to compute pixel row heights. */
    val totalHeightWeight: Float get() = rows.sumOf { it.heightWeight.toDouble() }.toFloat()
}
