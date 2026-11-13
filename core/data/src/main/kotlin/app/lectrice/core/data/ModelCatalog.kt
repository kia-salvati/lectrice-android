package app.lectrice.core.data

import app.lectrice.core.model.VoiceModelInfo
import uniffi.lectrice_ffi.KokoroVariant

/**
 * The voice models the app offers, in the order shown (user 2026-10-25): Fast (ours, default), Full quality (official
 * fp32), Small (ours). The archive SHA-256 is checked before unpacking; only [KEEP] is unpacked (the English engine's
 * files). A new model is one more entry here plus its engine config in [EngineFactory].
 */
enum class ModelSpec(
    val info: VoiceModelInfo,
    val variant: KokoroVariant,
    val url: String,
    val archiveSha256: String,
    /** The folder at the top of the archive. */
    val archiveRoot: String,
    val modelFile: String,
) {
    Fast(
        VoiceModelInfo(
            id = "kokoro-lectrice-fast",
            engineId = "kokoro-v1.0-lectrice-fast-v1",
            name = "Fast",
            description = "Our own build of the same voice. Sounds the same as Full quality in our tests, and makes speech fastest.",
            downloadBytes = 140_395_500,
            installedBytes = 169_233_154,
        ),
        KokoroVariant.INT8,
        "https://github.com/kia-salvati/lectrice/releases/download/model-kokoro-lectrice-fast-v1/kokoro-lectrice-q8-multi-lang-v1_0.tar.bz2",
        "f9ecc2e7135a86251ddb79fcea79503e9bab6b601cba1eb76ffee01e6e07adb3",
        "kokoro-lectrice-q8-multi-lang-v1_0",
        "model.int8.onnx",
    ),
    Full(
        VoiceModelInfo(
            id = "kokoro-en-v1_0-fp32",
            engineId = "kokoro-v1.0",
            name = "Full quality",
            description = "The original voice model, the same as the desktop app. The largest download.",
            downloadBytes = 349_906_910,
            installedBytes = 384_089_462,
        ),
        KokoroVariant.FP32,
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
        "kokoro-multi-lang-v1_0",
        "model.onnx",
    ),
    Small(
        VoiceModelInfo(
            id = "kokoro-lectrice-small",
            engineId = "kokoro-v1.0-lectrice-small-v1",
            name = "Small",
            description = "Our own build with smaller weights. Sounds like Full quality, at Full quality's speed.",
            downloadBytes = 140_366_606,
            installedBytes = 169_078_421,
        ),
        KokoroVariant.INT8,
        "https://github.com/kia-salvati/lectrice/releases/download/model-kokoro-lectrice-small-v1/kokoro-lectrice-w8-multi-lang-v1_0.tar.bz2",
        "57595e9ef3ddcb37107b1cc3e1318b450853680b7e8aafd45160da07efe9b1e2",
        "kokoro-lectrice-w8-multi-lang-v1_0",
        "model.int8.onnx",
    ),
    ;

    /**
     * The id the core's engine reports: the bundle's own for ours (they share the official int8 file slot), the core's
     * slot id for the official fp32. Same ids as the desktop app.
     */
    val engineIdOverride: String? get() = info.engineId.takeIf { variant == KokoroVariant.INT8 }

    /** Files and folders unpacked (relative to [archiveRoot]): what the English Kokoro engine reads, plus the licence. */
    val keep: List<String> get() = KEEP + modelFile

    companion object {
        val DEFAULT = Fast
        private val KEEP = listOf("voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "espeak-ng-data", "LICENSE", "NOTICE", "README.md")

        fun byId(id: String?): ModelSpec? = entries.firstOrNull { it.info.id == id }
    }
}
