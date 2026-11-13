//! The object Kotlin holds. Every call blocks; Kotlin picks the thread.

use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::time::Instant;

use lectrice_app::documents::{GetDocumentArgs, ImportPdfArgs, SaveReadingPositionArgs};
use lectrice_app::exports::{EstimateAudioExportArgs, MarkExportSavedArgs, PauseAudioExportArgs, StartAudioExportArgs};
use lectrice_app::library::PurgeRemovedDocumentsArgs;
use lectrice_app::services::{Services, purge_removed_at_start};
use lectrice_app::speech::{PrepareArgs, PrepareStatus, SpeakSentenceArgs, playback_order};
use lectrice_app::{RequestContext, UseCase};
use lectrice_domain::{
    AppError, AudioDecoder, AudiobookMismatch, DocumentId, DocumentReader, ErrorName, ExportFormat, ExportId, ExportRange, ExportSpeed, ListOptions,
    SentenceId, VoiceId, match_audiobook,
};
use lectrice_infra::export::mp4_map;
use lectrice_infra::{KokoroSpeechEngine, WavDecoder};
use tracing::{info, warn};

use crate::android_exporter::{AndroidExporter, AudioEncoderSink};
use crate::audiobook_records::{AudiobookFileRecord, AudiobookTimelineRecord};
use crate::composition::{LoadedEngine, Storage, assemble_services, elapsed_ms, load_engine};
use crate::errors::{CoreErrorInfo, CoreResult};
use crate::export_records::{AudioExportRecord, ExportEstimateRecord, ExportRequest, PauseReasonRecord, pause_reason};
use crate::log_bridge::{self, LogSink};
use crate::records::{
    CoreConfig, DocumentRecord, DocumentSummaryRecord, EngineConfig, EngineStatus, EvictionRecord, ImportResult, PurgeReportRecord, SpeakArgs,
    SpeechStatsRecord, SpokenAudio, StorageUsageRecord, SummaryPage, VoiceList,
};

const TARGET: &str = "lectrice_ffi";
const MS_PER_SECOND: u64 = 1000;

/// What changes when the engine is reloaded: the engine and the services built around it.
pub(crate) struct Loaded {
    pub(crate) services: Services,
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

    /// One book's summary (to tell whether opening it will clean it again first), without opening it.
    pub fn document_summary(&self, document_id: String) -> CoreResult<DocumentSummaryRecord> {
        let id = DocumentId::parse(&document_id)?;
        let summary = self.storage.library.find_summary(&id)?.ok_or_else(|| AppError::new(ErrorName::DocumentNotFound))?;
        Ok(summary.into())
    }

    /// The sentence ids the player reads, in order (the same order as the read-ahead).
    pub fn playback_order(&self, document_id: String, read_skipped: bool) -> CoreResult<Vec<u32>> {
        let id = DocumentId::parse(&document_id)?;
        let document = self.storage.library.find(&id)?.ok_or_else(|| AppError::new(ErrorName::DocumentNotFound))?;
        Ok(playback_order(&document, read_skipped).into_iter().map(|sentence| sentence.0).collect())
    }

    /// The engine's voices; without an engine, the Kokoro catalog (the same list) so menus work before the model loads.
    pub fn list_voices(&self) -> CoreResult<VoiceList> {
        match self.services().speech() {
            Ok(speech) => Ok(speech.list_voices.run(&self.context(), ())?.into()),
            Err(_) => Ok(VoiceList {
                voices: KokoroSpeechEngine::voice_catalog().into_iter().map(Into::into).collect(),
                default_voice_id: self.config.default_voice.clone(),
            }),
        }
    }

    /// Blocks until the clip is ready (from the cache or made now), and moves the read-ahead to this sentence.
    pub fn speak_sentence(&self, args: SpeakArgs) -> CoreResult<SpokenAudio> {
        let SpeakArgs { document_id, sentence_id, voice_id, rate, read_skipped, plan_ahead } = args;
        let speech = self.services().speech()?;
        let args = SpeakSentenceArgs {
            document_id: DocumentId::parse(&document_id)?,
            sentence_id: SentenceId(sentence_id),
            voice_id: VoiceId::new(voice_id),
            rate,
            read_skipped,
            plan_ahead,
        };
        let spoken = speech.speak_sentence.run(&self.context(), args)?;
        spoken_audio(spoken.bytes, spoken.ready_ahead_ms)
    }

