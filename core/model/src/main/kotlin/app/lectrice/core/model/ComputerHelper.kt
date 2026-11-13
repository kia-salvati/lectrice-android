package app.lectrice.core.model

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A computer this phone paired with (file 24 §7): public data only. */
data class PairedComputer(
    /** First 16 hex digits of SHA-256(its key): how it is found again on the Wi-Fi (never by name or address). */
    val peerId: String,
    /** The name it gave while pairing. */
    val name: String,
    /** The person's own name for it on this phone ("Rename"), if any. */
    val label: String?,
    val lastAddress: String?,
    val pairedAtMs: Long,
    val lastSeenAtMs: Long?,
) {
    val shownName: String get() = label ?: name
}

/** What needs the person before the computer can help again (file 24 §3.9, §3.11). */
enum class HelperAttention {
    /** The computer forgot this phone: pair again. */
    NotPaired,
    /** The computer doesn't have this phone's voice model; it must download it. */
    ModelMissing,
    /** The two apps don't match: update the older one. */
    Incompatible,
    /** Android 17+: "Nearby devices" was denied. */
    PermissionDenied,
}

/** The link to the paired computer as the UI shows it (file 24 §4). */
sealed interface HelperLink {
    /** Switched off, nothing paired, or nobody is listening (the governor is Off). */
    data object Off : HelperLink

    /** Looking for the computer or setting up the session. */
    data object Searching : HelperLink

    data object Connected : HelperLink

    /** Connected, but the computer's own listener comes first for now. */
    data object Busy : HelperLink

    /** Not reachable; retrying while someone listens. */
    data object Lost : HelperLink

    data class NeedsAttention(val reason: HelperAttention) : HelperLink

    /** The computer makes this phone's audio now (the pill's mark). */
    val makesAudio: Boolean get() = this == Connected || this == Busy
}

data class HelperState(
    /** "Let a computer make the audio". */
    val enabled: Boolean = false,
    val computers: List<PairedComputer> = emptyList(),
    /** The computer the phone uses (the last one paired). */
    val active: PairedComputer? = null,
    val link: HelperLink = HelperLink.Off,
    /** The phone is on a Wi-Fi (or Ethernet) network: pairing needs one. */
    val onWifi: Boolean = false,
    /** Android 17+ only: the "Nearby devices" permission must be asked before discovery. */
    val needsPermission: Boolean = false,
)

/** A Lectrice computer seen on this Wi-Fi while pairing. */
data class FoundComputer(val peerId: String, val name: String, val address: String, val paired: Boolean)

sealed interface PairResult {
    data class Paired(val computer: PairedComputer) : PairResult

    /** `attempt` counts this phone's wrong codes for the code on screen (the computer allows 3, then the code is gone). */
    data class WrongCode(val attempt: Int) : PairResult

    /** No code is showing on the computer: never opened, expired, or closed after 3 wrong codes. */
    data object NoCode : PairResult

    /** The address isn't on the home network. */
    data object NotLocal : PairResult

    data object Incompatible : PairResult

    data object Unreachable : PairResult
}

/** One-off things the reader shows (a snackbar), never a dialog. */
sealed interface HelperNotice {
    /** File 24 §2: once per session, when the link goes while playing. */
    data object ComputerLost : HelperNotice
}

/** The computer helper (file 24) for the screens. */
interface ComputerHelperRepository {
    val state: StateFlow<HelperState>
    val notices: SharedFlow<HelperNotice>

    /** Computers on this Wi-Fi, live while [startDiscovery] runs (the pairing list). */
    val found: StateFlow<List<FoundComputer>>

    fun startDiscovery()

    fun stopDiscovery()

    /** `address` = "ip:port" (from the list or typed); `code` = the 6 digits. */
    suspend fun pair(address: String, code: String): PairResult

    suspend fun setEnabled(enabled: Boolean)

    suspend fun forget(peerId: String)

    suspend fun rename(peerId: String, label: String?)

    /** "Retry" / "Fix" after the person acted (downloaded the model on the computer, updated an app). */
    fun retry()

    /** The permission dialog closed (Android 17+). */
    fun permissionChanged()
}
