package app.lectrice.core.work

import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.PauseReason

/**
 * The exports the core paused because the engine was rebuilt (a helper or model change): they ran at the last look
 * and are now paused "by the system" without a cause this app gave (heat, battery, time limit). They continue at once
 * instead of waiting for the app to come to the front.
 */
internal fun pausedByEngineRebuild(ranBefore: Set<String>, exports: List<AudioExport>, pausedForCause: Set<String>): List<String> =
    exports.filter { it.id in ranBefore && it.status == ExportStatus.Paused && it.pausedBy == PauseReason.System && it.id !in pausedForCause }.map { it.id }
