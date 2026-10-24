//! uniffi records (camelCase in Kotlin) and their mappers from the core's types.

use lectrice_app::documents::ImportedPdf;
use lectrice_app::speech::VoiceList as CoreVoiceList;
use lectrice_domain::{self as domain, Document, DocumentSummary, SentenceId};
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
    /// `provider`: "cpu" (default), "xnnpack" or "nnapi".
    Kokoro { model_dir: String, variant: KokoroVariant, num_threads: u32, provider: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EngineStatus {
    Ready { engine_id: String, load_ms: u64 },
    Missing,
    Failed { error: CoreErrorInfo },
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
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpokenAudio {
    /// 16-bit mono WAV.
    pub wav: Vec<u8>,
    pub duration_ms: u32,
    /// Estimated 1x audio already cached right after this sentence.
    pub ready_ahead_ms: u32,
}
