# DeckKey - Bug Fixes

## FIX #1: KeyRepeatController Memory Leak

File: app/src/main/java/com/deckkey/ime/input/KeyRepeatController.kt

Use Coroutines instead of Handler for proper lifecycle management.

## FIX #2: ModifierStateManager HashMap Initialization

File: app/src/main/java/com/deckkey/ime/input/ModifierStateManager.kt

Initialize all HashMaps in init block for all Modifier.values():

```
init {
    Modifier.values().forEach { m ->
        lastTapAt[m] = 0L
        pressOriginState[m] = ModState.OFF
        consumedSinceDown[m] = false
    }
}
```

## FIX #3: KeyboardView Touch Slop Logic

File: app/src/main/java/com/deckkey/ime/view/KeyboardView.kt

handleMove() - only trigger slide if hitTest actually found a new key nearby.
Cancel pointer if moved too far away without landing on a key.

## FIX #4: DeckKeyService Null Safety

File: app/src/main/java/com/deckkey/ime/service/DeckKeyService.kt

Check keyboardView != null before applying settings.
Replace catch(_: Exception) with specific exceptions and logging.

## FIX #5: LayoutManager Thread Safety

File: app/src/main/java/com/deckkey/ime/layout/LayoutManager.kt

Wrap cache access with synchronized(lock).

## FIX #6: KeyDispatcher Character Output

File: app/src/main/java/com/deckkey/ime/input/KeyDispatcher.kt

Validate baseOutput.isNotEmpty() before using firstOrNull().

## FIX #7: Preview Popup Cleanup

File: app/src/main/java/com/deckkey/ime/view/KeyboardView.kt

Always dismiss preview before showing new one during move.

## FIX #8: Bitmap Scaling Optimization

File: app/src/main/java/com/deckkey/ime/view/KeyboardView.kt

Cache bitmap scaling calculation in onSizeChanged(), reuse in onDraw().

