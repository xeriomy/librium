package com.librium.player

/**
 * Pure state machine guarding MPV native lifetime. The engine delegates
 * every transition here so the rules are unit-testable without native code:
 *
 * - exactly one instance: a second `initialize` while one is in flight or
 *   ready is refused;
 * - a failed init returns to new so a later retry is allowed;
 * - `release` runs exactly once; calls after release are refused;
 * - an init that finishes after a concurrent release must not publish.
 */
class EngineLifecycle {

    enum class State {
        NEW,
        INITIALIZING,
        READY,
        RELEASED,
    }

    private var state: State = State.NEW

    /** NEW -> INITIALIZING. False when init would create a duplicate. */
    @Synchronized
    fun tryBeginInit(): Boolean {
        if (state != State.NEW) return false
        state = State.INITIALIZING
        return true
    }

    /** INITIALIZING -> READY. No-op from any other state. */
    @Synchronized
    fun markReady() {
        if (state == State.INITIALIZING) state = State.READY
    }

    /** INITIALIZING -> NEW, allowing a later retry. */
    @Synchronized
    fun markInitFailed() {
        if (state == State.INITIALIZING) state = State.NEW
    }

    /**
     * NEW / INITIALIZING / READY -> RELEASED. True exactly once;
     * teardown must run only on true.
     */
    @Synchronized
    fun tryBeginRelease(): Boolean {
        if (state == State.RELEASED) return false
        state = State.RELEASED
        return true
    }

    @Synchronized
    fun current(): State = state

    @Synchronized
    fun isReleased(): Boolean = state == State.RELEASED
}