    /// Drops the engine (idle for 5 minutes, or the system asked for memory); `reload_engine` brings it back.
    pub fn unload_engine(&self) {
        let mut loaded = self.write();
        if loaded.engine.is_none() {
            return;
        }
        match assemble_services(&self.config, &self.storage, None) {
            Ok(services) => {
                *loaded = Loaded { services, engine: None, status: EngineStatus::Unloaded };
                info!(target: TARGET, "speech engine unloaded");
            }
            Err(error) => warn!(target: TARGET, error = %error, "engine unload failed, kept loaded"),
        }
    }

    pub fn list_recent(&self, limit: u32, offset: u32) -> CoreResult<SummaryPage> {
        let options = ListOptions { limit: Some(limit as usize), offset: Some(offset as usize) };
        Ok(self.services().documents.list_recent.run(&self.context(), options)?.into())
    }

    pub fn save_position(&self, document_id: String, sentence_id: u32) -> CoreResult<()> {
        let args = SaveReadingPositionArgs { id: DocumentId::parse(&document_id)?, sentence_id: SentenceId(sentence_id) };
        Ok(self.services().documents.save_position.run(&self.context(), args)?)
    }

    /// A summary older than this is cleaned again when it is opened.
    pub fn current_cleaner_version(&self) -> u32 {
        self.services().documents.current_cleaner_version()
    }

    /// Hides the book at once; `purge_removed` deletes it for good (after the undo window, or at the next start).
    pub fn remove_document(&self, document_id: String) -> CoreResult<()> {
        Ok(self.services().library.remove.run(&self.context(), DocumentId::parse(&document_id)?)?)
    }

    pub fn restore_document(&self, document_id: String) -> CoreResult<DocumentSummaryRecord> {
        Ok(self.services().library.restore.run(&self.context(), DocumentId::parse(&document_id)?)?.into())
    }

    /// `delete_exports_for`: removed books whose finished exports go too.
    pub fn purge_removed(&self, delete_exports_for: Vec<String>) -> CoreResult<PurgeReportRecord> {
        let delete_exports_for = delete_exports_for.iter().map(|id| DocumentId::parse(id)).collect::<Result<Vec<_>, _>>()?;
        Ok(self.services().library.purge_removed.run(&self.context(), PurgeRemovedDocumentsArgs { delete_exports_for })?.into())
    }

    pub fn clear_document_audio(&self, document_id: String) -> CoreResult<EvictionRecord> {
        Ok(self.services().library.clear_document_audio.run(&self.context(), DocumentId::parse(&document_id)?)?.into())
    }

    pub fn storage_usage(&self) -> CoreResult<StorageUsageRecord> {
        Ok(self.services().library.storage_usage.run(&self.context(), ())?.into())
    }

    pub fn clear_audio_cache(&self) -> CoreResult<EvictionRecord> {
        Ok(self.services().library.clear_audio_cache.run(&self.context(), ())?.into())
    }

    /// Stops planning ahead for one book (paused for a minute, sleep timer, book removed). Returns the jobs dropped.
    pub fn stop_read_ahead(&self, document_id: String) -> CoreResult<u32> {
        let dropped = self.services().speech()?.stop_read_ahead.run(&self.context(), DocumentId::parse(&document_id)?)?;
        Ok(u32::try_from(dropped).unwrap_or(u32::MAX))
    }

    pub fn stop_all_read_ahead(&self) -> CoreResult<u32> {
        let dropped = self.services().speech()?.stop_all_read_ahead.run(&self.context(), ())?;
        Ok(u32::try_from(dropped).unwrap_or(u32::MAX))
    }

    /// The governor's Full (the setting) or Reduced (2) listening-minutes.
    pub fn set_read_ahead_minutes(&self, minutes: u32) -> CoreResult<()> {
        Ok(self.services().speech()?.set_read_ahead_minutes.run(&self.context(), minutes)?)
    }

    /// The fixed sample sentence in this voice, made on demand and kept on disk.
    pub fn speak_voice_sample(&self, voice_id: String) -> CoreResult<SpokenAudio> {
        let spoken = self.services().speech()?.voice_sample.run(&self.context(), VoiceId::new(voice_id))?;
        spoken_audio(spoken.bytes, spoken.ready_ahead_ms)
    }

