//! Spec 23 (play from an audiobook file): what Kotlin gets from `inspect_audiobook` and `match_audiobook`.

use lectrice_domain::{AudiobookInfo, AudiobookMode, AudiobookTimeline};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AudiobookModeRecord {
    Sentences,
    Chapters,
    Unaligned,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TimedSentenceRecord {
    pub sentence_id: u32,
    pub start_ms: u64,
    pub end_ms: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TimedChapterRecord {
    pub toc_index: u32,
    pub sentence_id: u32,
    pub start_ms: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AudiobookTimelineRecord {
    pub mode: AudiobookModeRecord,
    pub remapped: bool,
    pub matched_percent: u32,
    pub file_speed_percent: u16,
    pub duration_ms: u64,
    pub voice_id: Option<String>,
    pub sentences: Vec<TimedSentenceRecord>,
    pub chapters: Vec<TimedChapterRecord>,
}

impl From<AudiobookTimeline> for AudiobookTimelineRecord {
    fn from(timeline: AudiobookTimeline) -> Self {
        Self {
            mode: match timeline.mode {
                AudiobookMode::Sentences => AudiobookModeRecord::Sentences,
                AudiobookMode::Chapters => AudiobookModeRecord::Chapters,
                AudiobookMode::Unaligned => AudiobookModeRecord::Unaligned,
            },
            remapped: timeline.remapped,
            matched_percent: timeline.matched_percent,
            file_speed_percent: timeline.file_speed_percent,
            duration_ms: timeline.duration_ms,
            voice_id: timeline.voice_id,
            sentences: timeline
                .sentences
                .into_iter()
                .map(|s| TimedSentenceRecord { sentence_id: s.sentence_id.0, start_ms: s.start_ms, end_ms: s.end_ms })
                .collect(),
            chapters: timeline
                .chapters
                .into_iter()
                .map(|c| TimedChapterRecord { toc_index: c.toc_index, sentence_id: c.sentence_id.0, start_ms: c.start_ms })
                .collect(),
        }
    }
}

/// A file before it is tied to a book: which book its map names (if Lectrice made it), its length and chapters.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AudiobookFileRecord {
    pub map_document_id: Option<String>,
    pub duration_ms: u64,
    pub chapter_count: u32,
    pub title: Option<String>,
    pub voice_id: Option<String>,
    pub speed_percent: Option<u16>,
}

impl From<&AudiobookInfo> for AudiobookFileRecord {
    fn from(info: &AudiobookInfo) -> Self {
        Self {
            map_document_id: info.map.as_ref().map(|map| map.document_id.to_string()),
            duration_ms: info.map.as_ref().map_or(info.duration_ms, |map| map.duration_ms.max(info.duration_ms)),
            chapter_count: u32::try_from(info.chapters.len()).unwrap_or(u32::MAX),
            title: info.title.clone(),
            voice_id: info.map.as_ref().map(|map| map.voice_id.clone()),
            speed_percent: info.map.as_ref().map(|map| map.speed_percent),
        }
    }
}
