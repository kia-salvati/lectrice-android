//! The Kotlin API of the Lectrice core (uniffi proc-macros, called through JNA) and the Android composition root.
//! M1 spike subset of the Android spec §5.2.

mod api;
#[cfg(feature = "bench")]
mod bench;
mod composition;
mod errors;
mod log_bridge;
mod no_exporter;
mod records;

pub use api::LectriceCore;
#[cfg(feature = "bench")]
pub use bench::{BenchResult, BenchSample};
pub use errors::{CoreError, CoreErrorInfo};
pub use log_bridge::{LogLevel, LogSink};
pub use records::*;

uniffi::setup_scaffolding!();