    pub fn speech_stats(&self) -> CoreResult<SpeechStatsRecord> {
        Ok(self.services().speech()?.stats.run(&self.context(), ())?.into())
    }
}

/// Exports (file 10). The runner is a core thread; Kotlin polls `list_all_exports` while a job runs.
#[uniffi::export]
impl LectriceCore {
    /// Hands over Kotlin's AAC encoder; exports are refused until it's set.
    pub fn set_encoder_sink(&self, sink: Arc<dyn AudioEncoderSink>) {
        self.storage.exporter.set(Arc::new(AndroidExporter::new(sink, Path::new(&self.config.data_dir))));
    }

    pub fn start_export(&self, request: ExportRequest) -> CoreResult<AudioExportRecord> {
        let args = StartAudioExportArgs {
            document_id: DocumentId::parse(&request.document_id)?,
            voice_id: VoiceId::new(request.voice_id),
            format: ExportFormat::M4b,
            read_skipped: request.read_skipped,
            speed: ExportSpeed::from_percent(request.speed_percent)?,
            engine_id: request.engine_id,
            range: export_range(request.range_from, request.range_to),
            range_label: request.range_label,
        };
        Ok(self.services().exports()?.start.run(&self.context(), args)?.into())
    }

    pub fn estimate_export(&self, request: ExportRequest) -> CoreResult<ExportEstimateRecord> {
        let args = EstimateAudioExportArgs {
            document_id: DocumentId::parse(&request.document_id)?,
            voice_id: VoiceId::new(request.voice_id),
            engine_id: request.engine_id,
            read_skipped: request.read_skipped,
            speed: ExportSpeed::from_percent(request.speed_percent)?,
            range: export_range(request.range_from, request.range_to),
        };
        Ok(self.services().exports()?.estimate.run(&self.context(), args)?.into())
    }

    /// Every export of every book (the storage screen and the work service); works without an engine.
    pub fn list_all_exports(&self) -> CoreResult<Vec<AudioExportRecord>> {
        Ok(self.services().library.list_all_exports.run(&self.context(), ())?.into_iter().map(Into::into).collect())
    }

    pub fn pause_export(&self, export_id: String, reason: PauseReasonRecord) -> CoreResult<AudioExportRecord> {
        let args = PauseAudioExportArgs { id: ExportId::new(export_id), reason: pause_reason(reason) };
        Ok(self.services().exports()?.pause.run(&self.context(), args)?.into())
    }

    /// Pauses every running export (thermal critical, battery, the time limit).
    pub fn pause_all_exports(&self, reason: PauseReasonRecord) -> CoreResult<Vec<AudioExportRecord>> {
        Ok(self.services().exports()?.pause_all.run(&self.context(), pause_reason(reason))?.into_iter().map(Into::into).collect())
    }

    /// Continues what the system or an engine switch paused.
    pub fn resume_exports(&self) -> CoreResult<Vec<AudioExportRecord>> {
        Ok(self.services().exports()?.resume.run(&self.context(), ())?.into_iter().map(Into::into).collect())
    }

    /// Continues one export, whatever paused it.
    pub fn resume_export(&self, export_id: String) -> CoreResult<AudioExportRecord> {
        Ok(self.services().exports()?.resume_one.run(&self.context(), ExportId::new(export_id))?.into())
    }

    pub fn cancel_export(&self, export_id: String) -> CoreResult<()> {
        self.services().exports()?.cancel.run(&self.context(), ExportId::new(export_id))?;
        Ok(())
    }

    pub fn delete_export(&self, export_id: String) -> CoreResult<()> {
        Ok(self.services().library.delete_export.run(&self.context(), ExportId::new(export_id))?)
    }

    /// The file moved to shared storage (Save to Audiobooks); `uri` is its MediaStore URI.
    pub fn mark_export_saved(&self, export_id: String, uri: String) -> CoreResult<AudioExportRecord> {
        let args = MarkExportSavedArgs { id: ExportId::new(export_id), uri };
        Ok(self.services().library.mark_export_saved.run(&self.context(), args)?.into())
    }
}

