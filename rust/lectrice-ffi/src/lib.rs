//! The Kotlin API of the Lectrice core (uniffi proc-macros, called through JNA) and the Android composition root.
//! The Android spec §5.2 API, plus model unpacking.

mod android_exporter;
mod api;
mod audiobook_records;
#[cfg(feature = "bench")]
mod bench;
mod composition;
mod errors;
mod export_records;
mod exporter_slot;
mod helper;
mod log_bridge;
mod model_archive;
mod records;

pub use android_exporter::{AudioEncoderSink, ChapterMarkRecord};
pub use api::{HelperStatusRecord, LectriceCore, PrepareStatusRecord, core_version, error_registry};
pub use audiobook_records::*;
#[cfg(feature = "bench")]
pub use bench::{BenchResult, BenchSample};
pub use errors::{CoreError, CoreErrorInfo};
pub use export_records::*;
pub use helper::{HelperComputer, HelperConfig, HelperListener, HelperStateRecord, NetworkBinder, PairOutcome, helper_lost_read_ahead_minutes, helper_peer_id};
pub use log_bridge::{LogLevel, LogSink};
pub use model_archive::unpack_model_archive;
pub use records::*;

uniffi::setup_scaffolding!();
