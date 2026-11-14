package app.lectrice.core.data

import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceRepository

/** The 28 English voices; cached after the first call (the list is fixed per engine family). */
class CoreVoiceRepository(private val core: RustCore) : VoiceRepository {
    @Volatile private var cached: List<Voice>? = null

    override suspend fun listVoices(): List<Voice> = cached ?: core.call("list_voices") { it.listVoices().voices.map(Mappers::voice) }.also { cached = it }
}
