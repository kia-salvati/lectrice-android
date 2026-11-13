package app.lectrice.core.link

import android.content.Context
import android.util.Base64
import app.lectrice.core.model.PairedComputer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `filesDir/lectrice/link/`: app-private, and not in any backup rule (the backup rules include only `settings.xml`;
 * file 17, security review S10), so neither the key nor the pairings leave the phone.
 */
internal class LinkFiles(context: Context) {
    val dir: File = File(context.filesDir, "lectrice/link").apply { mkdirs() }
    val identity = File(dir, "identity.key")
    private val peers = File(dir, "peers.json")

    fun readPeers(): PeerList = runCatching { decode(JSONObject(peers.readText())) }.getOrDefault(PeerList())

    /** Written to a temporary file, then renamed over the old one, so a crash never leaves half a file. */
    fun writePeers(list: PeerList) {
        writeAtomically(peers, encode(list).toString().toByteArray())
    }

    private fun encode(list: PeerList): JSONObject = JSONObject()
        .put("enabled", list.enabled)
        .put("active", list.activeId ?: JSONObject.NULL)
        .put(
            "computers",
            JSONArray(
                list.peers.map { peer ->
                    val c = peer.computer
                    JSONObject()
                        .put("peer_id", c.peerId)
                        .put("name", c.name)
                        .put("label", c.label ?: JSONObject.NULL)
                        .put("static_pub", Base64.encodeToString(peer.staticPublic, Base64.NO_WRAP))
                        .put("last_address", c.lastAddress ?: JSONObject.NULL)
                        .put("paired_at", c.pairedAtMs)
                        .put("last_seen_at", c.lastSeenAtMs ?: JSONObject.NULL)
                },
            ),
        )

    private fun decode(json: JSONObject): PeerList {
        val array = json.optJSONArray("computers") ?: JSONArray()
        val peers = (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val key = Base64.decode(item.getString("static_pub"), Base64.NO_WRAP)
                require(key.size == KEY_BYTES)
                Peer(
                    PairedComputer(
                        peerId = item.getString("peer_id"),
                        name = item.getString("name"),
                        label = item.optStringOrNull("label"),
                        lastAddress = item.optStringOrNull("last_address"),
                        pairedAtMs = item.getLong("paired_at"),
                        lastSeenAtMs = if (item.isNull("last_seen_at")) null else item.getLong("last_seen_at"),
                    ),
                    key,
                )
            }.getOrNull()
        }.take(PeerList.MAX_PEERS)
        return PeerList(enabled = json.optBoolean("enabled") && peers.isNotEmpty(), activeId = json.optStringOrNull("active"), peers = peers)
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

    companion object {
        const val KEY_BYTES = 32

        fun writeAtomically(target: File, bytes: ByteArray) {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.outputStream().use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) error("could not replace ${target.name}")
        }
    }
}
