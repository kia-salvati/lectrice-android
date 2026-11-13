package app.lectrice.core.data

import uniffi.lectrice_ffi.EngineConfig

/**
 * The only place that turns a model choice into the core's engine config. `lectrice-ffi`'s `composition::load_engine`
 * is the Rust-side factory (it wraps our bundles in `BundleSpeechEngine` for their own ids); any other
 * engine is one more [EngineConfig] variant there plus a branch here.
 */
class EngineFactory(private val models: ModelStore) {
    fun config(spec: ModelSpec, threads: Int): EngineConfig =
        EngineConfig.Kokoro(modelDir = models.dir(spec).path, variant = spec.variant, numThreads = threads.toUInt(), provider = CPU_PROVIDER, engineId = spec.engineIdOverride)

    private companion object {
        /** xnnpack and nnapi didn't engage on either test phone (spike S5/S7). */
        const val CPU_PROVIDER = "cpu"
    }
}
