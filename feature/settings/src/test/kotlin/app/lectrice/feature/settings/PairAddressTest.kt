package app.lectrice.feature.settings

import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.PairedComputer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairAddressTest {
    @Test
    fun typedAddressesGetTheDefaultPort() {
        assertEquals("192.168.1.20:47878", ComputerHelperViewModel.normalizedAddress(" 192.168.1.20 "))
        assertEquals("10.0.0.5:5000", ComputerHelperViewModel.normalizedAddress("10.0.0.5:5000"))
        assertNull(ComputerHelperViewModel.normalizedAddress("192.168.1.300"))
        assertNull(ComputerHelperViewModel.normalizedAddress("laptop.local"))
        assertNull(ComputerHelperViewModel.normalizedAddress("192.168.1.2:70000"))
    }

    @Test
    fun settingsRowSummary() {
        val laptop = PairedComputer("0123456789abcdef", "Laptop", null, null, 1, null)
        val on = HelperState(enabled = true, computers = listOf(laptop), active = laptop)
        assertEquals("Off", helperSummary(HelperState()))
        assertEquals("Off", helperSummary(on.copy(enabled = false)))
        assertEquals("Laptop · connected", helperSummary(on.copy(link = HelperLink.Connected)))
        assertEquals("Laptop · not reachable", helperSummary(on.copy(link = HelperLink.Lost)))
        assertEquals("Laptop", helperSummary(on))
    }
}