/// Prepare (file 14, core C21): make a range of a book ahead into the audio cache.
#[uniffi::export]
impl LectriceCore {
    pub fn start_prepare(
        &self,
        document_id: String,
        voice_id: String,
        read_skipped: bool,
        from_sentence: u32,
        to_sentence: u32,
    ) -> CoreResult<PrepareStatusRecord> {
        let args = PrepareArgs {
            document_id: DocumentId::parse(&document_id)?,
            voice_id: VoiceId::new(voice_id),
            read_skipped,
            from: SentenceId(from_sentence),
            to: SentenceId(to_sentence),
        };
        Ok(self.services().speech()?.prepare.start(args)?.into())
    }

    /// Stops after the sentence being made; the made clips stay pinned (unplugged, or the engine is needed).
    pub fn stop_prepare(&self, document_id: String) -> CoreResult<()> {
        self.services().speech()?.prepare.stop(&DocumentId::parse(&document_id)?);
        Ok(())
    }

    /// Stops and lifts the pin.
    pub fn cancel_prepare(&self, document_id: String) -> CoreResult<()> {
        Ok(self.services().speech()?.prepare.cancel(&DocumentId::parse(&document_id)?)?)
    }

    pub fn prepare_status(&self, document_id: String) -> CoreResult<PrepareStatusRecord> {
        Ok(self.services().speech()?.prepare.status(&DocumentId::parse(&document_id)?).into())
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PrepareStatusRecord {
    pub ready: u32,
    pub total: u32,
    pub running: bool,
}

impl From<PrepareStatus> for PrepareStatusRecord {
    fn from(status: PrepareStatus) -> Self {
        Self { ready: status.ready, total: status.total, running: status.running }
    }
}

fn export_range(from: Option<u32>, to: Option<u32>) -> Option<ExportRange> {
    Some(ExportRange { from_sentence: SentenceId(from?), to_sentence: SentenceId(to?) })
}

/// Every registry entry, for Kotlin's `ErrorName` mirror test.
#[uniffi::export]
pub fn error_registry() -> Vec<CoreErrorInfo> {
    ErrorName::ALL.iter().map(|name| CoreErrorInfo::from(*name)).collect()
}

/// The shared core's version (its release tag without the `v`).
#[uniffi::export]
pub fn core_version() -> String {
    lectrice_app::CORE_VERSION.to_string()
}

fn spoken_audio(wav: Vec<u8>, ready_ahead_ms: u32) -> CoreResult<SpokenAudio> {
    let audio = WavDecoder.decode(&wav)?;
    let duration_ms = (audio.samples.len() as u64 * MS_PER_SECOND).checked_div(u64::from(audio.sample_rate)).unwrap_or_default();
    Ok(SpokenAudio { wav, duration_ms: u32::try_from(duration_ms).unwrap_or(u32::MAX), ready_ahead_ms })
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

/// Spec 23: play a book from an audiobook file. Kotlin passes a path it can read (`/proc/self/fd/N` for a content URI).
#[uniffi::export]
impl LectriceCore {
    /// What a file is before it is tied to a book (the import flow: which book its map names, if any).
    pub fn inspect_audiobook(&self, path: String) -> CoreResult<AudiobookFileRecord> {
        let started = Instant::now();
        let info = mp4_map::read_audiobook(Path::new(&path))?;
        info!(target: TARGET, has_map = info.map.is_some(), duration_ms = info.duration_ms, chapters = info.chapters.len(), read_ms = elapsed_ms(started), "audiobook inspected");
        Ok(AudiobookFileRecord::from(&info))
    }

    /// The file's timeline in this book's sentences (or chapters). `AudiobookOtherBook` when its map names another book.
    pub fn match_audiobook(&self, path: String, document_id: String) -> CoreResult<AudiobookTimelineRecord> {
        let started = Instant::now();
        let id = DocumentId::parse(&document_id)?;
        let document = self.storage.library.find(&id)?.ok_or_else(|| AppError::new(ErrorName::DocumentNotFound))?;
        let info = mp4_map::read_audiobook(Path::new(&path))?;
        let timeline = match_audiobook(&document, &info).map_err(|AudiobookMismatch::OtherBook(other)| {
            AppError::new(ErrorName::AudiobookOtherBook).with_detail(format!("the map names document {other}"))
        })?;
        info!(target: TARGET, doc_id = %document_id, mode = ?timeline.mode, remapped = timeline.remapped, matched_percent = timeline.matched_percent, sentences = timeline.sentences.len(), chapters = timeline.chapters.len(), file_speed_percent = timeline.file_speed_percent, match_ms = elapsed_ms(started), "audiobook matched");
        Ok(timeline.into())
    }
}
