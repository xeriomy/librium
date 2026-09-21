package com.librium.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {

    @Test
    fun empty_history_has_nothing() {
        val history = UndoHistory<String>()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo("current"))
        assertNull(history.redo("current"))
    }

    @Test
    fun undo_redo_round_trip() {
        val history = UndoHistory<String>()
        history.push("a")
        history.push("b")
        assertTrue(history.canUndo)
        assertEquals("b", history.undo("c"))
        assertTrue(history.canRedo)
        assertEquals("c", history.redo("b"))
        assertFalse(history.canRedo)
    }

    @Test
    fun new_push_clears_redo() {
        val history = UndoHistory<String>()
        history.push("a")
        history.undo("b")
        assertTrue(history.canRedo)
        history.push("c")
        assertFalse(history.canRedo)
        assertEquals("c", history.undo("d"))
    }

    @Test
    fun capacity_evicts_oldest() {
        val history = UndoHistory<Int>(capacity = 3)
        history.push(1)
        history.push(2)
        history.push(3)
        history.push(4)
        assertEquals(3, history.undo(5))
        assertEquals(2, history.undo(3))
        assertEquals(1, history.undo(2))
        assertNull(history.undo(1))
    }

    @Test
    fun clear_resets_both_stacks() {
        val history = UndoHistory<String>()
        history.push("a")
        history.undo("b")
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
