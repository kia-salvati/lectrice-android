//! The object Kotlin holds. Every call blocks; Kotlin picks the thread.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::time::Instant;

use lectrice_app::documents::{GetDocumentArgs, ImportPdfArgs};
use lectrice_app::services::{Services, purge_removed_at_start};
use lectrice_app::speech::{SpeakSentenceArgs, playback_order};
use lectrice_app::{RequestContext, UseCase};
use lectrice_domain::{AppError, AudioDecoder, DocumentId, DocumentReader, ErrorName, SentenceId, VoiceId};
use lectrice_infra::WavDecoder;
use tracing::{info, warn};

use crate::composition::{LoadedEngine, Storage, assemble_services, elapsed_ms, load_engine};
use crate::errors::{CoreErrorInfo, CoreResult};
use crate::log_bridge::{self, LogSink};
use crate::records::{CoreConfig, DocumentRecord, EngineConfig, EngineStatus, ImportResult, SpeakArgs, SpokenAudio, VoiceList};

const TARGET: &str = "lectrice_ffi";
const MS_PER_SECOND: u64 = 1000;

/// What changes when the engine is reloaded: the engine and the services built around it.
pub(crate) struct Loaded {
    pub(crate) services: Services,
    #[cfg_attr(not(feature = "bench"), expect(dead_code, reason = "only the bench calls the engine directly"))]
    pub(crate) engine: Option<LoadedEngine>,
    status: EngineStatus,
}

#[derive(uniffi::Object)]
pub struct LectriceCore {
    config: CoreConfig,
    storage: Storage,
    loaded: RwLock<Loaded>,
    next_request: AtomicU64,
}

#[uniffi::export]
impl LectriceCore {
    /// Opens the library and the audio cache, without an engine (it loads with `reload_engine`).
    #[uniffi::constructor]
    pub fn new(config: CoreConfig, log: Arc<dyn LogSink>) -> CoreResult<Arc<Self>> {
        log_bridge::install(log);
        let started = Instant::now();
        let storage = Storage::open(&config)?;
        let services = assemble_services(&config, &storage, None)?;
        purge_removed_at_start(&services)?;
        info!(target: TARGET, open_ms = elapsed_ms(started), "core opened");
        let loaded = Loaded { services, engine: None, status: EngineStatus::Missing };
        Ok(Arc::new(Self { config, storage, loaded: RwLock::new(loaded), next_request: AtomicU64::new(0) }))
    }

    pub fn engine_status(&self) -> EngineStatus {
        self.read().status.clone()
    }

    /// Drops the old engine before loading the new one, so two models are never in memory at once.
    pub fn reload_engine(&self, engine: EngineConfig) -> EngineStatus {
        let mut loaded = self.write();
        let without_engine = match assemble_services(&self.config, &self.storage, None) {
            Ok(services) => services,
            Err(error) => return failed(error),
        };
        *loaded = Loaded { services: without_engine, engine: None, status: EngineStatus::Missing };

        let (status, new_engine) = load_engine(&engine);
        loaded.status = match new_engine.map(|new_engine| (assemble_services(&self.config, &self.storage, Some(&new_engine)), new_engine)) {
            Some((Ok(services), new_engine)) => {
                *loaded = Loaded { services, engine: Some(new_engine), status: status.clone() };
                status
            }
            Some((Err(error), _)) => failed(error),
            None => status,
        };
        loaded.status.clone()
    }

    pub fn import_pdf(&self, pdf_path: String, file_name: String) -> CoreResult<ImportResult> {
        let started = Instant::now();
        let bytes = std::fs::read(&pdf_path).map_err(|error| AppError::new(ErrorName::FileUnreadable).with_source(error))?;
        let services = self.services();
        let imported = services.documents.import_pdf.run(&self.context(), ImportPdfArgs { file_name, bytes })?;
        let duration_ms = elapsed_ms(started);
        info!(target: TARGET, doc_id = %imported.summary.id, pages = imported.summary.page_count, sentences = imported.summary.sentence_count, already_in_library = imported.already_in_library, duration_ms, "pdf imported");
        Ok(ImportResult::new(imported, duration_ms))
    }

    pub fn get_document(&self, document_id: String) -> CoreResult<DocumentRecord> {
        let started = Instant::now();
        let id = DocumentId::parse(&document_id)?;
        let opened = self.services().documents.get_document.run(&self.context(), GetDocumentArgs { id })?;
        let record = DocumentRecord::new(opened.document, opened.last_sentence_id);
        info!(target: TARGET, doc_id = %document_id, blocks = record.blocks.len(), mapping_ms = elapsed_ms(started), "document handed over");
        Ok(record)
    }

    /// The sentence ids the player reads, in order (the same order as the read-ahead).
    pub fn playback_order(&self, document_id: String, read_skipped: bool) -> CoreResult<Vec<u32>> {
        let id = DocumentId::parse(&document_id)?;
        let document = self.storage.library.find(&id)?.ok_or_else(|| AppError::new(ErrorName::DocumentNotFound))?;
        Ok(playback_order(&document, read_skipped).into_iter().map(|sentence| sentence.0).collect())
    }

    pub fn list_voices(&self) -> CoreResult<VoiceList> {
        let speech = self.services().speech()?;
        Ok(speech.list_voices.run(&self.context(), ())?.into())
    }

    /// Blocks until the clip is ready (from the cache or made now), and moves the read-ahead to this sentence.
    pub fn speak_sentence(&self, args: SpeakArgs) -> CoreResult<SpokenAudio> {
        let SpeakArgs { document_id, sentence_id, voice_id, rate, read_skipped } = args;
        let speech = self.services().speech()?;
        let args = SpeakSentenceArgs {
            document_id: DocumentId::parse(&document_id)?,
            sentence_id: SentenceId(sentence_id),
            voice_id: VoiceId::new(voice_id),
            rate,
            read_skipped,
            plan_ahead: true,
        };
        let spoken = speech.speak_sentence.run(&self.context(), args)?;
        let audio = WavDecoder.decode(&spoken.bytes)?;
        let duration_ms = (audio.samples.len() as u64 * MS_PER_SECOND).checked_div(u64::from(audio.sample_rate)).unwrap_or_default();
        Ok(SpokenAudio { wav: spoken.bytes, duration_ms: u32::try_from(duration_ms).unwrap_or(u32::MAX), ready_ahead_ms: spoken.ready_ahead_ms })
    }
}

fn failed(error: AppError) -> EngineStatus {
    warn!(target: TARGET, error = %error, "engine services failed to start");
    EngineStatus::Failed { error: CoreErrorInfo::from(error.primary()) }
}

impl LectriceCore {
    pub(crate) fn read(&self) -> RwLockReadGuard<'_, Loaded> {
        self.loaded.read().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn write(&self) -> RwLockWriteGuard<'_, Loaded> {
        self.loaded.write().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// A clone, so a long call (an import, a synthesis) doesn't hold the lock an engine reload needs.
    fn services(&self) -> Services {
        self.read().services.clone()
    }

    pub(crate) fn context(&self) -> RequestContext {
        RequestContext::new(format!("ffi-{}", self.next_request.fetch_add(1, Ordering::Relaxed)))
    }
}
