package app.lectrice.spike

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
private const val JSON_INDENT = 2

/**
 * `files/results/<model>-<timestamp>.json`, rewritten after every part so an interrupted session keeps what it has.
 * Holds only ids, counts and durations: no book text, no file names, no titles.
 */
class ResultFile(resultsDir: File, device: JSONObject, app: JSONObject) {
    val file: File = File(resultsDir, "${device.getString("model").replace(Regex("[^A-Za-z0-9_-]"), "_")}-${FILE_STAMP.format(Instant.now())}.json")
    private val runs = JSONArray()
    private val root = JSONObject().put("device", device).put("app", app).put("startedAt", Instant.now().toString()).put("runs", runs)

    init {
        resultsDir.mkdirs()
        save()
    }

    @Synchronized
    fun add(kind: String, data: JSONObject) {
        runs.put(data.put("kind", kind).put("at", Instant.now().toString()))
        save()
    }

    private fun save() {
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeText(root.toString(JSON_INDENT))
        partial.renameTo(file)
    }
}
