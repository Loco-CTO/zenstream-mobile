package com.zenstream.zenstreammobile.data

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.concurrent.atomic.AtomicBoolean

internal object AuthLifecycleLog {
    private const val TAG = "ZenStreamAuth"
    private val awaitingFirstProtectedRequest = AtomicBoolean(true)

    fun processCreated(pid: Int) {
        event("process_created", detail = "pid_$pid")
    }

    fun appResumed() {
        awaitingFirstProtectedRequest.set(true)
        event("app_resumed")
    }

    fun appBackgrounded() {
        event("app_backgrounded")
    }

    fun firstProtectedRequest(session: AuthSession) {
        if (awaitingFirstProtectedRequest.compareAndSet(true, false)) {
            event("first_protected_request", session)
        }
    }

    fun event(
        name: String,
        session: AuthSession? = null,
        attemptId: String? = session?.refreshAttemptId,
        statusCode: Int? = null,
        errorType: String? = null,
        detail: String? = null,
    ) {
        val host =
            session
                ?.serverUrl
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?.filter { it.isLetterOrDigit() || it in ".-:[]" }
                ?.take(120) ?: "unknown"
        val safeAttemptId = attemptId?.filter { it.isLetterOrDigit() || it == '-' }?.take(36)
        val fields = buildList {
            add("time_ms=${System.currentTimeMillis()}")
            add("elapsed_ms=${SystemClock.elapsedRealtime()}")
            add("event=${name.filter { it.isLetterOrDigit() || it == '_' }.take(48)}")
            add("server_host=$host")
            session?.accessExpiresAtMillis?.let { add("access_expires_at_ms=$it") }
            session?.refreshExpiresAtMillis?.let { add("refresh_expires_at_ms=$it") }
            session?.let {
                add("has_refresh_token=${!it.refreshToken.isNullOrBlank()}")
                it.accessExpiresAtMillis?.let { expiry ->
                    add("access_expiry_delta_ms=${expiry - System.currentTimeMillis()}")
                }
            }
            safeAttemptId?.let { add("attempt_id=$it") }
            statusCode?.let { add("status=$it") }
            errorType?.let { add("error_type=${it.filter(Char::isLetterOrDigit).take(48)}") }
            detail?.let {
                add("detail=${it.filter { c -> c.isLetterOrDigit() || c in "_-" }.take(64)}")
            }
        }
        Log.i(TAG, fields.joinToString(" "))
    }
}
