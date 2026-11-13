//! The Android `AudioExporter`: each part is encoded to AAC by Kotlin (MediaCodec) as it is made, so parts take the
//! space of the final file (file 10 §3.11); assembling joins them into an M4B with chapter marks (MediaMuxer + `chpl`).
//! Parts live in `<data_dir>/exports/parts/<exportId>/part-NNNN.aac`, the result in `<data_dir>/exports/<docId>/`.

use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use lectrice_domain::{
    AppError, AppResult, Audio, AudioExporter, ChapterMark, ErrorName, ExportId, ExportPart, ExportTarget, ExportWriter, ExportedFile, LectriceMap,
};

use crate::errors::CoreError;

const EXPORTS_DIR: &str = "exports";
const PARTS_DIR: &str = "parts";
const PART_EXTENSION: &str = "aac";
const PARTIAL_PREFIX: &str = ".partial-";
const PCM16_MAX: f32 = 32767.0;

/// A chapter mark as Kotlin writes it into the file.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ChapterMarkRecord {
    pub title: String,
    pub start_ms: u64,
}

/// Kotlin's MediaCodec AAC encoder and MP4 writer. Errors come back as `CoreError` (`StorageFull` when the disk is full).
#[uniffi::export(with_foreign)]
pub trait AudioEncoderSink: Send + Sync {
    /// Starts encoding mono 16-bit PCM at `sample_rate` into `path`; returns a handle for the next calls.
    fn open_part(&self, path: String, sample_rate: u32) -> Result<u64, CoreError>;
    /// 16-bit little-endian mono PCM (bytes: a `ByteArray` in Kotlin, no boxing).
    fn write_pcm(&self, handle: u64, pcm: Vec<u8>) -> Result<(), CoreError>;
    /// Flushes the encoder and closes the file.
    fn close_part(&self, handle: u64) -> Result<(), CoreError>;
    /// Drops an unfinished part (its file is deleted by the core).
    fn abandon_part(&self, handle: u64);
    /// Joins the parts (in order) into an M4B at `output_path` with the tags and chapter marks.
    fn assemble(&self, parts: Vec<String>, output_path: String, title: String, artist: String, chapters: Vec<ChapterMarkRecord>) -> Result<(), CoreError>;
}

pub struct AndroidExporter {
    sink: Arc<dyn AudioEncoderSink>,
    exports_dir: PathBuf,
}

impl AndroidExporter {
    pub fn new(sink: Arc<dyn AudioEncoderSink>, data_dir: &Path) -> Self {
        Self { sink, exports_dir: data_dir.join(EXPORTS_DIR) }
    }

    fn parts_dir(&self, export_id: &ExportId) -> PathBuf {
        self.exports_dir.join(PARTS_DIR).join(safe(export_id.as_str()))
    }
}

impl AudioExporter for AndroidExporter {
    fn check_available(&self) -> AppResult<()> {
        Ok(())
    }

    fn begin_part(&self, target: &ExportTarget, part_index: u32, sample_rate: u32) -> AppResult<Box<dyn ExportWriter>> {
        let directory = self.parts_dir(&target.export_id);
        fs::create_dir_all(&directory).map_err(|error| storage_error(error, "creating the parts folder"))?;
        let name = format!("part-{part_index:04}.{PART_EXTENSION}");
        let partial = directory.join(format!("{PARTIAL_PREFIX}{name}"));
        let handle = self.sink.open_part(partial.display().to_string(), sample_rate).map_err(app_error)?;
        Ok(Box::new(AacPartWriter {
            sink: self.sink.clone(),
            handle,
            index: part_index,
            partial,
            path: directory.join(name),
            sample_rate,
            samples: 0,
            finished: false,
        }))
    }

    fn assemble(&self, target: &ExportTarget, parts: &[ExportPart], chapters: &[ChapterMark]) -> AppResult<ExportedFile> {
        let audible: Vec<String> = parts.iter().filter(|part| part.samples > 0).map(|part| part.path.display().to_string()).collect();
        if audible.is_empty() {
            return Err(AppError::new(ErrorName::Internal).with_detail("the export has no audio to join"));
        }
        let directory = self.exports_dir.join(safe(target.document_id.as_str()));
        fs::create_dir_all(&directory).map_err(|error| storage_error(error, "creating the export folder"))?;
        let file_name = format!("{}.{}", safe(&target.file_stem), target.format.extension());
        let partial = directory.join(format!("{PARTIAL_PREFIX}{file_name}"));
        let marks = chapters.iter().map(|mark| ChapterMarkRecord { title: mark.title.clone(), start_ms: mark.start_ms }).collect();
        self.sink.assemble(audible, partial.display().to_string(), target.title.clone(), target.artist.clone(), marks).map_err(app_error)?;
        let path = directory.join(file_name);
        fs::rename(&partial, &path).map_err(|error| storage_error(error, "renaming the export"))?;
        let size_bytes = fs::metadata(&path).map_err(|error| storage_error(error, "reading the export"))?.len();
        Ok(ExportedFile { path, size_bytes })
    }

