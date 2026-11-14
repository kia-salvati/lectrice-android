package app.lectrice.core.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.Log
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.BookListening
import app.lectrice.core.model.Document
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.Pages
import app.lectrice.core.model.PlaceLabels
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PlaybackOrder
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.PositionSaver
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.Toc
import app.lectrice.core.model.Voices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real playback (files 05, 08, 09, 15, 16): one ExoPlayer whose playlist is a window of sentences; each item's audio
 * comes from [SentenceDataSource]. Lives for the process; [PlaybackService] wraps [player] in a MediaSession for the
 * lock screen, notification and Bluetooth. Every public method may be called from any thread; work runs on the main
 * looper, where ExoPlayer lives.
 */
@UnstableApi
class Media3PlaybackController(
    private val context: Context,
    private val core: RustCore,
    private val engine: EngineHost,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val positions: PositionSaver,
    private val scope: CoroutineScope,
    private val serviceClass: Class<*>,
    private val audiobooks: AudiobookRepository,
) : PlaybackController {
    private val main = Handler(Looper.getMainLooper())
    private val params = SpeechParams()
    private val slicer = AudiobookSlicer(context)
    private val mutable = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutable.asStateFlow()

    val readAhead = ReadAheadController(core, settings, params, scope)

    private var document: Document? = null
    private var order: PlaybackOrder? = null
    private var pageLabels: Map<Int, String> = emptyMap()
    private var window = PlaylistWindow(0)

    /** Set by play() and jumps: the next BUFFERING is "Preparing audio…", later ones mid-play are "Catching up…". */
    private var startingUp = false
    private var finished = false
    private var voiceMissing = false
    private var voiceFailed = false
    private var failuresInARow = 0
    private var retriedSentence: Int? = null
    private var engineHeld = false
    /** The book is set to its audiobook but the file can't be read (file 23 §3.7): nothing plays until it's found. */
    private var audiobookMissing = false
    private var fileSpeedPercent = PERCENT
    private var sleepJob: Job? = null
    private var statsJob: Job? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    val player: ExoPlayer by lazy { buildPlayer() }

    init {
        scope.launch(Dispatchers.Main) {
            settings.settings.map { it.sentenceOnLockScreen }.distinctUntilChanged().drop(1).collect { refreshMetadata() }
        }
        scope.launch(Dispatchers.Main) {
            settings.settings.map { it.readAhead }.distinctUntilChanged().drop(1).collect { readAhead.onSettingChanged() }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            // Small: the player only preloads the next few sentences; the core's read-ahead makes the rest in order.
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
            .build()
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(SentenceDataSource.Factory(core, engine, params, slicer)))
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .setMaxSeekToPreviousPositionMs(0)
            .build()
            .also { it.addListener(listener) }
    }

    // ---- PlaybackController -------------------------------------------------------------------------------------

    override fun open(document: Document, sentenceId: Int?, play: Boolean) = onMain {
        val previous = this.document
        if (previous != null && previous.id != document.id) {
            saveNow()
            player.pause()
            player.clearMediaItems()
            setSleep(SleepTimer.Off)
        }
        scope.launch(Dispatchers.Main) { load(document, sentenceId, play) }
    }

    private suspend fun load(opened: Document, sentenceId: Int?, play: Boolean) {
        val listening = settings.bookListening(opened.id)
        val readSkipped = settings.settings.value.readSkipped
        val source = settings.audioSource(opened.id)
        val timeline = if (source == AudioSource.Audiobook) audiobooks.timeline(opened.id)?.takeIf { it.mode == AudiobookMode.Sentences } else null
        audiobookMissing = source == AudioSource.Audiobook && timeline == null
        params.audiobook = timeline?.let { found ->
            val uri = audiobooks.links.value[opened.id]?.uri ?: return@let null
            AudiobookPlayback(opened.id, uri, found.sentences.associateBy { it.sentenceId })
        }
        fileSpeedPercent = timeline?.fileSpeedPercent ?: PERCENT
        val built = try {
            buildOrder(opened, readSkipped)
        } catch (error: LectriceError) {
            Log.w(SCOPE, "playback order failed", error)
            return
        }
        document = opened
        order = built
        pageLabels = Pages.labelLookup(opened.pages)
        window = PlaylistWindow(built.sentenceIds.size)
        params.voiceId = listening.voiceId
        params.rate = listening.speed.rate
        params.readSkipped = readSkipped
        player.playbackParameters = PlaybackParameters(playerRate(listening.speed))
        finished = false
        val start = built.startingSentenceId(sentenceId)
        mutable.value = PlaybackState(
            documentId = opened.id,
            currentSentenceId = start,
            status = PlaybackStatus.Paused,
            speed = listening.speed,
            voiceId = listening.voiceId,
            readSkipped = readSkipped,
            source = source,
            audiobookMissing = audiobookMissing,
        )
        readAhead.onBook(if (fromFile()) null else opened.id)
        Log.i(SCOPE, "book loaded source=$source fromFile=${fromFile()} sentences=${built.sentenceIds.size} fileSpeed=$fileSpeedPercent")
        if (start != null) setWindowAround(start, prepare = false)
        if (play) play() else publish()
    }

    /** The audiobook's sentences (file 23 §3.4), else the core's playback order. */
    private suspend fun buildOrder(opened: Document, readSkipped: Boolean): PlaybackOrder {
        val fromFile = params.audiobook?.takeIf { it.documentId == opened.id }
        val ids = fromFile?.slices?.values?.sortedBy { it.startMs }?.map { it.sentenceId } ?: library.playbackOrder(opened.id, readSkipped)
        return withContext(Dispatchers.Default) { PlaybackOrder(ids, opened) }
    }

    private fun fromFile(): Boolean = params.audiobook != null

    /** A file made at 1.5× plays at 1.0 for "1.5×" (file 23 §3.4). */
    private fun playerRate(speed: PlaybackSpeed): Float = if (fromFile()) speed.rate * PERCENT / fileSpeedPercent else speed.rate

    /** File 23: listen from the book's audiobook file or from speech made on the phone; keeps the place and play state. */
    override fun setAudioSource(source: AudioSource) = onMain {
        val opened = document ?: return@onMain
        settings.setAudioSource(opened.id, source)
        val wasPlaying = player.playWhenReady
        val current = mutable.value.currentSentenceId
        saveNow()
        player.pause()
        player.clearMediaItems()
        scope.launch(Dispatchers.Main) {
            if (source == AudioSource.Audiobook) runCatching { core.call("stop_read_ahead") { it.stopReadAhead(opened.id) } }
            load(opened, current, wasPlaying)
        }
    }

    override fun play() = onMain {
        val built = order ?: return@onMain
        if (audiobookMissing) {
            publish()
            return@onMain
        }
        if (!fromFile() && engine.activeModel() == null) {
            voiceMissing = true
            publish()
            return@onMain
        }
        voiceMissing = false
        voiceFailed = false
        failuresInARow = 0
        if (finished) {
            finished = false
            built.sentenceIds.firstOrNull()?.let { setWindowAround(it, prepare = false) }
        }
        startService()
        startingUp = true
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
        settings.update { it.copy(lastPlayedDocumentId = document?.id) }
        publish()
    }

    override fun pause() = onMain {
        player.playWhenReady = false
        saveNow()
        publish()
    }

    override fun next() = onMain { step(1) }

    override fun previous() = onMain { step(-1) }

    private fun step(offset: Int) {
        val built = order ?: return
        val current = mutable.value.currentSentenceId ?: return
        val target = built.stepFrom(current, offset) ?: if (offset < 0) current else return
        jumpOnMain(target)
    }

    override fun jumpTo(sentenceId: Int) = onMain { jumpOnMain(sentenceId) }

    private fun jumpOnMain(sentenceId: Int) {
        val built = order ?: return
        val target = built.resolvePlayable(sentenceId) ?: return
        val position = built.positionOf(target) ?: return
        finished = false
        if (window.contains(position) && player.mediaItemCount > 0) {
            player.seekTo(position - window.start, 0)
        } else {
            setWindowAround(target, prepare = player.playbackState != Player.STATE_IDLE)
        }
        if (player.playWhenReady) startingUp = true
        mutable.update { it.copy(currentSentenceId = target) }
        if (!player.playWhenReady) saveNow()
        checkEndOfChapter()
        publish()
    }

    override fun setSpeed(speed: PlaybackSpeed) = onMain {
        params.rate = speed.rate
        player.playbackParameters = PlaybackParameters(playerRate(speed))
        mutable.update { it.copy(speed = speed) }
        rememberListening()
    }

    override fun setVoice(voiceId: String) = onMain {
        if (voiceId == params.voiceId || fromFile()) return@onMain
        params.voiceId = voiceId
        mutable.update { it.copy(voiceId = voiceId) }
        rememberListening()
        document?.id?.let { id -> scope.launch { runCatching { core.call("stop_read_ahead") { it.stopReadAhead(id) } } } }
        mutable.value.currentSentenceId?.let { restartAt(it) }
    }

    override fun setReadSkipped(readSkipped: Boolean) = onMain {
        val opened = document ?: return@onMain
        if (fromFile()) return@onMain
        params.readSkipped = readSkipped
        scope.launch(Dispatchers.Main) {
            val built = runCatching { buildOrder(opened, readSkipped) }.getOrNull() ?: return@launch
            runCatching { core.call("stop_read_ahead") { it.stopReadAhead(opened.id) } }
            order = built
            window = PlaylistWindow(built.sentenceIds.size)
            mutable.update { it.copy(readSkipped = readSkipped) }
            val current = mutable.value.currentSentenceId?.let(built::resolvePlayable) ?: built.sentenceIds.firstOrNull() ?: return@launch
            mutable.update { it.copy(currentSentenceId = current) }
            setWindowAround(current, prepare = player.playbackState != Player.STATE_IDLE)
            publish()
        }
    }

    override fun setSleepTimer(timer: SleepTimer) = onMain { setSleep(timer) }

    override fun stop() = onMain {
        saveNow()
        player.stop()
        player.clearMediaItems()
        setSleep(SleepTimer.Off)
        document = null
        order = null
        params.audiobook = null
        audiobookMissing = false
        slicer.close()
        readAhead.onBook(null)
        mutable.value = PlaybackState()
        releaseEngine()
    }

    /** The model changed (file 11 "Use this"): reload, keep the place, play again if it was playing. */
    fun modelChanged() = onMain {
        val wasPlaying = player.playWhenReady
        val current = mutable.value.currentSentenceId
        player.pause()
        scope.launch(Dispatchers.Main) {
            runCatching { core.call("stop_all_read_ahead") { it.stopAllReadAhead() } }
            engine.modelChanged()
            if (current != null) restartAt(current)
            if (wasPlaying) play()
        }
    }

    // ---- Window and items ---------------------------------------------------------------------------------------

    /** Rebuilds the player's items around the sentence (same sentence, new audio: a voice change). */
    private fun restartAt(sentenceId: Int) {
        setWindowAround(sentenceId, prepare = player.playbackState != Player.STATE_IDLE)
        if (player.playWhenReady) startingUp = true
        publish()
    }

    private fun setWindowAround(sentenceId: Int, prepare: Boolean) {
        val built = order ?: return
        val position = built.positionOf(sentenceId) ?: return
        val index = window.rebuildAround(position)
        player.setMediaItems(items(window.start, window.end), index, 0)
        if (prepare) player.prepare()
    }

    private fun items(from: Int, to: Int): List<MediaItem> {
        val built = order ?: return emptyList()
        val opened = document ?: return emptyList()
        return (from until to).map { item(opened, built, built.sentenceIds[it]) }
    }

    /** File 09 §3.3: title = the sentence (or the section title with the setting off), artist = "Ch. 7 · Book". */
    private fun item(opened: Document, built: PlaybackOrder, sentenceId: Int): MediaItem {
        val labels = PlaceLabels.of(opened, built, sentenceId, params.rate, pageLabels)
        val sentenceOnLock = settings.settings.value.sentenceOnLockScreen
        val title = if (sentenceOnLock) shorten(built.sentence(sentenceId)?.text.orEmpty()) else labels.listenTitle()
        val artist = if (sentenceOnLock) labels.mediaArtist(opened.title) else opened.title
        val metadata = MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).setArtist(artist).setAlbumTitle(opened.title).build()
        return MediaItem.Builder().setMediaId("${opened.id}:$sentenceId").setUri(SentenceUri.of(opened.id, sentenceId)).setMediaMetadata(metadata).build()
    }

    private fun refreshMetadata() {
        val built = order ?: return
        val opened = document ?: return
        if (player.mediaItemCount == 0) return
        for (index in 0 until player.mediaItemCount) {
            val id = sentenceIdOf(player.getMediaItemAt(index)) ?: continue
            player.replaceMediaItem(index, item(opened, built, id))
        }
    }

    private fun sentenceIdOf(item: MediaItem?): Int? = item?.mediaId?.substringAfterLast(':')?.toIntOrNull()

    // ---- Player events ------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = sentenceIdOf(mediaItem) ?: return
            val built = order ?: return
            mutable.update { it.copy(currentSentenceId = id, readyAheadMs = params.readyAheadMs) }
            retriedSentence = null
            if (player.playWhenReady) document?.id?.let { positions.playingAt(it, id) }
            built.positionOf(id)?.let { position ->
                val change = window.advance(position)
                if (!change.isEmpty) {
                    player.addMediaItems(items(change.appendFrom, change.appendFrom + change.appendCount))
                    if (change.dropFromFront > 0) player.removeMediaItems(0, change.dropFromFront)
                }
            }
            checkEndOfChapter()
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    startingUp = false
                    failuresInARow = 0
                }
                Player.STATE_ENDED -> {
                    val built = order
                    if (built != null && mutable.value.currentSentenceId == built.lastSentenceId) {
                        finished = true
                        player.playWhenReady = false
                        saveNow()
                    }
                }
            }
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                saveNow()
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepStopped()
            }
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            readAhead.onPlaying(player.playWhenReady)
            if (player.playWhenReady) holdEngine() else releaseEngine()
            if (isPlaying) startSleepTicker() else sleepJob?.cancel()
            publish()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /** File 09 §3.5. */
    private fun handleError(error: PlaybackException) {
        val name = (error.cause as? SpeechIOException)?.error?.name ?: ErrorName.Internal
        val current = mutable.value.currentSentenceId
        Log.w(SCOPE, "player error ${name.name} sentence=$current fromFile=${fromFile()}")
        if (fromFile() && (name == ErrorName.FileUnreadable || name == ErrorName.AudiobookUnreadable)) {
            audiobookMissing = true
            player.playWhenReady = false
            mutable.update { it.copy(audiobookMissing = true) }
            publish()
            return
        }
        when (name) {
            ErrorName.ModelMissing -> {
                voiceMissing = true
                player.playWhenReady = false
            }
            ErrorName.SpeechEngineUnavailable -> {
                voiceFailed = true
                player.playWhenReady = false
            }
            ErrorName.SentenceNotFound -> scope.launch(Dispatchers.Main) { reloadOrder() }
            ErrorName.VoiceUnknown -> {
                setVoice(Voices.DEFAULT_VOICE_ID)
                return
            }
            else -> if (retriedSentence != current) {
                retriedSentence = current
                player.prepare()
                return
            } else {
                failuresInARow++
                mutable.update { it.copy(skippedCount = it.skippedCount + 1) }
                if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                    voiceFailed = true
                    player.playWhenReady = false
                } else {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        }
        publish()
    }

    private suspend fun reloadOrder() {
        val opened = document ?: return
        val built = runCatching { buildOrder(opened, params.readSkipped) }.getOrNull() ?: return
        order = built
        window = PlaylistWindow(built.sentenceIds.size)
        val current = mutable.value.currentSentenceId?.let(built::resolvePlayable) ?: return
        setWindowAround(current, prepare = true)
    }

    private fun publish() {
        val status = when {
            document == null -> PlaybackStatus.Idle
            voiceMissing -> PlaybackStatus.VoiceMissing
            voiceFailed -> PlaybackStatus.VoiceFailed
            finished -> PlaybackStatus.Finished
            !player.playWhenReady -> PlaybackStatus.Paused
            player.playbackState == Player.STATE_READY -> PlaybackStatus.Playing
            startingUp -> PlaybackStatus.Preparing
            else -> PlaybackStatus.CatchingUp
        }
        mutable.update { it.copy(status = status, readyAheadMs = params.readyAheadMs, error = if (voiceFailed) ErrorName.SpeechEngineUnavailable else null) }
    }

    // ---- Engine, stats, service ---------------------------------------------------------------------------------

    private fun holdEngine() {
        if (engineHeld || fromFile()) return
        engineHeld = true
        engine.acquire()
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(STATS_INTERVAL_MS)
                val stats = runCatching { core.call("speech_stats") { it.speechStats() } }.getOrNull()
                mutable.update { it.copy(rtf = stats?.rtfMedian) }
            }
        }
    }

    private fun releaseEngine() {
        if (!engineHeld) return
        engineHeld = false
        statsJob?.cancel()
        engine.release()
    }

    /** Binds a MediaController so Media3 starts the session service and promotes it to the foreground while playing. */
    private fun startService() {
        if (controllerFuture != null) return
        controllerFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, serviceClass))).buildAsync()
    }

    // ---- Sleep timer (file 15) ----------------------------------------------------------------------------------

    private fun setSleep(timer: SleepTimer) {
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        mutable.update { it.copy(sleep = timer) }
        if (timer is SleepTimer.Minutes && player.isPlaying) startSleepTicker() else sleepJob?.cancel()
        checkEndOfChapter()
    }

    private fun startSleepTicker() {
        if (mutable.value.sleep !is SleepTimer.Minutes) return
        sleepJob?.cancel()
        sleepJob = scope.launch(Dispatchers.Main) {
            var last = System.nanoTime()
            while (true) {
                delay(SLEEP_TICK_MS)
                val now = System.nanoTime()
                val elapsedMs = (now - last) / NANOS_PER_MS
                last = now
                val timer = mutable.value.sleep as? SleepTimer.Minutes ?: break
                val remaining = (timer.remainingMs - elapsedMs).coerceAtLeast(0)
                mutable.update { it.copy(sleep = timer.copy(remainingMs = remaining)) }
                if (remaining == 0L) {
                    player.pauseAtEndOfMediaItems = true
                    fadeNearItemEnd()
                }
            }
        }
    }

    /** End of chapter: pause after the last sentence before the next chapter-level entry. */
    private fun checkEndOfChapter() {
        if (mutable.value.sleep != SleepTimer.EndOfChapter) return
        val opened = document ?: return
        val built = order ?: return
        val current = mutable.value.currentSentenceId ?: return
        val location = Toc.location(opened.toc, current) ?: return
        val end = Toc.entryEndSentenceId(opened.toc, location.chapterIndex) ?: Int.MAX_VALUE
        val next = built.stepFrom(current, 1)
        val lastOfChapter = next == null || next >= end
        player.pauseAtEndOfMediaItems = lastOfChapter
        if (lastOfChapter) scope.launch(Dispatchers.Main) { fadeNearItemEnd() }
    }

    /** The last 3 s of the final sentence fade to silence (file 15). */
    private suspend fun fadeNearItemEnd() {
        while (player.pauseAtEndOfMediaItems && player.isPlaying) {
            val left = player.duration - player.currentPosition
            if (player.duration != C.TIME_UNSET && left in 0..FADE_MS) player.volume = left.toFloat() / FADE_MS
            delay(FADE_TICK_MS)
        }
    }

    private fun sleepStopped() {
        if (!player.pauseAtEndOfMediaItems) return
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        mutable.update { it.copy(sleep = SleepTimer.Off) }
        sleepJob?.cancel()
        readAhead.onSleepTimerFired()
        // Paused at the end of the item: move on so a later play starts at the next sentence.
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    private fun rememberListening() {
        val id = mutable.value.documentId ?: return
        settings.setBookListening(id, BookListening(mutable.value.voiceId, mutable.value.speed))
    }

    private fun saveNow() {
        val current = mutable.value
        val id = current.documentId ?: return
        current.currentSentenceId?.let { positions.saveNow(id, it) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Media resumption after process death (file 09 §3.9): the last played book at its saved place. */
    suspend fun resumeLastBook(): Boolean {
        val id = settings.settings.value.lastPlayedDocumentId ?: return false
        val opened = runCatching { library.getDocument(id, markOpened = false) }.getOrNull() ?: return false
        withContext(Dispatchers.Main) { load(opened, opened.lastSentenceId, play = false) }
        return true
    }

    /** Whitespace collapsed, at most 120 characters (the system shows about 35–40 anyway). */
    private fun shorten(text: String): String {
        val single = text.replace(WHITESPACE, " ").trim()
        return if (single.length <= TITLE_MAX) single else single.take(TITLE_MAX - 1).trimEnd() + "…"
    }

    private companion object {
        const val SCOPE = "Playback"
        const val PERCENT = 100
        const val MIN_BUFFER_MS = 5_000
        const val MAX_BUFFER_MS = 15_000
        const val BUFFER_FOR_PLAYBACK_MS = 250
        const val BUFFER_AFTER_REBUFFER_MS = 500
        const val MAX_FAILURES_IN_A_ROW = 3
        const val STATS_INTERVAL_MS = 30_000L
        const val SLEEP_TICK_MS = 1_000L
        const val FADE_MS = 3_000L
        const val FADE_TICK_MS = 100L
        const val NANOS_PER_MS = 1_000_000L
        const val TITLE_MAX = 120
        val WHITESPACE = Regex("\\s+")
    }
}
