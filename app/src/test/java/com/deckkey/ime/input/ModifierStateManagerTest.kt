package com.deckkey.ime.input

import com.deckkey.core.model.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierStateManagerTest {

    @Test
    fun testInitialState() {
        val manager = ModifierStateManager()
        assertFalse(manager.isActive(Modifier.SHIFT))
        assertEquals(ModState.OFF, manager.state(Modifier.SHIFT))
        assertFalse(manager.capsLock)
    }

    @Test
    fun testSingleTapLatching() {
        val manager = ModifierStateManager()
        val now = 1000L

        // Down transitions to HELD
        manager.onModifierDown(Modifier.CTRL, now)
        assertEquals(ModState.HELD, manager.state(Modifier.CTRL))

        // Up transitions to LATCHED
        manager.onModifierUp(Modifier.CTRL, now + 100L)
        assertEquals(ModState.LATCHED, manager.state(Modifier.CTRL))
        assertTrue(manager.isActive(Modifier.CTRL))

        // After key commit, it clears back to OFF
        manager.consumeAfterKey()
        assertEquals(ModState.OFF, manager.state(Modifier.CTRL))
    }

    @Test
    fun testDoubleTapLocking() {
        val manager = ModifierStateManager()
        var now = 1000L

        // First tap
        manager.onModifierDown(Modifier.SHIFT, now)
        now += 50
        manager.onModifierUp(Modifier.SHIFT, now)
        assertEquals(ModState.LATCHED, manager.state(Modifier.SHIFT))

        // Second tap within double-tap window (300ms)
        now += 150
        manager.onModifierDown(Modifier.SHIFT, now)
        now += 50
        manager.onModifierUp(Modifier.SHIFT, now)
        assertEquals(ModState.LOCKED, manager.state(Modifier.SHIFT))

        // After key commit, locked modifiers persist!
        manager.consumeAfterKey()
        assertEquals(ModState.LOCKED, manager.state(Modifier.SHIFT))

        // Tap again to release lock
        now += 1000
        manager.onModifierDown(Modifier.SHIFT, now)
        now += 50
        manager.onModifierUp(Modifier.SHIFT, now)
        assertEquals(ModState.OFF, manager.state(Modifier.SHIFT))
    }

    @Test
    fun testChording() {
        val manager = ModifierStateManager()
        val now = 1000L

        // Hold Ctrl
        manager.onModifierDown(Modifier.CTRL, now)
        assertEquals(ModState.HELD, manager.state(Modifier.CTRL))

        // Type a key while held
        manager.consumeAfterKey()
        assertEquals(ModState.HELD, manager.state(Modifier.CTRL))

        // Release Ctrl
        manager.onModifierUp(Modifier.CTRL, now + 500L)
        assertEquals(ModState.OFF, manager.state(Modifier.CTRL))
    }
}
