package app.lectrice.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookKeysTest {
    private val documentId = "ab".repeat(32)

    @Test
    fun `every key that carries a book id is kept out of the backed-up file`() {
        listOf("lastPlayedDocumentId", "voice.$documentId", "speed.$documentId", "slowTip.$documentId", "source.$documentId").forEach {
            assertTrue(it, BookKeys.isPerBook(it))
        }
    }

    @Test
    fun `app-wide settings stay in the backed-up file`() {
        listOf("theme", "textSize", "defaultVoice", "defaultSpeed", "readSkipped", "activeModel", "hint.pill", "notificationAsked").forEach {
            assertFalse(it, BookKeys.isPerBook(it))
        }
    }

    /** The backup rules name what is included; the per-book file must never be added to them. */
    @Test
    fun `the backup rules include only the settings file`() {
        val resources = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/res/xml") }.first { it.isDirectory }
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { name ->
            val includes = Regex("<include[^>]*>").findAll(File(resources, name).readText()).map { it.value }.toList()
            assertTrue(name, includes.isNotEmpty() && includes.all { it.contains("domain=\"sharedpref\"") && it.contains("path=\"settings.xml\"") })
        }
    }
}
