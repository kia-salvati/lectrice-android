use std::path::Path;
use std::sync::{Arc, RwLock};

use lectrice_domain::{
    AppError, AppResult, AudioExporter, ChapterMark, ErrorName, ExportId, ExportPart, ExportTarget, ExportWriter, ExportedFile, LectriceMap,
};

/// The exporter the core is assembled with. Empty until Kotlin hands over its encoder (`set_encoder_sink`): until then
/// every export is refused up front, as in a build without an encoder (the spike).
#[derive(Default)]
pub struct ExporterSlot {
    inner: RwLock<Option<Arc<dyn AudioExporter>>>,
}

impl ExporterSlot {
    pub fn set(&self, exporter: Arc<dyn AudioExporter>) {
        *self.inner.write().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(exporter);
    }

    fn get(&self) -> Option<Arc<dyn AudioExporter>> {
        self.inner.read().unwrap_or_else(|poisoned| poisoned.into_inner()).clone()
    }
}

fn unavailable() -> AppError {
    AppError::new(ErrorName::ExporterUnavailable).with_detail("no audio encoder was set")
}

impl AudioExporter for ExporterSlot {
    fn check_available(&self) -> AppResult<()> {
        self.get().ok_or_else(unavailable)?.check_available()
    }

    fn begin_part(&self, target: &ExportTarget, part_index: u32, sample_rate: u32) -> AppResult<Box<dyn ExportWriter>> {
        self.get().ok_or_else(unavailable)?.begin_part(target, part_index, sample_rate)
    }

    fn assemble(&self, target: &ExportTarget, parts: &[ExportPart], chapters: &[ChapterMark]) -> AppResult<ExportedFile> {
        self.get().ok_or_else(unavailable)?.assemble(target, parts, chapters)
    }

    fn discard_parts(&self, export_id: &ExportId) -> AppResult<()> {
        self.get().map_or(Ok(()), |exporter| exporter.discard_parts(export_id))
    }

    fn parts_bytes(&self) -> AppResult<u64> {
        self.get().map_or(Ok(0), |exporter| exporter.parts_bytes())
    }

    fn delete_file(&self, path: &Path) -> AppResult<()> {
        self.get().map_or(Ok(()), |exporter| exporter.delete_file(path))
    }

    fn attach_map(&self, file: &ExportedFile, map: &LectriceMap) -> AppResult<u64> {
        self.get().map_or(Ok(0), |exporter| exporter.attach_map(file, map))
    }
}
