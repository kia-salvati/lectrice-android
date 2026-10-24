use lectrice_domain::{AppError, ErrorName};
use tracing::warn;

/// A registry entry as Kotlin sees it. `message` is the registry's user-facing text, never book content.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoreErrorInfo {
    pub name: String,
    pub code: u32,
    pub operational: bool,
    pub message: String,
}

impl From<ErrorName> for CoreErrorInfo {
    fn from(name: ErrorName) -> Self {
        let spec = name.spec();
        Self { name: name.as_str().to_string(), code: spec.code, operational: spec.operational, message: spec.description.to_string() }
    }
}

/// Kotlin: `CoreException.Failed`. Carries only the primary registry name; the detail and cause chain go to the log.
/// `user_message`, not `message`: a Kotlin exception already has a `message` property.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{name}")]
    Failed { name: String, code: u32, operational: bool, user_message: String },
}

impl From<CoreErrorInfo> for CoreError {
    fn from(info: CoreErrorInfo) -> Self {
        let CoreErrorInfo { name, code, operational, message } = info;
        Self::Failed { name, code, operational, user_message: message }
    }
}

impl From<AppError> for CoreError {
    fn from(error: AppError) -> Self {
        let names: Vec<&str> = error.names().iter().map(|name| name.as_str()).collect();
        warn!(target: "lectrice_ffi", error_names = ?names, detail = error.detail().unwrap_or_default(), "core call failed");
        CoreErrorInfo::from(error.primary()).into()
    }
}

pub type CoreResult<T> = Result<T, CoreError>;
