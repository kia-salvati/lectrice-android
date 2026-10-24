use std::path::Path;

use lectrice_domain::{AppError, AppResult, AudioExporter, ChapterMark, ErrorName, ExportId, ExportPart, ExportTarget, ExportWriter, ExportedFile};

/// The spike has no exporter (the MediaCodec sink comes in M2): every export is refused up front.
pub struct NoExporter;

fn unavailable() -> AppError {
    AppError::new(ErrorName::ExporterUnavailable).with_detail("this build has no audio exporter")
}

impl AudioExporter for NoExporter {
    fn check_available(&self) -> AppResult<()> {
        Err(unavailable())
    }

    fn begin_part(&self, _target: &ExportTarget, _part_index: u32, _sample_rate: u32) -> AppResult<Box<dyn ExportWriter>> {
        Err(unavailable())
    }

    fn assemble(&self, _target: &ExportTarget, _parts: &[ExportPart], _chapters: &[ChapterMark]) -> AppResult<ExportedFile> {
        Err(unavailable())
    }

    fn discard_parts(&self, _export_id: &ExportId) -> AppResult<()> {
        Ok(())
    }

    fn parts_bytes(&self) -> AppResult<u64> {
        Ok(0)
    }

    fn delete_file(&self, _path: &Path) -> AppResult<()> {
        Ok(())
    }
}
