package app.lectrice.core.model

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsTest {
    @Test
    fun `failures become results and cancellation is rethrown`() {
        assertEquals(2, suspendRunCatching { 1 + 1 }.getOrNull())
        assertTrue(suspendRunCatching { throw LectriceError(ErrorName.Internal) }.isFailure)
        val rethrown = runCatching { suspendRunCatching { throw CancellationException("stop") } }.exceptionOrNull()
        assertTrue(rethrown is CancellationException)
    }
}
