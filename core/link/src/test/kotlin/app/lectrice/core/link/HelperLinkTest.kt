package app.lectrice.core.link

import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.PairedComputer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperLinkTest {
    private fun peer(id: Char, seenAt: Long?) = Peer(PairedComputer(id.toString().repeat(16), "Computer $id", null, null, pairedAtMs = 1, lastSeenAtMs = seenAt), ByteArray(32) { id.code.toByte() })

    @Test
    fun pairingTurnsTheHelperOnAndKeepsFourComputers() {
        var list = PeerList()
        assertFalse(list.enabled)
        for ((index, id) in "abcd".withIndex()) list = list.paired(peer(id, seenAt = 10L + index))
        assertTrue(list.enabled)
        assertEquals("d".repeat(16), list.active?.computer?.peerId)

        list = list.paired(peer('e', seenAt = 100))

        assertEquals(PeerList.MAX_PEERS, list.peers.size)
        assertFalse("the computer seen longest ago goes", list.peers.any { it.computer.peerId == "a".repeat(16) })
        assertEquals("e".repeat(16), list.activeId)
    }

    @Test
    fun pairingAgainReplacesTheKey() {
        val first = peer('a', 1)
        val again = first.copy(staticPublic = ByteArray(32) { 9 })

        val list = PeerList().paired(first).paired(again)

        assertEquals(listOf(again), list.peers)
    }

    @Test
    fun forgettingTheLastComputerTurnsTheHelperOff() {
        val list = PeerList().paired(peer('a', 1)).paired(peer('b', 2))

        val one = list.forgotten("b".repeat(16))
        assertTrue(one.enabled)
        assertEquals("a".repeat(16), one.active?.computer?.peerId)
        val none = one.forgotten("a".repeat(16))
        assertFalse(none.enabled)
        assertNull(none.active)
        assertFalse("can't turn on without a computer", none.switched(true).enabled)
    }

    @Test
    fun labelsAreTrimmedAndCapped() {
        val list = PeerList().paired(peer('a', 1))

        assertEquals("Desk", list.renamed("a".repeat(16), "  Desk ").active?.computer?.shownName)
        assertEquals("Computer a", list.renamed("a".repeat(16), "   ").active?.computer?.shownName)
        assertEquals(PeerList.MAX_LABEL, list.renamed("a".repeat(16), "x".repeat(99)).active?.computer?.label?.length)
    }

    @Test
    fun linkStatesForTheScreens() {
        val core = HelperLinkState.Core.Connected
        assertEquals(HelperLink.Off, HelperLinkState.of(core, running = false, permissionDenied = false))
        assertEquals(HelperLink.Connected, HelperLinkState.of(core, running = true, permissionDenied = false))
        assertEquals(HelperLink.Searching, HelperLinkState.of(HelperLinkState.Core.Connecting, running = true, permissionDenied = false))
        assertEquals(HelperLink.Searching, HelperLinkState.of(HelperLinkState.Core.Off, running = true, permissionDenied = false))
        assertEquals(HelperLink.Lost, HelperLinkState.of(HelperLinkState.Core.Lost, running = true, permissionDenied = false))
        assertEquals(HelperLink.NeedsAttention(HelperAttention.NotPaired), HelperLinkState.of(HelperLinkState.Core.NeedsAttention("HelperNotPaired"), true, false))
        assertEquals(HelperLink.NeedsAttention(HelperAttention.ModelMissing), HelperLinkState.of(HelperLinkState.Core.NeedsAttention("HelperModelMissing"), true, false))
        assertEquals(HelperLink.NeedsAttention(HelperAttention.Incompatible), HelperLinkState.of(HelperLinkState.Core.NeedsAttention("HelperIncompatible"), true, false))
        assertEquals(HelperLink.NeedsAttention(HelperAttention.PermissionDenied), HelperLinkState.of(core, running = false, permissionDenied = true))
    }

    @Test
    fun theTakeOverStartsOnlyWhenTheComputerWasMakingAudio() {
        assertTrue(HelperLinkState.lostNow(HelperLink.Connected, HelperLink.Lost))
        assertTrue(HelperLinkState.lostNow(HelperLink.Busy, HelperLink.Lost))
        assertTrue(HelperLinkState.lostNow(HelperLink.Connected, HelperLink.NeedsAttention(HelperAttention.ModelMissing)))
        assertFalse("stopping on purpose is no loss", HelperLinkState.lostNow(HelperLink.Connected, HelperLink.Off))
        assertFalse(HelperLinkState.lostNow(HelperLink.Searching, HelperLink.Lost))
        assertFalse(HelperLinkState.lostNow(HelperLink.Connected, HelperLink.Busy))
    }
}
