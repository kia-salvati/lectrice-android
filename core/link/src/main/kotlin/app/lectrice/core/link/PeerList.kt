package app.lectrice.core.link

import app.lectrice.core.model.PairedComputer

/** A paired computer with its pinned key (public data only). */
data class Peer(val computer: PairedComputer, val staticPublic: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Peer && other.computer == computer && other.staticPublic.contentEquals(staticPublic)

    override fun hashCode(): Int = 31 * computer.hashCode() + staticPublic.contentHashCode()
}

/**
 * The phone's paired computers and the helper switch (file 24 §7: `peers.json`). Pure: every change returns a new
 * list. At most [MAX_PEERS]; pairing a fifth drops the one seen longest ago. The newest pairing is the active one.
 */
data class PeerList(val enabled: Boolean = false, val activeId: String? = null, val peers: List<Peer> = emptyList()) {
    val active: Peer? get() = peers.firstOrNull { it.computer.peerId == activeId } ?: peers.firstOrNull()

    /** Adds or replaces (same id) a computer, turns the helper on and makes it the active one. */
    fun paired(peer: Peer): PeerList {
        val others = peers.filterNot { it.computer.peerId == peer.computer.peerId }
        val kept = if (others.size < MAX_PEERS) others else others.sortedByDescending { it.computer.lastSeenAtMs ?: it.computer.pairedAtMs }.take(MAX_PEERS - 1)
        return copy(enabled = true, activeId = peer.computer.peerId, peers = kept + peer)
    }

    /** Forgets a computer; with none left the helper is off. */
    fun forgotten(peerId: String): PeerList {
        val left = peers.filterNot { it.computer.peerId == peerId }
        return copy(enabled = enabled && left.isNotEmpty(), activeId = activeId.takeUnless { it == peerId }, peers = left)
    }

    fun renamed(peerId: String, label: String?): PeerList = update(peerId) { it.copy(label = label?.trim()?.take(MAX_LABEL)?.ifEmpty { null }) }

    /** The computer answered (or was found) at `address`. */
    fun seen(peerId: String, address: String?, nowMs: Long): PeerList = update(peerId) { it.copy(lastAddress = address ?: it.lastAddress, lastSeenAtMs = nowMs) }

    fun switched(enabled: Boolean): PeerList = copy(enabled = enabled && peers.isNotEmpty())

    private fun update(peerId: String, change: (PairedComputer) -> PairedComputer): PeerList =
        copy(peers = peers.map { if (it.computer.peerId == peerId) it.copy(computer = change(it.computer)) else it })

    companion object {
        const val MAX_PEERS = 4
        const val MAX_LABEL = 40
    }
}
