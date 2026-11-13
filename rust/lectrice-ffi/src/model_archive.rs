//! Unpacks a voice model archive (`.tar.bz2`) the app downloaded and checked (its SHA-256 is checked in Kotlin while it
//! downloads). Only the kept files are written; an entry that is a link or whose path leaves the archive refuses the whole
//! archive.

use std::fs::{self, File};
use std::io::{self, BufReader};
use std::path::{Component, Path, PathBuf};

use bzip2::read::BzDecoder;
use lectrice_domain::{AppError, ErrorName};
use tar::{Archive, EntryType};
use tracing::info;

use crate::errors::CoreResult;

const TARGET: &str = "lectrice_ffi";
/// `ENOSPC` on Linux/Android.
const NO_SPACE: i32 = 28;

/// Writes the entries under `archive_root/` whose first path part is in `keep` into `target_dir`; returns the bytes written.
#[uniffi::export]
pub fn unpack_model_archive(archive_path: String, archive_root: String, keep: Vec<String>, target_dir: String) -> CoreResult<u64> {
    let file = File::open(&archive_path).map_err(|error| AppError::new(ErrorName::FileUnreadable).with_source(error))?;
    let mut archive = Archive::new(BzDecoder::new(BufReader::new(file)));
    let target = PathBuf::from(target_dir);
    let mut written = 0u64;
    let mut files = 0u32;
    for entry in archive.entries().map_err(not_a_model)? {
        let mut entry = entry.map_err(not_a_model)?;
        let kind = entry.header().entry_type();
        if kind.is_symlink() || kind.is_hard_link() {
            return Err(AppError::new(ErrorName::ModelChecksumMismatch).with_detail("the model archive has a link").into());
        }
        let path = entry.path().map_err(not_a_model)?.into_owned();
        let Some(relative) = inside_root(&path, &archive_root)? else { continue };
        if kind != EntryType::Regular || !kept(&relative, &keep) {
            continue;
        }
        let output_path = target.join(&relative);
        if let Some(parent) = output_path.parent() {
            fs::create_dir_all(parent).map_err(write_error)?;
        }
        let mut output = File::create(&output_path).map_err(write_error)?;
        written += io::copy(&mut entry, &mut output).map_err(write_error)?;
        output.sync_all().map_err(write_error)?;
        files += 1;
    }
    info!(target: TARGET, files, bytes = written, "model archive unpacked");
    Ok(written)
}

/// The path below `root/`; `None` for entries outside it. `..` or an absolute path refuses the archive.
fn inside_root(path: &Path, root: &str) -> CoreResult<Option<PathBuf>> {
    let mut components = path.components();
    if path.components().any(|part| matches!(part, Component::ParentDir | Component::RootDir | Component::Prefix(_))) {
        return Err(AppError::new(ErrorName::ModelChecksumMismatch).with_detail("the model archive has an unsafe path").into());
    }
    match components.next() {
        Some(Component::Normal(first)) if first == root => Ok(Some(components.as_path().to_path_buf()).filter(|rest| !rest.as_os_str().is_empty())),
        _ => Ok(None),
    }
}

fn kept(relative: &Path, keep: &[String]) -> bool {
    relative.components().next().is_some_and(|first| keep.iter().any(|name| first.as_os_str() == name.as_str()))
}

fn not_a_model(error: io::Error) -> crate::errors::CoreError {
    AppError::new(ErrorName::ModelChecksumMismatch).with_detail("the model archive can't be read").with_source(error).into()
}

fn write_error(error: io::Error) -> crate::errors::CoreError {
    let name = if error.raw_os_error() == Some(NO_SPACE) { ErrorName::StorageFull } else { ErrorName::StorageFailed };
    AppError::new(name).with_source(error).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn archive(dir: &Path, entries: &[(&str, &[u8])]) -> PathBuf {
        let path = dir.join("model.tar.bz2");
        let encoder = bzip2::write::BzEncoder::new(File::create(&path).unwrap(), bzip2::Compression::fast());
        let mut builder = tar::Builder::new(encoder);
        for (name, data) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, name, *data).unwrap();
        }
        builder.into_inner().unwrap().finish().unwrap();
        path
    }

    #[test]
    fn unpacks_only_the_kept_entries_under_the_root() {
        let dir = tempfile::tempdir().unwrap();
        let path =
            archive(dir.path(), &[("bundle/model.onnx", b"m"), ("bundle/espeak-ng-data/phontab", b"p"), ("bundle/lexicon-zh.txt", b"z"), ("other/x", b"x")]);
        let target = dir.path().join("out");
        let keep = vec!["model.onnx".to_string(), "espeak-ng-data".to_string()];

        let written = unpack_model_archive(path.display().to_string(), "bundle".into(), keep, target.display().to_string()).unwrap();

        assert_eq!(written, 2);
        assert!(target.join("model.onnx").is_file());
        assert!(target.join("espeak-ng-data/phontab").is_file());
        assert!(!target.join("lexicon-zh.txt").exists());
    }

    #[test]
    fn a_damaged_archive_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("broken.tar.bz2");
        fs::write(&path, b"not bzip2").unwrap();

        let result = unpack_model_archive(path.display().to_string(), "bundle".into(), vec![], dir.path().join("out").display().to_string());

        assert!(result.is_err());
    }
}
