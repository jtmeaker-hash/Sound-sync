package com.example.scheduling

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class DeviceUsageState {
    ACTIVE,
    RECENTLY_ACTIVE,
    IDLE
}

object AdaptiveWorkScheduler {
    private const val ACTIVE_TIMEOUT_MS = 2500L
    private const val RECENTLY_ACTIVE_TIMEOUT_MS = 10000L

    private var lastInteractionTime = 0L

    private val _usageState = MutableStateFlow(DeviceUsageState.IDLE)
    val usageState: StateFlow<DeviceUsageState> = _usageState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                tick()
                delay(1000)
            }
        }
    }

    fun reportUserInteraction() {
        lastInteractionTime = SystemClock.elapsedRealtime()
        if (_usageState.value != DeviceUsageState.ACTIVE) {
            _usageState.value = DeviceUsageState.ACTIVE
        }
    }

    private fun tick() {
        if (lastInteractionTime == 0L) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastInteractionTime
        val newState = when {
            elapsed < ACTIVE_TIMEOUT_MS -> DeviceUsageState.ACTIVE
            elapsed < RECENTLY_ACTIVE_TIMEOUT_MS -> DeviceUsageState.RECENTLY_ACTIVE
            else -> DeviceUsageState.IDLE
        }
        if (_usageState.value != newState) {
            _usageState.value = newState
        }
    }
    
    suspend fun yieldIfActive() {
        val state = _usageState.value
        if (state == DeviceUsageState.ACTIVE) {
            delay(100)
        } else if (state == DeviceUsageState.RECENTLY_ACTIVE) {
            delay(20)
        }
    }
}
