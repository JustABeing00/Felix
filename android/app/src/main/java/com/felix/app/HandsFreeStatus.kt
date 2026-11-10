package com.felix.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide hands-free status. The service publishes it and the Activity listens.
 * Listeners always run on the main thread.
 */
object HandsFreeStatus {

    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var current: HandsFreeUiState = HandsFreeUiState.INACTIVE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(HandsFreeUiState) -> Unit>()

    fun publish(phase: HandsFreePhase, message: String) {
        val state = HandsFreeUiState(phase, message)
        current = state
        main.post { listeners.forEach { it(state) } }
    }

    fun addListener(listener: (HandsFreeUiState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HandsFreeUiState) -> Unit) {
        listeners.remove(listener)
    }
}
