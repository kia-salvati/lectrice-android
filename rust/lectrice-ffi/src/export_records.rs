//! uniffi records for exports (file 10) and their mappers.

use lectrice_app::exports::{ExportEstimate, PartOffset, TocOffset};
use lectrice_domain::{AudioExport, ExportLocation, ExportStage, ExportStatus, PauseReason};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ExportStatusRecord {
    Queued,
    Running,
    Paused,
    Done,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PauseReasonRecord {
    User,
    System,
    StorageFull,
    EngineSwitch,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AudioExportRecord {
    pub id: String,
    pub document_id: String,
    pub voice_id: String,
    pub speed_percent: u16,
    pub engine_id: String,
    pub range_from: Option<u32>,
    pub range_to: Option<u32>,
    pub range_label: Option<String>,
    pub title: String,
    pub status: ExportStatusRecord,
    pub paused_by: Option<PauseReasonRecord>,
    /// True while joining the parts.
    pub assembling: bool,
    pub error_name: Option<String>,
    pub sentences_done: u32,
    pub sentences_total: u32,
    pub parts_done: u32,
    pub parts_total: u32,
    pub estimated_seconds_left: Option<u32>,
    pub file_bytes: Option<u64>,
    /// The file in app storage while it's there.
    pub file_path: Option<String>,
    /// The shared (MediaStore) URI once saved to Audiobooks.
    pub shared_uri: Option<String>,
    pub waiting_for_engine: bool,
}

impl From<AudioExport> for AudioExportRecord {
    fn from(export: AudioExport) -> Self {
        let (file_path, shared_uri) = match export.location {
            Some(ExportLocation::AppStorage { path }) => (Some(path.display().to_string()), None),
            Some(ExportLocation::Shared { uri }) => (None, Some(uri)),
            None => (None, None),
        };
        Self {
            id: export.id.as_str().to_string(),
            document_id: export.document_id.to_string(),
            voice_id: export.voice_id.to_string(),
            speed_percent: export.speed.percent(),
            engine_id: export.engine_id,
            range_from: export.range.map(|range| range.from_sentence.0),
            range_to: export.range.map(|range| range.to_sentence.0),
            range_label: export.range_label,
            title: export.title,
            status: match export.status {
                ExportStatus::Queued => ExportStatusRecord::Queued,
                ExportStatus::Running => ExportStatusRecord::Running,
                ExportStatus::Paused => ExportStatusRecord::Paused,
                ExportStatus::Done => ExportStatusRecord::Done,
                ExportStatus::Failed => ExportStatusRecord::Failed,
                ExportStatus::Cancelled => ExportStatusRecord::Cancelled,
            },
            paused_by: export.paused_by.map(|reason| match reason {
                PauseReason::User => PauseReasonRecord::User,
                PauseReason::System => PauseReasonRecord::System,
                PauseReason::StorageFull => PauseReasonRecord::StorageFull,
                PauseReason::EngineSwitch => PauseReasonRecord::EngineSwitch,
            }),
            assembling: export.stage == ExportStage::Assemble,
            error_name: export.error.map(|name| name.as_str().to_string()),
            sentences_done: export.sentences_done,
            sentences_total: export.sentences_total,
            parts_done: export.parts_done,
            parts_total: export.parts_total,
            estimated_seconds_left: export.estimated_seconds_left,
            file_bytes: export.file_bytes,
            file_path,
            shared_uri,
            waiting_for_engine: export.waiting_for_engine,
        }
    }
}

pub fn pause_reason(reason: PauseReasonRecord) -> PauseReason {
    match reason {
        PauseReasonRecord::User => PauseReason::User,
        PauseReasonRecord::System => PauseReason::System,
        PauseReasonRecord::StorageFull => PauseReason::StorageFull,
        PauseReasonRecord::EngineSwitch => PauseReason::EngineSwitch,
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TocOffsetRecord {
    pub toc_index: u32,
    pub first_sentence: u32,
    pub audio_ms_1x_before: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PartOffsetRecord {
    pub first_sentence: u32,
    pub page_label: String,
    pub audio_ms_1x_before: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ExportEstimateRecord {
    pub sentences: u32,
    pub parts: u32,
    pub audio_ms_1x: u64,
    pub output_ms: u64,
    pub cached_ms_1x: u64,
    pub total_audio_ms_1x: u64,
    pub toc_offsets: Vec<TocOffsetRecord>,
    pub part_offsets: Vec<PartOffsetRecord>,
}

impl From<ExportEstimate> for ExportEstimateRecord {
    fn from(estimate: ExportEstimate) -> Self {
        Self {
            sentences: estimate.sentences,
            parts: estimate.parts,
            audio_ms_1x: estimate.audio_ms_1x,
            output_ms: estimate.output_ms,
            cached_ms_1x: estimate.cached_ms_1x,
            total_audio_ms_1x: estimate.total_audio_ms_1x,
            toc_offsets: estimate
                .toc_offsets
                .into_iter()
                .map(|TocOffset { toc_index, first_sentence, audio_ms_1x_before }| TocOffsetRecord {
                    toc_index,
                    first_sentence: first_sentence.0,
                    audio_ms_1x_before,
                })
                .collect(),
            part_offsets: estimate
                .part_offsets
                .into_iter()
                .map(|PartOffset { first_sentence, page_label, audio_ms_1x_before }| PartOffsetRecord {
                    first_sentence: first_sentence.0,
                    page_label,
                    audio_ms_1x_before,
                })
                .collect(),
        }
    }
}

/// What to export (file 10 §3.2): M4B only on Android.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ExportRequest {
    pub document_id: String,
    pub voice_id: String,
    pub read_skipped: bool,
    pub speed_percent: u16,
    /// `None` = the loaded engine.
    pub engine_id: Option<String>,
    pub range_from: Option<u32>,
    pub range_to: Option<u32>,
    pub range_label: Option<String>,
}
