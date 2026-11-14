package app.lectrice.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.FoundComputer
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.PairResult
import app.lectrice.core.model.PairedComputer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the pairing flow is (file 24 §11.3, option A: one page per step, back goes one step back). */
sealed interface PairStep {
    /** H2: computers found on this Wi-Fi; [searchedLong] after 10 s (the help text and "Enter the address instead"). */
    data class List(val searchedLong: Boolean) : PairStep

    /** H4: typed address; [invalid] when it isn't "ip:port". */
    data class Address(val text: String, val invalid: Boolean) : PairStep

    /** H3. An empty [computerName] (typed address) reads "your computer". */
    data class Code(val computerName: String, val address: String, val phase: CodePhase) : PairStep

    /** H5. */
    data class Done(val computer: PairedComputer) : PairStep
}

sealed interface CodePhase {
    data object Typing : CodePhase
    data object Checking : CodePhase
    /** "That code didn't work. Check the code on your computer." (`attempt` of 3; boxes cleared). */
    data class Wrong(val attempt: Int) : CodePhase
    /** "No code is showing on …": the 3rd wrong code, expired, or never opened (security S4: no automatic new code). */
    data object NoCode : CodePhase
    /** Couldn't reach it (wrong address, firewall, another Wi-Fi). */
    data object Unreachable : CodePhase
    /** The address isn't on the home network. */
    data object NotLocal : CodePhase
    /** "Update Lectrice on the older device." */
    data object Incompatible : CodePhase
}

data class ComputerHelperUiState(
    val helper: HelperState = HelperState(),
    val found: List<FoundComputer> = emptyList(),
    /** Non-null while the pairing pages are shown. */
    val pair: PairStep? = null,
)

interface ComputerHelperActions {
    fun setEnabled(enabled: Boolean)
    fun forget(peerId: String)
    fun rename(peerId: String, label: String?)
    fun retry()
    /** "Pair with a computer" (H1) or "Pair again" (H9). */
    fun startPairing()
    /** H2 → H3. */
    fun pickComputer(computer: FoundComputer)
    /** H2 → H4. */
    fun enterAddress()
    fun addressChanged(text: String)
    /** H4 → H3. */
    fun submitAddress()
    /** Auto-submit on the 6th digit. */
    fun submitCode(code: String)
    /** H3 after an error: type again. */
    fun typeAgain()
    /** Back arrow inside the flow; `false` when the flow closed (the route then pops the page if needed). */
    fun pairBack(): Boolean
    /** H5 "Done". */
    fun finishPairing()
    /** The permission dialog closed (Android 17+). */
    fun permissionChanged()
}

class ComputerHelperViewModel(private val helper: ComputerHelperRepository) : ViewModel(), ComputerHelperActions {
    private val state = MutableStateFlow(ComputerHelperUiState())
    val uiState: StateFlow<ComputerHelperUiState> = state.asStateFlow()
    private var searchTimer: Job? = null

    init {
        viewModelScope.launch { helper.state.collect { s -> state.update { it.copy(helper = s) } } }
        viewModelScope.launch { helper.found.collect { f -> state.update { it.copy(found = f) } } }
    }

    override fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { helper.setEnabled(enabled) }
    }

    override fun forget(peerId: String) {
        viewModelScope.launch { helper.forget(peerId) }
    }

    override fun rename(peerId: String, label: String?) {
        viewModelScope.launch { helper.rename(peerId, label) }
    }

    override fun retry() = helper.retry()

    override fun startPairing() {
        helper.startDiscovery()
        state.update { it.copy(pair = PairStep.List(searchedLong = false)) }
        searchTimer?.cancel()
        searchTimer = viewModelScope.launch {
            delay(SEARCH_HELP_MS)
            state.update { s -> if (s.pair is PairStep.List) s.copy(pair = PairStep.List(searchedLong = true)) else s }
        }
    }

    override fun pickComputer(computer: FoundComputer) {
        state.update { it.copy(pair = PairStep.Code(computer.name, computer.address, CodePhase.Typing)) }
    }

    override fun enterAddress() {
        state.update { it.copy(pair = PairStep.Address("", invalid = false)) }
    }

    override fun addressChanged(text: String) {
        state.update { s -> (s.pair as? PairStep.Address)?.let { s.copy(pair = it.copy(text = text, invalid = false)) } ?: s }
    }

    override fun submitAddress() {
        val step = state.value.pair as? PairStep.Address ?: return
        val address = normalizedAddress(step.text)
        state.update { it.copy(pair = if (address == null) step.copy(invalid = true) else PairStep.Code("", address, CodePhase.Typing)) }
    }

    override fun submitCode(code: String) {
        val step = state.value.pair as? PairStep.Code ?: return
        if (step.phase == CodePhase.Checking || !CODE.matches(code)) return
        state.update { it.copy(pair = step.copy(phase = CodePhase.Checking)) }
        viewModelScope.launch {
            val next = when (val result = helper.pair(step.address, code)) {
                is PairResult.Paired -> PairStep.Done(result.computer)
                is PairResult.WrongCode -> step.copy(phase = if (result.attempt >= MAX_TRIES) CodePhase.NoCode else CodePhase.Wrong(result.attempt))
                PairResult.NoCode -> step.copy(phase = CodePhase.NoCode)
                PairResult.Unreachable -> step.copy(phase = CodePhase.Unreachable)
                PairResult.NotLocal -> step.copy(phase = CodePhase.NotLocal)
                PairResult.Incompatible -> step.copy(phase = CodePhase.Incompatible)
            }
            if (next is PairStep.Done) helper.stopDiscovery()
            state.update { it.copy(pair = next) }
        }
    }

    override fun typeAgain() {
        state.update { s -> (s.pair as? PairStep.Code)?.let { s.copy(pair = it.copy(phase = CodePhase.Typing)) } ?: s }
    }

    override fun pairBack(): Boolean {
        val next: PairStep? = when (state.value.pair) {
            is PairStep.Code, is PairStep.Address -> PairStep.List(searchedLong = true)
            else -> null
        }
        if (next == null) closePairing()
        state.update { it.copy(pair = next) }
        return next != null
    }

    override fun finishPairing() {
        closePairing()
        state.update { it.copy(pair = null) }
    }

    override fun permissionChanged() = helper.permissionChanged()

    private fun closePairing() {
        searchTimer?.cancel()
        helper.stopDiscovery()
    }

    override fun onCleared() {
        helper.stopDiscovery()
    }

    companion object {
        const val SEARCH_HELP_MS = 10_000L
        const val MAX_TRIES = 3
        const val DEFAULT_PORT = 47878
        private val CODE = Regex("\\d{6}")
        private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?")

        /** "192.168.1.20:47878", or the address alone with the default port; null when it isn't one. */
        fun normalizedAddress(text: String): String? {
            val match = IPV4.matchEntire(text.trim()) ?: return null
            val parts = match.groupValues.drop(1).take(4).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            val port = match.groupValues[5].ifEmpty { DEFAULT_PORT.toString() }.toInt()
            if (port !in 1..65535) return null
            return parts.joinToString(".") + ":" + port
        }
    }
}
