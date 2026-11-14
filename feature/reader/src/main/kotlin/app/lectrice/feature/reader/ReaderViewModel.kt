package app.lectrice.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.Document
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.Pages
import app.lectrice.core.model.PlaceLabels
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.HelperNotice
import app.lectrice.core.model.PrepareController
import app.lectrice.core.model.PrepareRequest
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.PrepareState
import app.lectrice.core.model.VoiceModelRepository
import app.lectrice.core.model.PlaybackOrder
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.Progress
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.Toc
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceAvailability
import app.lectrice.core.model.VoiceRepository
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.VoiceState
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

interface ReaderNavigation {
    fun backToLibrary(message: ReaderMessage? = null)
    fun openModelSetup()
    fun openComputerHelper()
}

/** Opened book + what the player shows about it (files 04, 05, 06, 07, 13). */
class ReaderViewModel(
    private val documentId: String,
    private val playOnOpen: Boolean,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val settings: SettingsStore,
    private val voices: VoiceRepository,
    private val voiceSamples: VoiceSamplePlayer,
    private val voice: VoiceAvailability,
    private val navigation: ReaderNavigation,
    private val prepares: PrepareController,
    private val models: VoiceModelRepository,
    private val audiobooks: AudiobookRepository,
    private val helper: ComputerHelperRepository? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ViewModel(), ReaderActions {
    private val state = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = state.asStateFlow()

    private var document: Document? = null
    private var order: PlaybackOrder? = null
    private var pageLabels: Map<Int, String> = emptyMap()
    private var chapterStarts: List<Int> = emptyList()

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch { voiceSamples.state.collect { sample -> state.update { it.copy(voiceSample = sample) } } }
        viewModelScope.launch {
            settings.settings.collect { s -> state.update { it.copy(textSize = s.textSize, pillHintSeen = s.pillHintSeen, listenHintSeen = s.listenHintSeen) } }
        }
        viewModelScope.launch {
            voice.state.collect { v -> state.update { it.copy(voiceMissing = v == VoiceState.NotInstalled || v is VoiceState.Downloading) } }
        }
    }

    private suspend fun open() {
        try {
            val summary = library.summary(documentId)
            if (summary.cleanerVersion < library.currentCleanerVersion()) state.update { it.copy(content = ReaderContent.Recleaning) }
            val opened = library.getDocument(documentId, markOpened = true)
            val readSkipped = settings.settings.value.readSkipped
            val ids = library.playbackOrder(documentId, readSkipped)
            val prepared = withContext(Dispatchers.Default) {
                val labels = Pages.labelLookup(opened.pages)
                Prepared(PlaybackOrder(ids, opened), labels, ReaderModel.blocks(opened, labels), Toc.chapterNodeIndexes(opened.toc).map { opened.toc[it].sentenceId })
            }
            document = opened
            order = prepared.order
            pageLabels = prepared.labels
            chapterStarts = prepared.chapterStarts
            val start = prepared.order.startingSentenceId(opened.lastSentenceId)
            state.update { it.copy(content = ReaderContent.Ready(opened.title, prepared.blocks, ReaderModel.outline(opened, prepared.labels, start))) }
            if (playback.state.value.documentId != documentId) playback.open(opened, start, playOnOpen) else if (playOnOpen) playback.play()
            loadVoices()
            observePlayback()
            publishPrepare()
        } catch (error: LectriceError) {
            if (error.name == ErrorName.DocumentNotFound) navigation.backToLibrary(ReaderMessage.BookGone)
            else state.update { it.copy(content = ReaderContent.Failed(error.name)) }
        }
    }

    private class Prepared(val order: PlaybackOrder, val labels: Map<Int, String>, val blocks: List<ReaderBlock>, val chapterStarts: List<Int>)

    private suspend fun loadVoices() {
        val list = suspendRunCatching { voices.listVoices() }.getOrDefault(emptyList())
        state.update { it.copy(voices = list) }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            combine(playback.state, voice.state) { playing, voiceState -> playing to voiceState }
                .distinctUntilChanged()
                .collect { (playing, voiceState) -> onPlayback(playing, voiceState) }
        }
        viewModelScope.launch {
            prepares.states.collect { publishPrepare() }
        }
        helper?.let { repository ->
            viewModelScope.launch {
                combine(repository.state, playback.state) { h, p -> h to p }.distinctUntilChanged().collect { (h, p) ->
                    state.update { it.copy(helper = HelperLines.of(h, p)) }
                }
            }
            viewModelScope.launch { repository.state.map { it.link.makesAudio to it.active?.shownName }.distinctUntilChanged().collect { publishPrepare() } }
            viewModelScope.launch { repository.notices.collect { if (it == HelperNotice.ComputerLost) state.update { s -> s.copy(message = ReaderMessage.ComputerLost) } } }
        }
        viewModelScope.launch {
            audiobooks.links.map { it[documentId] }.distinctUntilChanged().collect { onPlayback(playback.state.value, voice.state.value) }
        }
        viewModelScope.launch {
            playback.state.map { it.source }.distinctUntilChanged().collect { publishPrepare() }
        }
        viewModelScope.launch {
            playback.state.map { it.readSkipped }.distinctUntilChanged().collect { readSkipped ->
                val opened = document ?: return@collect
                val ids = suspendRunCatching { library.playbackOrder(documentId, readSkipped) }.getOrNull() ?: return@collect
                order = withContext(Dispatchers.Default) { PlaybackOrder(ids, opened) }
                onPlayback(playback.state.value, voice.state.value)
            }
        }
    }

    private fun onPlayback(playing: PlaybackState, voiceState: VoiceState) {
        val opened = document ?: return
        val currentOrder = order ?: return
        if (playing.documentId != documentId) return
        val sentenceId = playing.currentSentenceId ?: return
        val rate = playing.speed.rate
        val labels = PlaceLabels.of(opened, currentOrder, sentenceId, rate, pageLabels)
        val blockIndex = currentOrder.blockIndexOf(sentenceId)
        val bookPercent = Progress.percentRead(sentenceId, opened.sentenceIdEnd) ?: 0
        val hasToc = opened.toc.isNotEmpty()
        val sleepShort = sleepShort(playing.sleep)
        val skipped = playing.skippedCount > lastSkipped
        lastSkipped = playing.skippedCount
        val player = PlayerInfo(
            status = playing.status,
            error = playing.error,
            isPlaying = playing.isPlaying,
            location = labels.pillLocation(hasToc),
            locationSpoken = spokenLocation(labels, hasToc),
            speed = playing.speed,
            sleep = playing.sleep,
            sleepShort = sleepShort,
            sleepSummary = sleepSummary(playing.sleep),
            bookPercent = bookPercent,
            bookTimeLeft = Progress.formatTimeLeft(currentOrder.secondsLeft(sentenceId, rate)),
            bookFraction = fraction(sentenceId, opened),
            bufferedFraction = bufferedFraction(playing, opened, currentOrder),
            chapterTicks = chapterStarts.takeIf { it.size <= MAX_TICKS }.orEmpty().map { fraction(it, opened) },
            sliderCaption = listOfNotNull(labels.chapter?.number?.let { "Ch. $it" }, "$bookPercent %").joinToString(" · "),
            chapterLineTitle = labels.chapterLineTitle(),
            chapterLineDetail = labels.chapterLineDetail(),
            hasToc = hasToc,
            canPreviousSection = hasToc && Toc.previousSectionStart(opened.toc, sentenceId, currentOrder::resolvePlayable) != null,
            canNextSection = hasToc && Toc.nextSectionStart(opened.toc, sentenceId) != null,
            voice = state.value.voices.firstOrNull { it.id == playing.voiceId },
            readSkipped = playing.readSkipped,
            audiobook = audiobookUi(playing),
        )
        state.update {
            it.copy(
                currentSentenceId = sentenceId,
                currentBlockIndex = blockIndex,
                player = player,
                listen = listenInfo(opened, currentOrder, sentenceId, labels, bookPercent, rate),
                banner = banner(it.banner, playing, voiceState),
                message = if (skipped) ReaderMessage.SentenceSkipped(ErrorName.SpeechSynthesisFailed) else it.message,
                content = (it.content as? ReaderContent.Ready)?.let { ready -> ready.copy(outline = ReaderModel.outline(opened, pageLabels, sentenceId)) } ?: it.content,
            )
        }
    }

    private var lastSkipped = 0

    private fun banner(current: ReaderBanner?, playing: PlaybackState, voiceState: VoiceState): ReaderBanner? {
        if (voiceState == VoiceState.Failed || playing.status == PlaybackStatus.VoiceFailed) return ReaderBanner.VoiceFailed
        if (current is ReaderBanner.SlowPhone) return current
        val rtf = playing.rtf ?: return current.takeUnless { it == ReaderBanner.VoiceFailed }
        val slow = playing.status == PlaybackStatus.CatchingUp && rtf * playing.speed.rate > SLOW_LIMIT && !settings.isSlowTipShown(documentId)
        if (!slow) return current.takeUnless { it == ReaderBanner.VoiceFailed }
        val best = PlaybackSpeed.entries.lastOrNull { rtf * it.rate <= SMOOTH_LIMIT }
        return ReaderBanner.SlowPhone(playing.speed, best, canPrepare = true)
    }

    private fun publishPrepare() {
        val computer = helper?.state?.value?.takeIf { it.link.makesAudio }?.active?.shownName
        if (!models.isSlowPhone() && computer == null) {
            state.update { it.copy(prepare = null) }
            return
        }
        // File 23 §3.4: nothing to prepare while the book plays from its audiobook file.
        if (playback.state.value.source == AudioSource.Audiobook) {
            state.update { it.copy(prepare = null) }
            return
        }
        val info = when (val prepared = prepares.states.value[documentId]) {
            null -> PrepareInfo("Off", active = false, voiceWarning = null)
            PrepareState.WaitingForCharger -> PrepareInfo("Waits for the charger", active = true, voiceWarning = null)
            is PrepareState.Running -> PrepareInfo(
                if (prepared.onComputer && computer != null) "Preparing on $computer · ${prepared.percent} %" else "Preparing · ${prepared.percent} %",
                active = true,
                voiceWarning = null,
            )
            is PrepareState.Ready -> PrepareInfo("Ready up to ${prepared.upToLabel}", active = true, voiceWarning = null)
        }
        state.update { it.copy(prepare = info.copy(onComputer = computer)) }
    }

    override fun prepare(scope: PrepareScope) {
        val opened = document ?: return
        val currentOrder = order ?: return
        val current = playback.state.value.currentSentenceId ?: return
        val chapterIndexes = Toc.chapterNodeIndexes(opened.toc)
        val nextChapters = chapterIndexes.map { opened.toc[it] }.filter { it.sentenceId > current }
        val (end, label) = when (scope) {
            PrepareScope.ThisChapter -> (nextChapters.firstOrNull()?.sentenceId?.minus(1) ?: (opened.sentenceIdEnd - 1)) to chapterLabelBefore(opened, nextChapters.firstOrNull()?.sentenceId)
            PrepareScope.NextThreeChapters -> (nextChapters.getOrNull(NEXT_CHAPTERS)?.sentenceId?.minus(1) ?: (opened.sentenceIdEnd - 1)) to chapterLabelBefore(opened, nextChapters.getOrNull(NEXT_CHAPTERS)?.sentenceId)
            PrepareScope.AsMuchAsFits -> {
                var last = current
                var cursor: Int? = current
                while (cursor != null && currentOrder.secondsBetween(current, cursor, 1f) < MAX_PREPARE_SECONDS) {
                    last = cursor
                    cursor = currentOrder.stepFrom(cursor, 1)
                }
                last to chapterLabelBefore(opened, last + 1)
            }
        }
        prepares.prepare(PrepareRequest(documentId, playback.state.value.voiceId, playback.state.value.readSkipped, current, end, label, clockMs()))
        state.update { it.copy(message = ReaderMessage.PrepareQueued) }
    }

    /** "Ch. 9" (the chapter that ends right before [nextStart]), else "the end". */
    private fun chapterLabelBefore(opened: Document, nextStart: Int?): String {
        val last = (nextStart ?: opened.sentenceIdEnd) - 1
        val location = Toc.location(opened.toc, last) ?: return "the end"
        val label = Toc.chapterLabel(opened.toc[location.chapterIndex].title)
        return label.number?.let { "Ch. $it" } ?: label.name
    }

    override fun cancelPrepare() = prepares.cancel(documentId)

    // ---- Audiobook source (file 23) ----------------------------------------------------------------------------------

    private fun audiobookUi(playing: PlaybackState): AudiobookUi? {
        val link = audiobooks.links.value[documentId] ?: return null
        val active = playing.source == AudioSource.Audiobook
        val made = if (link.fileSpeedPercent != PlaybackSpeed.X1.percent) "made at ${PlaybackSpeed.fromPercent(link.fileSpeedPercent).label}" else null
        val origin = if (link.kind == AudiobookKind.OwnExport) "from this phone" else "imported"
        val voiceName = link.voiceId?.let { id -> state.value.voices.firstOrNull { it.id == id }?.name }
        val follows = when (link.mode) {
            AudiobookMode.Sentences -> "Text follows each sentence"
            AudiobookMode.Chapters -> "Made from another version of this book · text follows chapters"
            AudiobookMode.Unaligned -> "Can't follow the text"
        }
        return AudiobookUi(
            active = active,
            fileName = link.displayName,
            detail = listOfNotNull(Progress.formatDuration(link.durationMs / MS_PER_SECOND.toDouble()), voiceName, made, origin).joinToString(" · "),
            follows = follows,
            missing = active && playing.audiobookMissing,
        )
    }

    override fun setAudioSource(source: AudioSource) {
        if (source == AudioSource.Audiobook && audiobooks.links.value[documentId]?.mode != AudiobookMode.Sentences) {
            state.update { it.copy(message = ReaderMessage.AudiobookFailed(ErrorName.AudiobookNotLectrice)) }
            return
        }
        playback.setAudioSource(source)
    }

    override fun linkAudiobook(uri: String) {
        viewModelScope.launch {
            try {
                audiobooks.link(documentId, uri, AudiobookKind.Imported)
                playback.setAudioSource(AudioSource.Audiobook)
            } catch (error: LectriceError) {
                state.update { it.copy(message = ReaderMessage.AudiobookFailed(error.name)) }
            }
        }
    }

    override fun removeAudiobookLink() {
        viewModelScope.launch {
            if (playback.state.value.source == AudioSource.Audiobook) playback.setAudioSource(AudioSource.Generated)
            audiobooks.unlink(documentId)
        }
    }

    private fun listenInfo(opened: Document, currentOrder: PlaybackOrder, sentenceId: Int, labels: PlaceLabels, bookPercent: Int, rate: Float): ListenInfo {
        val blockIndex = currentOrder.blockIndexOf(sentenceId)
        val block = blockIndex?.let(opened.blocks::get)
        val location = Toc.location(opened.toc, sentenceId)
        val chapterStart = location?.let { opened.toc[it.chapterIndex].sentenceId }
        val chapterEnd = location?.let { Toc.entryEndSentenceId(opened.toc, it.chapterIndex) } ?: opened.sentenceIdEnd
        val sectionTicks = if (chapterStart != null) {
            opened.toc.filter { it.sentenceId in (chapterStart + 1) until chapterEnd }.map { (it.sentenceId - chapterStart).toFloat() / (chapterEnd - chapterStart).coerceAtLeast(1) }
        } else {
            emptyList()
        }
        val left = if (location != null) currentOrder.secondsBetween(sentenceId, Toc.entryEndSentenceId(opened.toc, location.chapterIndex), rate) else currentOrder.secondsLeft(sentenceId, rate)
        return ListenInfo(
            bookTitle = opened.title,
            title = labels.listenTitle(),
            previous = currentOrder.stepFrom(sentenceId, -1)?.let { currentOrder.sentence(it)?.text },
            current = currentOrder.sentence(sentenceId)?.text.orEmpty(),
            next = currentOrder.stepFrom(sentenceId, 1)?.let { currentOrder.sentence(it)?.text },
            currentBlockKind = block?.kind ?: BlockKind.Paragraph,
            currentBoxText = block?.takeIf { it.kind == BlockKind.Code || it.kind == BlockKind.Table }?.let { it.text ?: it.sentences.joinToString(" ") { s -> s.text } },
            progress = labels.listenProgress(bookPercent),
            timeLeft = Progress.formatTimeLeft(left),
            chapterFraction = (labels.chapterPercent ?: bookPercent) / PERCENT,
            sectionTicks = sectionTicks,
        )
    }

    private fun fraction(sentenceId: Int, opened: Document): Float = sentenceId.toFloat() / (opened.sentenceIdEnd - 1).coerceAtLeast(1)

    private fun bufferedFraction(playing: PlaybackState, opened: Document, currentOrder: PlaybackOrder): Float {
        val sentenceId = playing.currentSentenceId ?: return 0f
        if (playing.readyAheadMs <= 0) return 0f
        var seconds = playing.readyAheadMs / MS_PER_SECOND.toDouble()
        var cursor = sentenceId
        while (seconds > 0) {
            val next = currentOrder.stepFrom(cursor, 1) ?: break
            seconds -= (currentOrder.sentence(next)?.text?.length ?: 0) / Progress.CHARS_PER_SECOND
            cursor = next
        }
        return fraction(cursor, opened) - fraction(sentenceId, opened)
    }

    private fun spokenLocation(labels: PlaceLabels, hasToc: Boolean): String {
        val chapter = labels.chapter
        val place = when {
            chapter?.number != null -> "Chapter ${chapter.number}, page ${labels.pageLabel}"
            chapter != null -> "${chapter.name}, page ${labels.pageLabel}"
            hasToc -> "Page ${labels.pageLabel}"
            else -> "Page ${labels.pageLabel} of ${labels.pageCount}"
        }
        return "$place. Open the Listen view"
    }

    private fun sleepShort(timer: SleepTimer): String? = when (timer) {
        SleepTimer.Off -> null
        SleepTimer.EndOfChapter -> "End of chapter"
        is SleepTimer.Minutes -> "${((timer.remainingMs + MS_PER_MINUTE - 1) / MS_PER_MINUTE)} min"
    }

    private fun sleepSummary(timer: SleepTimer): String = when (timer) {
        SleepTimer.Off -> "Off"
        SleepTimer.EndOfChapter -> "End of chapter"
        is SleepTimer.Minutes -> "Stops at " + TIME.format(Instant.ofEpochMilli(clockMs() + timer.remainingMs).atZone(ZoneId.systemDefault()))
    }

    override fun playPause() = playback.togglePlayPause()

    override fun next() = playback.next()

    override fun previous() = playback.previous()

    override fun nextSection() {
        val opened = document ?: return
        val current = playback.state.value.currentSentenceId ?: return
        val start = Toc.nextSectionStart(opened.toc, current) ?: return
        jump(order?.resolvePlayable(start) ?: start)
    }

    override fun previousSection() {
        val opened = document ?: return
        val currentOrder = order ?: return
        val current = playback.state.value.currentSentenceId ?: return
        Toc.previousSectionStart(opened.toc, current, currentOrder::resolvePlayable)?.let(::jump)
    }

    override fun jumpToSentence(sentenceId: Int) = jump(order?.resolvePlayable(sentenceId) ?: sentenceId)

    override fun tapBlock(blockIndex: Int) {
        val currentOrder = order ?: return
        currentOrder.firstPlayableFromBlock(blockIndex)?.let(::jump)
    }

    private fun jump(sentenceId: Int) {
        playback.jumpTo(sentenceId)
        state.update { it.copy(followRequest = it.followRequest + 1) }
    }

    override fun seekTo(fraction: Float) {
        val target = seekTarget(fraction) ?: return
        jump(target)
    }

    override fun seekLabel(fraction: Float): String {
        val opened = document ?: return ""
        val target = seekTarget(fraction) ?: return ""
        val labels = PlaceLabels.of(opened, order, target, 1f, pageLabels)
        return listOfNotNull("p. ${labels.pageLabel}", labels.chapter?.number?.let { "Ch. $it" }).joinToString(" · ")
    }

    private fun seekTarget(fraction: Float): Int? {
        val opened = document ?: return null
        val id = (fraction.coerceIn(0f, 1f) * (opened.sentenceIdEnd - 1)).roundToInt()
        return order?.resolvePlayable(id)
    }

    override fun setSpeed(speed: PlaybackSpeed) = playback.setSpeed(speed)

    override fun setVoice(voiceId: String) = playback.setVoice(voiceId)

    override fun playVoiceSample(voiceId: String) = voiceSamples.play(voiceId)

    override fun setReadSkipped(readSkipped: Boolean) {
        settings.update { it.copy(readSkipped = readSkipped) }
        playback.setReadSkipped(readSkipped)
    }

    override fun setSleep(timer: SleepTimer) = playback.setSleepTimer(timer)

    override fun searchOutline(query: String): List<OutlineRow> {
        val opened = document ?: return emptyList()
        val rows = ReaderModel.outline(opened, pageLabels, playback.state.value.currentSentenceId)
        return Toc.searchOutline(opened.toc, query).map { match -> rows[match.index].copy(matchStart = match.matchStart, matchEnd = match.matchEnd) }
    }

    override fun pillHintDone() = settings.update { it.copy(pillHintSeen = true) }

    override fun listenHintDone() = settings.update { it.copy(listenHintSeen = true) }

    override fun bannerAction() {
        when (val banner = state.value.banner) {
            ReaderBanner.VoiceFailed -> voice.retry()
            is ReaderBanner.SlowPhone -> {
                banner.best?.let(playback::setSpeed)
                settings.setSlowTipShown(documentId)
                state.update { it.copy(banner = null) }
            }
            null -> Unit
        }
    }

    override fun dismissBanner() {
        if (state.value.banner is ReaderBanner.SlowPhone) settings.setSlowTipShown(documentId)
        state.update { it.copy(banner = null) }
    }

    override fun messageShown() = state.update { it.copy(message = null) }

    override fun openLibrary() = navigation.backToLibrary()

    override fun openModelSetup() = navigation.openModelSetup()

    override fun openComputerHelper() = navigation.openComputerHelper()

    private companion object {
        const val MAX_TICKS = 60
        const val NEXT_CHAPTERS = 3
        /** The cache cap less 100 MB, in seconds of 16-bit 24 kHz WAV (file 14 §3). */
        const val MAX_PREPARE_SECONDS = (1024 - 100) * 1_048_576.0 / 48_000
        const val SLOW_LIMIT = 0.95f
        const val SMOOTH_LIMIT = 0.9f
        const val PERCENT = 100f
        const val MS_PER_SECOND = 1000L
        const val MS_PER_MINUTE = 60_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
