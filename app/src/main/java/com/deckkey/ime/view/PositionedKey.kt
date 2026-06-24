package com.deckkey.ime.view

import android.graphics.RectF
import com.deckkey.core.model.Key

/** A key resolved to absolute pixel bounds for hit-testing and drawing. */
data class PositionedKey(
    val key: Key,
    val bounds: RectF,
    val helperLabel: String? = null,
)
