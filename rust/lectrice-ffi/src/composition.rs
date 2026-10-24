//! The Android composition root: picks the adapters, then wires them with `lectrice_app::services::assemble` (shared
//! with the desktop server).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use lectrice_app::exports::ExportSettings;
use lectrice_app::services::{Adapters, Services, Settings, assemble};
use lectrice_app::speech::ReadAheadSettings;
use lectrice_domain::cleaning::RuleBasedCleaner;
use lectrice_domain::{AppResult, SpeechEngine, VoiceId};
use lectrice_infra::{
    CachedLibrary, FsAudioCache, FsDocumentRepository, FsExportRecordStore, FsVoiceSampleCache, KokoroSettings, KokoroSpeechEngine, PdfiumTextSource,
    SentencexSplitter, Sha256ContentHasher, SystemClock, WavDecoder, WavEncoder,
};
use tracing::{info, warn};

use crate::errors::CoreErrorInfo;
use crate::no_exporter::NoExporter;
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
        })
    }
}

pub struct LoadedEngine {
    pub engine: Arc<dyn SpeechEngine>,
    pub load_ms: u64,
}

/// `Missing` when the model folder isn't there (not downloaded yet); `Failed` when it is there but doesn't load.
pub fn load_engine(config: &EngineConfig) -> (EngineStatus, Option<LoadedEngine>) {
    let EngineConfig::Kokoro { model_dir, variant, num_threads, provider } = config;
    if !Path::new(model_dir).is_dir() {
        info!(target: TARGET, variant = ?variant, "model folder missing, no engine");
        return (EngineStatus::Missing, None);
    }
    let settings = KokoroSettings { model_dir: PathBuf::from(model_dir), variant: (*variant).into(), num_threads: *num_threads, provider: provider.clone() };
    let started = Instant::now();
    match KokoroSpeechEngine::load(&settings) {
        Ok(engine) => {
            let load_ms = elapsed_ms(started);
            info!(target: TARGET, engine_id = engine.id(), num_threads, provider = provider.as_str(), load_ms, "speech engine loaded");
            let status = EngineStatus::Ready { engine_id: engine.id().to_string(), load_ms };
            (status, Some(LoadedEngine { engine: Arc::new(engine), load_ms }))
        }
        Err(error) => {
            warn!(target: TARGET, variant = ?variant, num_threads, provider = provider.as_str(), error = %error, "speech engine failed to load");
            (EngineStatus::Failed { error: CoreErrorInfo::from(error.primary()) }, None)
        }
    }
}

pub fn assemble_services(config: &CoreConfig, storage: &Storage, engine: Option<&LoadedEngine>) -> AppResult<Services> {
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
        exporter: Arc::new(NoExporter),
        export_records: storage.export_records.clone(),
        time_stretch: None,
    };
    let settings = Settings {
        import_max_bytes: usize::try_from(config.import_max_mb.saturating_mul(BYTES_PER_MB)).unwrap_or(usize::MAX),
        default_voice: VoiceId::new(config.default_voice.trim()),
        read_ahead: ReadAheadSettings { read_ahead_minutes: config.read_ahead_minutes, keep_minutes_per_document: config.keep_minutes_per_document },
        export: ExportSettings::default(),
    };
    assemble(adapters, settings)
}

pub fn elapsed_ms(started: Instant) -> u64 {
    u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX)
}
