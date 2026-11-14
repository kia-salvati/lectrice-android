//! The Android composition root: picks the adapters, then wires them with `lectrice_app::services::assemble` (shared
//! with the desktop server).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use lectrice_app::exports::ExportSettings;
use lectrice_app::services::{Adapters, Services, Settings, SpeechOptions, assemble_with};
use lectrice_app::speech::ReadAheadSettings;
use lectrice_domain::cleaning::RuleBasedCleaner;
use lectrice_domain::{AppResult, SpeechEngine, SpeechHelper, Voice, VoiceId};
use lectrice_infra::{
    CachedLibrary, DeferredSpeechEngine, FsAudioCache, FsDocumentRepository, FsExportRecordStore, FsVoiceSampleCache, KokoroSettings, KokoroSpeechEngine,
    PdfiumTextSource, SentencexSplitter, Sha256ContentHasher, SonicTimeStretch, SystemClock, WavDecoder, WavEncoder,
};
use tracing::{info, warn};

use crate::errors::CoreErrorInfo;
use crate::exporter_slot::ExporterSlot;
use crate::records::{CoreConfig, EngineConfig, EngineStatus};

const TARGET: &str = "lectrice_ffi";
const BYTES_PER_MB: u64 = 1024 * 1024;
/// Parsed documents kept in memory (a book's `document.json` is a few hundred KB to a few MB).
const DOCUMENT_CACHE_CAPACITY: usize = 2;

pub type Library = CachedLibrary<FsDocumentRepository>;

/// The adapters that outlive an engine reload.
pub struct Storage {
    pub library: Arc<Library>,
    audio_cache: Arc<FsAudioCache>,
    pdf_source: Arc<PdfiumTextSource>,
    voice_samples: Arc<FsVoiceSampleCache>,
    export_records: Arc<FsExportRecordStore>,
    pub exporter: Arc<ExporterSlot>,
}

impl Storage {
    pub fn open(config: &CoreConfig) -> AppResult<Self> {
        let data_dir = PathBuf::from(&config.data_dir);
        let cache_dir = PathBuf::from(&config.cache_dir);
        let started = Instant::now();
        let audio_cache = FsAudioCache::open(&cache_dir, config.cache_max_mb.saturating_mul(BYTES_PER_MB))?;
        info!(target: TARGET, cache_max_mb = config.cache_max_mb, scan_ms = elapsed_ms(started), "audio cache opened");
        Ok(Self {
            library: Arc::new(CachedLibrary::new(Arc::new(FsDocumentRepository::new(&data_dir)), DOCUMENT_CACHE_CAPACITY)),
            audio_cache: Arc::new(audio_cache),
            pdf_source: Arc::new(PdfiumTextSource::new(Path::new(&config.pdfium_library))?),
            voice_samples: Arc::new(FsVoiceSampleCache::new(&cache_dir)),
            export_records: Arc::new(FsExportRecordStore::new(&data_dir)),
            exporter: Arc::new(ExporterSlot::default()),
        })
    }
}

pub struct LoadedEngine {
    pub engine: Arc<dyn SpeechEngine>,
    pub load_ms: u64,
}

/// `Missing` when the model folder isn't there (not downloaded yet); `Failed` when it is there but doesn't load.
pub fn load_engine(config: &EngineConfig) -> (EngineStatus, Option<LoadedEngine>) {
    let EngineConfig::Kokoro { model_dir, variant, num_threads, provider, engine_id } = config;
    if !Path::new(model_dir).is_dir() {
        info!(target: TARGET, variant = ?variant, "model folder missing, no engine");
        return (EngineStatus::Missing, None);
    }
    let settings = KokoroSettings { model_dir: PathBuf::from(model_dir), variant: (*variant).into(), num_threads: *num_threads, provider: provider.clone() };
    let started = Instant::now();
    match KokoroSpeechEngine::load(&settings) {
        Ok(engine) => {
            let load_ms = elapsed_ms(started);
            let engine: Arc<dyn SpeechEngine> = match engine_id {
                Some(id) => Arc::new(BundleSpeechEngine::new(Arc::new(engine), id.clone())),
                None => Arc::new(engine),
            };
            info!(target: TARGET, engine_id = engine.id(), num_threads, provider = provider.as_str(), load_ms, "speech engine loaded");
            let status = EngineStatus::Ready { engine_id: engine.id().to_string(), load_ms };
            (status, Some(LoadedEngine { engine, load_ms }))
        }
        Err(error) => {
            warn!(target: TARGET, variant = ?variant, num_threads, provider = provider.as_str(), error = %error, "speech engine failed to load");
            (EngineStatus::Failed { error: CoreErrorInfo::from(error.primary()) }, None)
        }
    }
}

