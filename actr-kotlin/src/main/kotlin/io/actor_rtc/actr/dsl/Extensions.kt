/** Utility functions and extensions for Actor-RTC SDK. */
package io.actor_rtc.actr.dsl

import io.actor_rtc.actr.ActrException
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.PayloadType

// ============================================================================
// ActrRef Call Extensions - Convenience wrappers with default parameters
// ============================================================================

/**
 * Call a remote actor via RPC with default PayloadType.RPC_RELIABLE and 30s timeout.
 *
 * Example:
 * ```kotlin
 * val response = ref.call(targetId, "echo.EchoService.Echo", requestPayload)
 * ```
 */
suspend fun ActrRef.call(
        target: ActrId,
        routeKey: String,
        requestPayload: ByteArray,
        payloadType: PayloadType = PayloadType.RPC_RELIABLE,
        timeoutMs: Long = 30000L
): ByteArray {
    return call(target, routeKey, payloadType, requestPayload, timeoutMs)
}

/**
 * Send a one-way message to an actor with default PayloadType.RPC_RELIABLE.
 *
 * Example:
 * ```kotlin
 * ref.tell(targetId, "echo.EchoService.Notify", messagePayload)
 * ```
 */
suspend fun ActrRef.tell(
        target: ActrId,
        routeKey: String,
        messagePayload: ByteArray,
        payloadType: PayloadType = PayloadType.RPC_RELIABLE
) {
    tell(target, routeKey, payloadType, messagePayload)
}

// ============================================================================
// Result Extensions - For functional error handling
// ============================================================================

/**
 * Execute an RPC call and wrap the result.
 *
 * Example:
 * ```kotlin
 * val result = ref.callCatching(target, "echo.EchoService.Echo", payload)
 * result.onSuccess { response ->
 *     println("Got response: $response")
 * }.onFailure { error ->
 *     println("Call failed: $error")
 * }
 * ```
 */
suspend fun ActrRef.callCatching(
        target: ActrId,
        routeKey: String,
        requestPayload: ByteArray,
        payloadType: PayloadType = PayloadType.RPC_RELIABLE,
        timeoutMs: Long = 30000L
): Result<ByteArray> {
    return runCatching { call(target, routeKey, requestPayload, payloadType, timeoutMs) }
}

/** Discover actors and wrap the result. */
suspend fun ActrRef.discoverCatching(typeString: String, count: UInt = 1u): Result<List<ActrId>> {
    return runCatching { discover(typeString, count) }
}

// ============================================================================
// Exception Extensions
// ============================================================================

/** Get a user-friendly error message. */
val ActrException.userMessage: String
    get() =
            when (this) {
                is ActrException.ConfigException -> "Configuration error: $msg"
                is ActrException.ConnectionException -> "Connection error: $msg"
                is ActrException.RpcException -> "RPC error: $msg"
                is ActrException.StateException -> "State error: $msg"
                is ActrException.InternalException -> "Internal error: $msg"
                is ActrException.TimeoutException -> "Timeout: $msg"
                is ActrException.WorkloadException -> "Workload error: $msg"
            }

/** Check if the exception is a timeout. */
val ActrException.isTimeout: Boolean
    get() = this is ActrException.TimeoutException

/** Check if the exception is a connection error. */
val ActrException.isConnectionError: Boolean
    get() = this is ActrException.ConnectionException

/** Check if the exception is recoverable (worth retrying). */
val ActrException.isRecoverable: Boolean
    get() =
            when (this) {
                is ActrException.TimeoutException -> true
                is ActrException.ConnectionException -> true
                is ActrException.RpcException -> false
                is ActrException.ConfigException -> false
                is ActrException.StateException -> false
                is ActrException.InternalException -> false
                is ActrException.WorkloadException -> false
            }

// ============================================================================
// Retry Utilities
// ============================================================================

/** Retry configuration for operations. */
data class RetryConfig(
        val maxAttempts: Int = 3,
        val initialDelayMs: Long = 1000,
        val maxDelayMs: Long = 10000,
        val factor: Double = 2.0
)

/**
 * Execute a suspending block with exponential backoff retry.
 *
 * Example:
 * ```kotlin
 * val result = withRetry(maxAttempts = 5) {
 *     ref.discover("acme:EchoService")
 * }
 * ```
 */
suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        shouldRetry: (Exception) -> Boolean = { it is ActrException && it.isRecoverable },
        block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    var lastException: Exception? = null

    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            if (attempt == maxAttempts - 1 || !shouldRetry(e)) {
                throw e
            }
            kotlinx.coroutines.delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }

    throw lastException ?: IllegalStateException("Retry failed without exception")
}

/** Execute a suspending block with retry using RetryConfig. */
suspend fun <T> withRetry(
        config: RetryConfig,
        shouldRetry: (Exception) -> Boolean = { it is ActrException && it.isRecoverable },
        block: suspend () -> T
): T =
        withRetry(
                maxAttempts = config.maxAttempts,
                initialDelayMs = config.initialDelayMs,
                maxDelayMs = config.maxDelayMs,
                factor = config.factor,
                shouldRetry = shouldRetry,
                block = block
        )

// ============================================================================
// Scoped Resource Management
// ============================================================================

/**
 * Execute a block with an ActrRef, ensuring proper cleanup.
 *
 * Example:
 * ```kotlin
 * system.withActor(workload) { ref ->
 *     val target = ref.discoverOne("acme:EchoService")
 *     ref.call(target, "echo.EchoService.Echo", payload)
 * }
 * // Actor is automatically shut down after the block
 * ```
 */
suspend fun <T> ActrSystem.withActor(workload: Workload, block: suspend (ActrRef) -> T): T {
    val node = attach(workload)
    val ref = node.start()
    return try {
        block(ref)
    } finally {
        try {
            ref.shutdown()
            ref.awaitShutdown()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }
}
