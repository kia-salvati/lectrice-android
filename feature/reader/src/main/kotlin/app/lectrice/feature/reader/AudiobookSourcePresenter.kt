package app.lectrice.feature.reader

import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookLink
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.Progress
import app.lectrice.core.model.Voice
import kotlinx.coroutines.flow.StateFlow

/** One book's audiobook file: the "Audio" row and card, and switching between the file and speech made on the phone. */
class AudiobookSourcePresenter(
    private val documentId: String,
    private val audiobooks: AudiobookRepository,
    private val playback: PlaybackController,
) {
    val links: StateFlow<Map<String, AudiobookLink>> get() = audiobooks.links

    /** Null when the book has no linked file. */
    fun ui(playing: PlaybackState, voices: List<Voice>): AudiobookUi? {
        val link = audiobooks.links.value[documentId] ?: return null
        val active = playing.source == AudioSource.Audiobook
        return AudiobookUi(
            active = active,
            fileName = link.displayName,
            duration = Progress.formatDuration(link.durationMs / MS_PER_SECOND),
            voiceName = link.voiceId?.let { id -> voices.firstOrNull { it.id == id }?.name },
            madeAt = PlaybackSpeed.fromPercent(link.fileSpeedPercent).takeIf { it != PlaybackSpeed.X1 },
            kind = link.kind,
            mode = link.mode,
            missing = active && playing.audiobookMissing,
        )
    }

    /** The message to show when the file can't be used (it doesn't follow the text sentence by sentence). */
    fun setSource(source: AudioSource): ReaderMessage? {
        if (source == AudioSource.Audiobook && audiobooks.links.value[documentId]?.mode != AudiobookMode.Sentences) {
            return ReaderMessage.AudiobookFailed(ErrorName.AudiobookNotLectrice)
        }
        playback.setAudioSource(source)
        return null
    }

    /** Links a picked file and listens from it; the message when that failed. */
    suspend fun link(uri: String): ReaderMessage? = try {
        audiobooks.link(documentId, uri, AudiobookKind.Imported)
        playback.setAudioSource(AudioSource.Audiobook)
        null
    } catch (error: LectriceError) {
        ReaderMessage.AudiobookFailed(error.name)
    }

    /** The link goes (the file itself stays); playback goes back to speech first. */
    suspend fun unlink() {
        if (playback.state.value.source == AudioSource.Audiobook) playback.setAudioSource(AudioSource.Generated)
        audiobooks.unlink(documentId)
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
