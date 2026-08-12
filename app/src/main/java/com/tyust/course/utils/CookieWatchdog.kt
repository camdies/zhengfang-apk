package com.tyust.course.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.session.SessionProbe
import com.tyust.course.session.SessionProbeContext
import com.tyust.course.session.SessionProbeResult
import com.tyust.course.session.SessionRequestContext
import com.tyust.course.session.SessionRequestOwner
import com.tyust.course.session.SessionRequestPurpose
import com.tyust.course.session.SessionRegistry
import com.tyust.course.session.SessionResponseClassification
import com.tyust.course.session.SessionResponseClassifiers
import com.tyust.course.session.SessionResponseState
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局 Cookie 有效性定期检查器
 * 每隔固定间隔请求学生信息页，检测 Cookie 是否过期
 * 过期时发送 ACTION_COOKIE_EXPIRED 广播
 */
object CookieWatchdog {
    private const val TAG = "CookieWatchdog"
    private const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟
    private const val MAX_BACKOFF_INTERVAL_MS = 30 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private val nextWatchRunId = AtomicLong(0L)
    private var running = false
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var currentDelayMs = DEFAULT_INTERVAL_MS
    private var watchedSchool: SchoolConfig? = null
    private var watchedAccountStorageKey: String = ""
    private var watchedGeneration: Long = 0L
    private var watchedActiveContextEpoch: Long = 0L
    private var watchRunId: Long = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val userManager = UserManager.getInstance()
            val school = watchedSchool ?: userManager.currentSchool
            val requestAccountStorageKey = watchedAccountStorageKey.ifBlank { userManager.currentAccountStorageKey }
            if (!isWatchContextCurrent(requestAccountStorageKey, watchRunId) ||
                !isSessionContextCurrent(requestAccountStorageKey)
            ) {
                stopStaleWatch(watchRunId)
            } else if (school != null) {
                check(school, requestAccountStorageKey)
            } else {
                Log.w(TAG, "No school configured, skipping check")
                scheduleNext()
            }
        }
    }

    @JvmStatic
    fun start(@Suppress("UNUSED_PARAMETER") ctx: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (running) return
        this.intervalMs = intervalMs
        this.currentDelayMs = intervalMs
        val userManager = UserManager.getInstance()
        this.watchedSchool = userManager.currentSchool
        this.watchedAccountStorageKey = userManager.currentAccountStorageKey
        this.watchedGeneration = SessionRegistry.snapshot(watchedAccountStorageKey).generation
        this.watchedActiveContextEpoch = SessionRegistry.activeContextEpoch()
        this.watchRunId = nextWatchRunId.incrementAndGet()
        this.running = true
        Log.d(TAG, "Watchdog started, interval=${intervalMs}ms")
        // 首次检查延迟 30 秒（避免刚登录就检查）
        handler.postDelayed(checkRunnable, 30_000L)
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(checkRunnable)
        watchedSchool = null
        watchedAccountStorageKey = ""
        watchedGeneration = 0L
        watchedActiveContextEpoch = 0L
        watchRunId = 0L
        currentDelayMs = DEFAULT_INTERVAL_MS
        Log.d(TAG, "Watchdog stopped")
    }

    private fun check(school: SchoolConfig, requestAccountStorageKey: String) {
        val capturedWatchRunId = watchRunId
        val requestContext = SessionRequestContext.forSchool(
            school = school,
            accountStorageKey = requestAccountStorageKey,
            purpose = SessionRequestPurpose.WATCHDOG,
            owner = SessionRequestOwner.BACKGROUND,
            activeContextEpoch = null,
            serviceRunId = null
        )
        val capturedContext = SessionProbeContext(
            accountStorageKey = requestAccountStorageKey,
            sessionGeneration = requestContext.sessionGeneration,
            activeContextEpoch = watchedActiveContextEpoch
        )
        Log.d(TAG, "Checking cookie validity...")
        CourseApiClient.getInstance().validateCookie(school, requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Cookie probe did not complete: ${e.javaClass.simpleName}")
                handleProbeResult(
                    capturedWatchRunId,
                    capturedContext,
                    requestContext,
                    SessionProbeResult.INDETERMINATE,
                    null
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val classification = try {
                    val body = response.body?.string().orEmpty()
                    SessionResponseClassifiers.classifyConsumedBody(
                        requestContext,
                        response,
                        body
                    )
                } catch (e: Exception) {
                    // Treat a malformed or otherwise unclassifiable response
                    // as unknown; never publish an expiry from this path.
                    Log.w(TAG, "Cookie probe could not be classified: ${e.javaClass.simpleName}")
                    null
                } finally {
                    response.close()
                }
                handleProbeResult(
                    capturedWatchRunId,
                    capturedContext,
                    requestContext,
                    classification?.let(::toSessionProbeResult)
                        ?: SessionProbeResult.INDETERMINATE,
                    classification
                )
            }
        })
    }

    private fun handleProbeResult(
        capturedWatchRunId: Long,
        capturedContext: SessionProbeContext,
        requestContext: SessionRequestContext,
        result: SessionProbeResult,
        classification: SessionResponseClassification?
    ) {
        handler.post {
            if (!isWatchContextCurrent(capturedContext.accountStorageKey, capturedWatchRunId)
                || !SessionProbe.isCurrent(
                    context = capturedContext,
                    currentAccountStorageKey = watchedAccountStorageKey,
                    currentSessionGeneration = SessionRegistry.snapshot(capturedContext.accountStorageKey).generation,
                    currentActiveContextEpoch = SessionRegistry.activeContextEpoch()
                )
            ) {
                Log.d(TAG, "Discarding stale watchdog session probe")
                stopStaleWatch(capturedWatchRunId)
                return@post
            }

            when (result) {
                SessionProbeResult.VALID -> {
                    currentDelayMs = intervalMs
                    Log.d(TAG, "Cookie probe valid")
                    scheduleNext(currentDelayMs)
                }
                SessionProbeResult.CONFIRMED_EXPIRED -> {
                    Log.e(TAG, "Cookie expiry confirmed by session probe")
                    // This is the sole branch that may notify the unified
                    // expiry pipeline.  Unknown responses never broadcast.
                    if (classification != null) {
                        CourseApiClient.getInstance().reportSessionClassification(requestContext, classification)
                    }
                    stopStaleWatch(capturedWatchRunId)
                }
                SessionProbeResult.INDETERMINATE -> {
                    currentDelayMs = nextBackoffDelay(currentDelayMs, intervalMs)
                    Log.w(TAG, "Cookie probe indeterminate; retrying with bounded backoff")
                    scheduleNext(currentDelayMs)
                }
            }
        }
    }

    private fun toSessionProbeResult(
        classification: SessionResponseClassification
    ): SessionProbeResult = when (classification.state) {
        SessionResponseState.VALID -> SessionProbeResult.VALID
        SessionResponseState.CONFIRMED_EXPIRED -> SessionProbeResult.CONFIRMED_EXPIRED
        SessionResponseState.INDETERMINATE -> SessionProbeResult.INDETERMINATE
    }

    private fun isWatchContextCurrent(accountStorageKey: String, expectedWatchRunId: Long): Boolean {
        if (!running || expectedWatchRunId == 0L || expectedWatchRunId != watchRunId) return false
        val currentAccountStorageKey = UserManager.getInstance().currentAccountStorageKey
        return accountStorageKey == currentAccountStorageKey
    }

    private fun isSessionContextCurrent(accountStorageKey: String): Boolean = SessionProbe.isCurrent(
        context = SessionProbeContext(
            accountStorageKey = accountStorageKey,
            sessionGeneration = watchedGeneration,
            activeContextEpoch = watchedActiveContextEpoch
        ),
        currentAccountStorageKey = watchedAccountStorageKey,
        currentSessionGeneration = SessionRegistry.snapshot(accountStorageKey).generation,
        currentActiveContextEpoch = SessionRegistry.activeContextEpoch()
    )

    private fun stopStaleWatch(expectedWatchRunId: Long) {
        if (expectedWatchRunId != watchRunId) return
        running = false
        handler.removeCallbacks(checkRunnable)
        watchedSchool = null
        watchedAccountStorageKey = ""
        watchedGeneration = 0L
        watchedActiveContextEpoch = 0L
        watchRunId = 0L
    }

    private fun nextBackoffDelay(current: Long, base: Long): Long {
        val safeBase = base.coerceAtLeast(1L)
        val doubled = current.coerceAtLeast(safeBase).coerceAtMost(MAX_BACKOFF_INTERVAL_MS / 2L) * 2L
        return doubled.coerceAtMost(MAX_BACKOFF_INTERVAL_MS)
    }

    private fun scheduleNext(delayMs: Long = intervalMs) {
        if (running) {
            handler.postDelayed(checkRunnable, delayMs)
        }
    }

}