/// With the computer helper on (file 24): the engine knows its id, voices and sample rate without loading the model,
/// and loads only when this phone has to speak itself (C46). `Missing` when the model folder isn't there.
pub fn load_engine_deferred(config: &EngineConfig) -> (EngineStatus, Option<(LoadedEngine, Arc<DeferredSpeechEngine>)>) {
    let EngineConfig::Kokoro { model_dir, variant, num_threads, provider, engine_id } = config;
    if !Path::new(model_dir).is_dir() {
        info!(target: TARGET, variant = ?variant, "model folder missing, no engine");
        return (EngineStatus::Missing, None);
    }
    let settings = KokoroSettings { model_dir: PathBuf::from(model_dir), variant: (*variant).into(), num_threads: *num_threads, provider: provider.clone() };
    let deferred = Arc::new(KokoroSpeechEngine::deferred(settings, engine_id.clone()));
    info!(target: TARGET, engine_id = deferred.id(), num_threads, "speech engine ready to load on first use");
    let status = EngineStatus::Ready { engine_id: deferred.id().to_string(), load_ms: 0 };
    (status, Some((LoadedEngine { engine: deferred.clone(), load_ms: 0 }, deferred)))
}

/// The engine's voices without loading it, so the voice menus work before a model is there.
pub fn voice_catalog() -> Vec<Voice> {
    KokoroSpeechEngine::voice_catalog()
}

/// `helper`: the paired computer, when the helper is on (file 24); the services then fill through it.
pub fn assemble_services(config: &CoreConfig, storage: &Storage, engine: Option<&LoadedEngine>, helper: Option<Arc<dyn SpeechHelper>>) -> AppResult<Services> {
    let adapters = Adapters {
        hasher: Arc::new(Sha256ContentHasher),
        pdf_source: storage.pdf_source.clone(),
        cleaner: Arc::new(RuleBasedCleaner),
        splitter: Arc::new(SentencexSplitter),
        reader: storage.library.clone(),
        writer: storage.library.clone(),
        clock: Arc::new(SystemClock),
        speech_engine: engine.map(|loaded| loaded.engine.clone()),
        speech_engine_load_ms: engine.and_then(|loaded| u32::try_from(loaded.load_ms).ok()),
        audio_cache: storage.audio_cache.clone(),
        audio_retention: storage.audio_cache.clone(),
        audio_encoder: Arc::new(WavEncoder),
        audio_decoder: Arc::new(WavDecoder),
        voice_samples: storage.voice_samples.clone(),
        exporter: storage.exporter.clone(),
        export_records: storage.export_records.clone(),
        time_stretch: Some(Arc::new(SonicTimeStretch)),
    };
    let settings = Settings {
        import_max_bytes: usize::try_from(config.import_max_mb.saturating_mul(BYTES_PER_MB)).unwrap_or(usize::MAX),
        default_voice: VoiceId::new(config.default_voice.trim()),
        read_ahead: ReadAheadSettings { read_ahead_minutes: config.read_ahead_minutes, keep_minutes_per_document: config.keep_minutes_per_document },
        export: ExportSettings::default(),
    };
    assemble_with(adapters, settings, SpeechOptions { helper, ..SpeechOptions::default() })
}

pub fn elapsed_ms(started: Instant) -> u64 {
    u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX)
}

/// The core's Kokoro adapter names an engine after its model file slot, and our Fast and Small bundles share the
/// official int8 slot. Each bundle's engine reports the bundle's own id (as the desktop app does), so clips and exports
/// never mix. Another engine kind plugs in next to it in [`load_engine`].
pub struct BundleSpeechEngine {
    inner: Arc<dyn SpeechEngine>,
    engine_id: String,
}

impl BundleSpeechEngine {
    pub fn new(inner: Arc<dyn SpeechEngine>, engine_id: String) -> Self {
        Self { inner, engine_id }
    }
}

impl SpeechEngine for BundleSpeechEngine {
    fn id(&self) -> &str {
        &self.engine_id
    }

    fn voices(&self) -> Vec<lectrice_domain::Voice> {
        self.inner.voices()
    }

    fn sample_rate(&self) -> u32 {
        self.inner.sample_rate()
    }

    fn synthesize(&self, request: lectrice_domain::SpeechRequest) -> AppResult<lectrice_domain::Audio> {
        self.inner.synthesize(request)
    }

    fn revision(&self) -> &str {
        self.inner.revision()
    }
}