    fn discard_parts(&self, export_id: &ExportId) -> AppResult<()> {
        match fs::remove_dir_all(self.parts_dir(export_id)) {
            Err(error) if error.kind() != io::ErrorKind::NotFound => Err(storage_error(error, "deleting parts")),
            _ => Ok(()),
        }
    }

    fn parts_bytes(&self) -> AppResult<u64> {
        let root = self.exports_dir.join(PARTS_DIR);
        let Ok(folders) = fs::read_dir(&root) else { return Ok(0) };
        let mut bytes = 0;
        for folder in folders.flatten() {
            let Ok(files) = fs::read_dir(folder.path()) else { continue };
            bytes += files.flatten().filter_map(|file| file.metadata().ok()).filter(fs::Metadata::is_file).map(|metadata| metadata.len()).sum::<u64>();
        }
        Ok(bytes)
    }

    fn delete_file(&self, path: &Path) -> AppResult<()> {
        if path.components().any(|part| part == Component::ParentDir) || !path.starts_with(&self.exports_dir) {
            return Err(AppError::new(ErrorName::Internal).with_detail("not an export file"));
        }
        match fs::remove_file(path) {
            Err(error) if error.kind() != io::ErrorKind::NotFound => Err(storage_error(error, "deleting an export")),
            _ => Ok(()),
        }
    }

    /// Spec 23 §3.1: the Lectrice map after the moov Kotlin wrote, so the phone can play the file with the text.
    fn attach_map(&self, file: &ExportedFile, map: &LectriceMap) -> AppResult<u64> {
        lectrice_infra::export::mp4_map::append_map(&file.path, map)?;
        let size = fs::metadata(&file.path).map_err(|error| storage_error(error, "reading the export"))?.len();
        Ok(size.saturating_sub(file.size_bytes))
    }
}

/// Dropped without `finish`: the part is abandoned and its partial file deleted.
struct AacPartWriter {
    sink: Arc<dyn AudioEncoderSink>,
    handle: u64,
    index: u32,
    partial: PathBuf,
    path: PathBuf,
    sample_rate: u32,
    samples: u64,
    finished: bool,
}

impl ExportWriter for AacPartWriter {
    fn append(&mut self, audio: &Audio) -> AppResult<()> {
        if audio.sample_rate != self.sample_rate {
            return Err(AppError::new(ErrorName::Internal).with_detail("a clip has another sample rate"));
        }
        // `as` saturates for floats: values outside -1..1 clip instead of wrapping.
        let pcm: Vec<u8> = audio.samples.iter().flat_map(|&sample| ((sample.clamp(-1.0, 1.0) * PCM16_MAX) as i16).to_le_bytes()).collect();
        self.sink.write_pcm(self.handle, pcm).map_err(app_error)?;
        self.samples += audio.samples.len() as u64;
        Ok(())
    }

    fn finish(mut self: Box<Self>) -> AppResult<ExportPart> {
        self.finished = true;
        self.sink.close_part(self.handle).map_err(app_error)?;
        fs::rename(&self.partial, &self.path).map_err(|error| storage_error(error, "renaming a part"))?;
        Ok(ExportPart { index: self.index, path: self.path.clone(), samples: self.samples, sample_rate: self.sample_rate })
    }
}

impl Drop for AacPartWriter {
    fn drop(&mut self) {
        if !self.finished {
            self.sink.abandon_part(self.handle);
            let _ = fs::remove_file(&self.partial);
        }
    }
}

/// One path component, nothing that can climb out of the folder.
fn safe(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') { c } else { '_' })
        .collect::<String>()
        .trim_start_matches('.')
        .to_string()
}

fn app_error(error: CoreError) -> AppError {
    let CoreError::Failed { name, .. } = &error;
    let name = lectrice_domain::ErrorName::ALL.iter().copied().find(|candidate| candidate.as_str() == name).unwrap_or(ErrorName::Internal);
    let error = AppError::new(name).with_detail("the Android encoder failed");
    if name == ErrorName::StorageFull { error.and(ErrorName::StorageFailed) } else { error }
}

fn storage_error(error: io::Error, what: &str) -> AppError {
    let name = if error.raw_os_error() == Some(NO_SPACE) { ErrorName::StorageFull } else { ErrorName::StorageFailed };
    AppError::new(name).with_detail(what.to_string()).with_source(error)
}

/// `ENOSPC` on Linux/Android.
const NO_SPACE: i32 = 28;
