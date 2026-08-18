package com.kprint.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class MonitorStatus { STOPPED, STARTING, LISTENING, PRINTING, ERROR }

data class MonitorSnapshot(
    val status: MonitorStatus = MonitorStatus.STOPPED,
    val message: String = "Monitor parado",
    val printedThisSession: Int = 0,
    val updatedAt: String = Instant.now().toString(),
)

object MonitorRuntime {
    private val mutableState = MutableStateFlow(MonitorSnapshot())
    val state = mutableState.asStateFlow()

    fun update(status: MonitorStatus, message: String, printedThisSession: Int? = null) {
        val current = mutableState.value
        mutableState.value = current.copy(
            status = status,
            message = message,
            printedThisSession = printedThisSession ?: current.printedThisSession,
            updatedAt = Instant.now().toString(),
        )
    }
}
