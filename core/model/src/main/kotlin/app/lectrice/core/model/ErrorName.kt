package app.lectrice.core.model

/** Mirror of the Rust registry (`lectrice-domain/src/error.rs`); `ErrorRegistryTest` in `lectrice-ffi` keeps them equal. */
enum class ErrorName(val code: Int) {
    RequestInvalid(1000),
    UploadMissingFile(1001),
    UploadTooLarge(1002),
    PdfInvalid(1003),
    PdfEncrypted(1004),
    PdfNoText(1005),
    RouteNotFound(1006),
    MethodNotAllowed(1007),
    RequestCancelled(1008),
    RequestForbidden(1009),
    PdfTooComplex(1010),
    DocumentNotFound(1101),
    SentenceNotFound(1102),
    VoiceUnknown(2001),
    SpeechEngineUnavailable(2002),
    SpeechSynthesisFailed(2003),
    ExportNotFound(3001),
    ExportNotReady(3002),
    ExportFormatUnsupported(3003),
    ExporterUnavailable(3004),
    AudiobookNotLectrice(3101),
    AudiobookOtherBook(3102),
    AudiobookUnreadable(3103),
    ModelMissing(4001),
    ModelDownloadFailed(4002),
    ModelChecksumMismatch(4003),
    StorageFull(4004),
    ExportPaused(4005),
    FileUnreadable(4006),
    HelperNotPaired(5001),
    HelperPairingFailed(5002),
    HelperUnreachable(5003),
    HelperModelMissing(5004),
    HelperIncompatible(5005),
    HelperBusy(5006),
    StorageFailed(9001),
    Internal(9999),
    ;

    companion object {
        private val byName = entries.associateBy(ErrorName::name)

        /** Unknown names (a newer core) are treated as `Internal`. */
        fun fromName(name: String): ErrorName = byName[name] ?: Internal
    }
}

/** The one error type the layers above `core:data` see. Never carries book text. */
class LectriceError(
    val name: ErrorName,
    cause: Throwable? = null,
    /** A size the message needs: the file's size (`UploadTooLarge`) or the space to free (`StorageFull`). */
    val bytes: Long? = null,
) : Exception(name.name, cause)
