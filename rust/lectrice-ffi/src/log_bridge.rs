//! Forwards the core's `tracing` events to Kotlin. The core logs only ids, counts, durations and errors.

use std::fmt::Debug;
use std::sync::{Arc, OnceLock, RwLock};

use serde_json::{Map, Value};
use tracing::field::{Field, Visit};
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::layer::{Context, SubscriberExt};
use tracing_subscriber::{Layer, Registry};

const MESSAGE_FIELD: &str = "message";
/// Debug events (per-clip cache writes) would flood logcat during read-ahead.
const MAX_LEVEL: LevelFilter = LevelFilter::INFO;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

impl From<&Level> for LogLevel {
    fn from(level: &Level) -> Self {
        match *level {
            Level::ERROR => Self::Error,
            Level::WARN => Self::Warn,
            Level::INFO => Self::Info,
            Level::DEBUG => Self::Debug,
            Level::TRACE => Self::Trace,
        }
    }
}

/// Kotlin implements this; it is called from Rust threads and must return quickly.
#[uniffi::export(with_foreign)]
pub trait LogSink: Send + Sync {
    fn log(&self, level: LogLevel, target: String, message: String, fields_json: String);
}

static SINK: RwLock<Option<Arc<dyn LogSink>>> = RwLock::new(None);
static INSTALLED: OnceLock<()> = OnceLock::new();

/// The global subscriber can be set once per process; a later core only swaps the sink.
pub fn install(sink: Arc<dyn LogSink>) {
    *SINK.write().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(sink);
    INSTALLED.get_or_init(|| {
        let subscriber = Registry::default().with(ForwardLayer.with_filter(MAX_LEVEL));
        // Fails only if the host app set its own subscriber, which then gets the events instead.
        let _ = tracing::subscriber::set_global_default(subscriber);
    });
}

struct ForwardLayer;

impl<S: Subscriber> Layer<S> for ForwardLayer {
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let Some(sink) = SINK.read().unwrap_or_else(|poisoned| poisoned.into_inner()).clone() else { return };
        let mut fields = FieldCollector::default();
        event.record(&mut fields);
        let metadata = event.metadata();
        sink.log(metadata.level().into(), metadata.target().to_string(), fields.message, Value::Object(fields.fields).to_string());
    }
}

#[derive(Default)]
struct FieldCollector {
    message: String,
    fields: Map<String, Value>,
}

impl FieldCollector {
    fn insert(&mut self, field: &Field, value: Value) {
        self.fields.insert(field.name().to_string(), value);
    }
}

impl Visit for FieldCollector {
    fn record_i64(&mut self, field: &Field, value: i64) {
        self.insert(field, value.into());
    }

    fn record_u64(&mut self, field: &Field, value: u64) {
        self.insert(field, value.into());
    }

    fn record_f64(&mut self, field: &Field, value: f64) {
        self.insert(field, value.into());
    }

    fn record_bool(&mut self, field: &Field, value: bool) {
        self.insert(field, value.into());
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == MESSAGE_FIELD {
            self.message = value.to_string();
        } else {
            self.insert(field, value.into());
        }
    }

    fn record_debug(&mut self, field: &Field, value: &dyn Debug) {
        let text = format!("{value:?}");
        if field.name() == MESSAGE_FIELD {
            self.message = text;
        } else {
            self.insert(field, text.into());
        }
    }
}
