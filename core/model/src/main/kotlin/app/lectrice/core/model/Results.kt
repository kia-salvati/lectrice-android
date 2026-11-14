package app.lectrice.core.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that calls suspend functions: a [CancellationException] is rethrown, so a cancelled scope
 * stops instead of carrying on as if the call had failed. Failures are logged where they happen (the core boundary).
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
