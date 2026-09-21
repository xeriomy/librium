package com.librium.subtitle

/**
 * Bounded undo/redo over immutable snapshots. The editor pushes the
 * pre-change document; undo returns to it while stashing the current one
 * for redo. Pushing a new state clears the redo stack. Pure and
 * unit-tested; the ViewModel owns one instance.
 */
class UndoHistory<T>(private val capacity: Int = 25) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(state: T) {
        undoStack.addLast(state)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Returns the state to restore, or null when there is nothing to undo. */
    fun undo(current: T): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        return undoStack.removeLast()
    }

    /** Returns the state to restore, or null when there is nothing to redo. */
    fun redo(current: T): T? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        return redoStack.removeLast()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
