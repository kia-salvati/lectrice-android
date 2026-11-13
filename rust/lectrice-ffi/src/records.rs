//! uniffi records (camelCase in Kotlin) and their mappers from the core's types.

use lectrice_app::documents::ImportedPdf;
use lectrice_app::library::{PurgeReport, StorageUsage};
use lectrice_app::speech::{SpeechStats, VoiceList as CoreVoiceList};
use lectrice_domain::{self as domain, AudioEviction, Document, DocumentSummary, Page, SentenceId};
use lectrice_infra::KokoroVariant as CoreKokoroVariant;

use crate::errors::CoreErrorInfo;

#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreConfig {
    /// `context.filesDir + "/lectrice"`: library and export records.
    pub data_dir: String,
    /// `context.cacheDir + "/lectrice"`: sentence audio and voice samples.
    pub cache_dir: String,
    /// `"libpdfium.so"` lets the system linker find it in the app's native lib dir.
    pub pdfium_library: String,
    pub default_voice: String,
    pub read_ahead_minutes: u32,
    pub keep_minutes_per_document: u32,
    pub cache_max_mb: u64,
    pub import_max_mb: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KokoroVariant {
    Fp32,
    Int8,
}

impl From<KokoroVariant> for CoreKokoroVariant {
    fn from(variant: KokoroVariant) -> Self {
        match variant {
            KokoroVariant::Fp32 => Self::Fp32,
            KokoroVariant::Int8 => Self::Int8,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EngineConfig {
    /// `provider`: "cpu" (default), "xnnpack" or "nnapi". `engine_id`: the model bundle's own id when several bundles
    /// share a model file slot (our Fast and Small use `model.int8.onnx`), so their clips and exports never mix; `None`
    /// keeps the core's id for the slot.
    Kokoro { model_dir: String, variant: KokoroVariant, num_threads: u32, provider: String, engine_id: Option<String> },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EngineStatus {
    Ready {
        engine_id: String,
        load_ms: u64,
    },
    Missing,
    /// Unloaded to save memory; the model is still installed.
    Unloaded,
    Failed {
        error: CoreErrorInfo,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocumentSummaryRecord {
    pub id: String,
    pub title: String,
    pub source_file_name: String,
    pub page_count: u32,
    pub sentence_count: u32,
    pub cleaner_version: u32,
    pub added_at_epoch_s: i64,
    pub last_opened_at_epoch_s: i64,
    pub last_sentence_id: Option<u32>,
}

impl From<DocumentSummary> for DocumentSummaryRecord {
    fn from(summary: DocumentSummary) -> Self {
        Self {
            id: summary.id.to_string(),
            title: summary.title,
            source_file_name: summary.source_file_name,
            page_count: summary.page_count,
            sentence_count: summary.sentence_count,
            cleaner_version: summary.cleaner_version,
            added_at_epoch_s: summary.added_at.timestamp(),
            last_opened_at_epoch_s: summary.last_opened_at.timestamp(),
            last_sentence_id: summary.last_sentence_id.map(|id| id.0),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImportResult {
    pub summary: DocumentSummaryRecord,
    pub already_in_library: bool,
    pub duration_ms: u64,
}

impl ImportResult {
    pub fn new(imported: ImportedPdf, duration_ms: u64) -> Self {
        Self { summary: imported.summary.into(), already_in_library: imported.already_in_library, duration_ms }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocumentRecord {
    pub id: String,
    pub title: String,
    pub page_count: u32,
    pub cleaner_version: u32,
    pub pages: Vec<PageLabelRecord>,
    pub toc: Vec<TocEntryRecord>,
    pub blocks: Vec<BlockRecord>,
    pub last_sentence_id: Option<u32>,
}

impl DocumentRecord {
    pub fn new(document: Document, last_sentence_id: Option<SentenceId>) -> Self {
        Self {
            id: document.id.to_string(),
            title: document.title,
            page_count: document.page_count,
            cleaner_version: document.cleaner_version,
            pages: document.pages.into_iter().map(|page| PageLabelRecord { index: page.index, label: page.label }).collect(),
            toc: document.toc.into_iter().map(TocEntryRecord::from).collect(),
            blocks: document.blocks.into_iter().map(BlockRecord::from).collect(),
            last_sentence_id: last_sentence_id.map(|id| id.0),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PageLabelRecord {
    pub index: u32,
    pub label: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TocEntryRecord {
    pub id: u32,
    pub title: String,
    pub level: u8,
    pub sentence_id: u32,
    pub page_index: u32,
}

impl From<domain::TocEntry> for TocEntryRecord {
    fn from(entry: domain::TocEntry) -> Self {
        Self { id: entry.id, title: entry.title, level: entry.level, sentence_id: entry.sentence_id.0, page_index: entry.page_index }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BlockKind {
    Heading,
    Paragraph,
    ListItem,
    Note,
    Code,
    Table,
    Caption,
}

impl From<domain::BlockKind> for BlockKind {
    fn from(kind: domain::BlockKind) -> Self {
        match kind {
            domain::BlockKind::Heading => Self::Heading,
            domain::BlockKind::Paragraph => Self::Paragraph,
            domain::BlockKind::ListItem => Self::ListItem,
            domain::BlockKind::Note => Self::Note,
            domain::BlockKind::Code => Self::Code,
            domain::BlockKind::Table => Self::Table,
            domain::BlockKind::Caption => Self::Caption,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct BlockRecord {
    pub id: u32,
    pub kind: BlockKind,
    pub level: Option<u8>,
    pub page_index: u32,
    pub spoken: bool,
    pub text: Option<String>,
    pub sentences: Vec<SentenceRecord>,
    pub cue: Option<SentenceRecord>,
}

impl From<domain::Block> for BlockRecord {
    fn from(block: domain::Block) -> Self {
        Self {
            id: block.id,
            kind: block.kind.into(),
            level: block.level,
            page_index: block.page_index,
            spoken: block.spoken,
            text: block.text,
            sentences: block.sentences.into_iter().map(SentenceRecord::from).collect(),
            cue: block.cue.map(SentenceRecord::from),
        }
    }
}

/// The shown text only; the spoken text stays in Rust.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SentenceRecord {
    pub id: u32,
    pub text: String,
    pub page_index: u32,
}

impl From<domain::Sentence> for SentenceRecord {
    fn from(sentence: domain::Sentence) -> Self {
        Self { id: sentence.id.0, text: sentence.text, page_index: sentence.page_index }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VoiceLanguage {
    EnUs,
    EnGb,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VoiceGender {
    Female,
    Male,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct VoiceRecord {
    pub id: String,
    pub name: String,
    pub language: VoiceLanguage,
    pub gender: VoiceGender,
}

impl From<domain::Voice> for VoiceRecord {
    fn from(voice: domain::Voice) -> Self {
        let language = match voice.language {
            domain::VoiceLanguage::EnUs => VoiceLanguage::EnUs,
            domain::VoiceLanguage::EnGb => VoiceLanguage::EnGb,
        };
        let gender = match voice.gender {
            domain::VoiceGender::Female => VoiceGender::Female,
            domain::VoiceGender::Male => VoiceGender::Male,
        };
        Self { id: voice.id.to_string(), name: voice.name, language, gender }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct VoiceList {
    pub voices: Vec<VoiceRecord>,
    pub default_voice_id: String,
}

impl From<CoreVoiceList> for VoiceList {
    fn from(list: CoreVoiceList) -> Self {
        Self { voices: list.voices.into_iter().map(VoiceRecord::from).collect(), default_voice_id: list.default_voice_id.to_string() }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeakArgs {
    pub document_id: String,
    pub sentence_id: u32,
    pub voice_id: String,
    /// The listener's speed; audio is always made at 1x, the speed sizes the read-ahead.
    pub rate: f32,
    pub read_skipped: bool,
    /// False while the read-ahead governor is Off, and for the player's own preloads (spec 08 §3.3).
    pub plan_ahead: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpokenAudio {
    /// 16-bit mono WAV.
    pub wav: Vec<u8>,
    pub duration_ms: u32,
    /// Estimated 1x audio already cached right after this sentence.
    pub ready_ahead_ms: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SummaryPage {
    pub items: Vec<DocumentSummaryRecord>,
    pub total: u32,
    pub limit: u32,
    pub offset: u32,
}

impl From<Page<DocumentSummary>> for SummaryPage {
    fn from(page: Page<DocumentSummary>) -> Self {
        Self {
            items: page.items.into_iter().map(DocumentSummaryRecord::from).collect(),
            total: to_u32(page.total),
            limit: to_u32(page.limit),
            offset: to_u32(page.offset),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PurgeReportRecord {
    pub documents: u32,
    pub failed: u32,
    pub exports_cancelled: u32,
    pub exports_deleted: u32,
    pub clips: u32,
}

impl From<PurgeReport> for PurgeReportRecord {
    fn from(report: PurgeReport) -> Self {
        Self {
            documents: to_u32(report.documents),
            failed: to_u32(report.failed),
            exports_cancelled: to_u32(report.exports_cancelled),
            exports_deleted: to_u32(report.exports_deleted),
            clips: to_u32(report.clips),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocumentUsageRecord {
    pub document_id: String,
    pub library_bytes: u64,
    pub cache_bytes: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StorageUsageRecord {
    pub library_documents: u32,
    pub library_bytes: u64,
    pub cache_bytes: u64,
    pub cache_cap_bytes: u64,
    pub export_files: u32,
    pub export_bytes: u64,
    pub export_parts_bytes: u64,
    /// Largest total first.
    pub documents: Vec<DocumentUsageRecord>,
}

impl From<StorageUsage> for StorageUsageRecord {
    fn from(usage: StorageUsage) -> Self {
        Self {
            library_documents: usage.library_documents,
            library_bytes: usage.library_bytes,
            cache_bytes: usage.cache_bytes,
            cache_cap_bytes: usage.cache_cap_bytes,
            export_files: usage.export_files,
            export_bytes: usage.export_bytes,
            export_parts_bytes: usage.export_parts_bytes,
            documents: usage
                .documents
                .into_iter()
                .map(|document| DocumentUsageRecord {
                    document_id: document.document_id.to_string(),
                    library_bytes: document.library_bytes,
                    cache_bytes: document.cache_bytes,
                })
                .collect(),
        }
    }
}

/// What a clear freed.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EvictionRecord {
    pub clips: u32,
    pub freed_bytes: u64,
}

impl From<AudioEviction> for EvictionRecord {
    fn from(eviction: AudioEviction) -> Self {
        Self { clips: to_u32(eviction.window_clips.saturating_add(eviction.cap_clips)), freed_bytes: eviction.freed_bytes }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeechStatsRecord {
    pub engine_id: String,
    /// Median real-time factor of the last 50 sentences; `None` below 10 measured sentences.
    pub rtf_median: Option<f32>,
    pub samples: u32,
    pub engine_load_ms: Option<u32>,
}

impl From<SpeechStats> for SpeechStatsRecord {
    fn from(stats: SpeechStats) -> Self {
        Self { engine_id: stats.engine_id, rtf_median: stats.rtf_median, samples: stats.samples, engine_load_ms: stats.engine_load_ms }
    }
}

fn to_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}
