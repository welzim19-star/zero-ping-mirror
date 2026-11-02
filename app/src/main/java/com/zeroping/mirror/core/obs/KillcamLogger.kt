package com.zeroping.mirror.core.obs

import android.util.Log
import java.util.UUID

object KillcamLogger {
    private const val TAG = "ZeroPingKillcam"

    fun logError(context: String, error: Throwable) {
        val correlationId = UUID.randomUUID().toString()
        val stackTrace = error.stackTraceToString()
        val lastFile = error.stackTrace.firstOrNull()?.fileName ?: "UNKNOWN"
        val lastLine = error.stackTrace.firstOrNull()?.lineNumber ?: -1
        
        Log.e(TAG, "==== KILLCAM TRIGGERED ====")
        Log.e(TAG, "Correlation ID: [$correlationId]")
        Log.e(TAG, "Context: $context")
        Log.e(TAG, "Exception: ${error.message}")
        Log.e(TAG, "Last File Access: $lastFile @ Line $lastLine")
        Log.e(TAG, "Trace:\n$stackTrace")
        Log.e(TAG, "===========================")
    }

    fun logInfo(context: String, message: String) {
        Log.i(TAG, "[$context] $message")
    }

    fun logWarning(context: String, message: String) {
        Log.w(TAG, "[$context] WARN: $message")
    }
}
