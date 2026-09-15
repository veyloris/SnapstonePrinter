package com.example.snapstoneprinter.data.print

import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun isCanonicalJobId(value: String): Boolean = try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}

data class DispatchToken(val jobId: String, val index: Int) {
    init {
        require(isCanonicalJobId(jobId)) { "Expected a canonical UUID job ID." }
        require(index >= 0) { "Slip index must be nonnegative." }
    }
}

data class LaunchRequest(val token: DispatchToken, val uri: String, val label: String, val total: Int)

sealed interface StartPrintResult {
    data class Started(val jobId: String) : StartPrintResult
    data object Busy : StartPrintResult
    data object Empty : StartPrintResult
}

sealed interface PrintJobState {
    data object Idle : PrintJobState
    data class Preparing(val jobId: String, val label: String, val total: Int) : PrintJobState

    sealed class Indexed protected constructor(
        val jobId: String,
        val label: String,
        uris: List<String>,
        val index: Int
    ) : PrintJobState {
        val uris: List<String> = Collections.unmodifiableList(ArrayList(uris))
        val total: Int get() = uris.size
        val token: DispatchToken get() = DispatchToken(jobId, index)

        init {
            require(isCanonicalJobId(jobId))
            require(this.uris.isNotEmpty() && this.uris.all { it.isNotBlank() })
            require(index in this.uris.indices)
        }
    }

    class Ready internal constructor(jobId: String, label: String, uris: List<String>, index: Int) : Indexed(jobId, label, uris, index)
    class Launched internal constructor(jobId: String, label: String, uris: List<String>, index: Int) : Indexed(jobId, label, uris, index)
    class AwaitingNext internal constructor(jobId: String, label: String, uris: List<String>, index: Int) : Indexed(jobId, label, uris, index)
    class Stopping internal constructor(jobId: String, label: String, uris: List<String>, index: Int) : Indexed(jobId, label, uris, index)
    data class Completed(val jobId: String) : PrintJobState
    data class Cancelled(val jobId: String) : PrintJobState
    data class Failed(val jobId: String, val message: String) : PrintJobState

    val isBusy: Boolean
        get() = this is Preparing || this is Ready || this is Launched || this is AwaitingNext || this is Stopping
}

/** Call synchronously on the main thread; PrintJobCoordinatorTest covers transition rejection. */
class PrintJobCoordinator(private val idFactory: () -> String = { UUID.randomUUID().toString() }) {
    private val mutableState = MutableStateFlow<PrintJobState>(PrintJobState.Idle)
    val state: StateFlow<PrintJobState> = mutableState.asStateFlow()
    private val issuedIds = mutableSetOf<String>()

    fun start(label: String, total: Int): StartPrintResult {
        require(total >= 0) { "Slip total must be nonnegative." }
        if (total == 0) return StartPrintResult.Empty
        if (state.value.isBusy) return StartPrintResult.Busy
        val jobId = idFactory()
        check(isCanonicalJobId(jobId) && jobId !in issuedIds) { "Expected a fresh canonical UUID job ID." }
        issuedIds += jobId
        mutableState.value = PrintJobState.Preparing(jobId, label, total)
        return StartPrintResult.Started(jobId)
    }

    fun exported(jobId: String, uris: List<String>): Boolean {
        val current = state.value as? PrintJobState.Preparing ?: return false
        if (current.jobId != jobId) return false
        val snapshot = uris.toList()
        mutableState.value = if (snapshot.size != current.total || snapshot.any { it.isBlank() }) {
            PrintJobState.Failed(jobId, "Could not prepare all slips for sharing.")
        } else PrintJobState.Ready(jobId, current.label, snapshot, 0)
        return true
    }

    fun exportFailed(jobId: String, message: String): Boolean {
        val current = state.value as? PrintJobState.Preparing ?: return false
        if (current.jobId != jobId) return false
        mutableState.value = PrintJobState.Failed(jobId, message.ifBlank { "Could not save images for sharing." })
        return true
    }

    fun claimLaunch(token: DispatchToken): LaunchRequest? {
        val current = state.value as? PrintJobState.Ready ?: return null
        if (current.token != token) return null
        mutableState.value = PrintJobState.Launched(current.jobId, current.label, current.uris, current.index)
        return LaunchRequest(token, current.uris[current.index], current.label, current.total)
    }

    fun launchFailed(token: DispatchToken, message: String): Boolean {
        val current = state.value as? PrintJobState.Indexed ?: return false
        if (current !is PrintJobState.Launched && current !is PrintJobState.Stopping) return false
        if (current.token != token) return false
        mutableState.value = PrintJobState.Failed(current.jobId, message.ifBlank { "No app could open this slip." })
        return true
    }

    fun returned(token: DispatchToken): Boolean {
        val current = state.value as? PrintJobState.Indexed ?: return false
        if (current.token != token) return false
        mutableState.value = when (current) {
            is PrintJobState.Stopping -> PrintJobState.Cancelled(current.jobId)
            is PrintJobState.Launched -> if (current.index == current.uris.lastIndex) {
                PrintJobState.Completed(current.jobId)
            } else PrintJobState.AwaitingNext(current.jobId, current.label, current.uris, current.index)
            else -> return false
        }
        return true
    }

    fun sendNext(jobId: String): Boolean {
        val current = state.value as? PrintJobState.AwaitingNext ?: return false
        if (current.jobId != jobId) return false
        mutableState.value = PrintJobState.Ready(jobId, current.label, current.uris, current.index + 1)
        return true
    }

    fun stop(jobId: String): Boolean {
        val current = state.value
        mutableState.value = when {
            current is PrintJobState.Preparing && current.jobId == jobId -> PrintJobState.Cancelled(jobId)
            current is PrintJobState.Indexed && current.jobId == jobId -> when (current) {
                is PrintJobState.Ready, is PrintJobState.AwaitingNext -> PrintJobState.Cancelled(jobId)
                is PrintJobState.Launched -> PrintJobState.Stopping(jobId, current.label, current.uris, current.index)
                else -> return false
            }
            else -> return false
        }
        return true
    }
}
