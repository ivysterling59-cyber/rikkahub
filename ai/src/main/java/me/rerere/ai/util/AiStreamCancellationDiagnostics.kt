package me.rerere.ai.util

import android.util.Log
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference

class AiStreamCancellationDiagnostics(
    private val provider: String,
    private val flowJob: Job?,
) {
    private val closeReason = AtomicReference("collector_cancelled_or_failed")

    fun mark(reason: String) {
        closeReason.set(reason)
    }

    fun logCleanup() {
        Log.i(
            "AiHttpDiag",
            "stage=flow_cleanup provider=$provider reason=${closeReason.get()} " +
                "coroutineActive=${flowJob?.isActive} coroutineCancelled=${flowJob?.isCancelled} " +
                "thread=${Thread.currentThread().name}",
        )
    }
}
