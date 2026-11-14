package app.lectrice.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFilesTest {
    private fun kind(action: String? = IncomingRules.ACTION_VIEW, scheme: String? = "content", authority: String? = "com.files.provider", type: String? = "application/pdf") =
        IncomingRules.kind(action, scheme, authority, type, ownPackage = "app.lectrice")

    @Test
    fun `a pdf or an m4b from another app's provider is accepted`() {
        assertEquals(IncomingKind.Pdf, kind())
        assertEquals(IncomingKind.Pdf, kind(action = IncomingRules.ACTION_SEND, type = "Application/PDF; charset=binary"))
        assertEquals(IncomingKind.Audiobook, kind(type = "audio/mp4"))
        assertEquals(IncomingKind.Audiobook, kind(type = "audio/x-m4b"))
    }

    @Test
    fun `only content URIs are accepted`() {
        assertNull(kind(scheme = "file", authority = null))
        assertNull(kind(scheme = "android.resource", authority = "app.lectrice"))
        assertNull(kind(scheme = "https", authority = "example.org"))
        assertNull(kind(scheme = null))
    }

    @Test
    fun `the app's own providers are refused`() {
        assertNull(kind(authority = "app.lectrice.exports"))
        assertNull(kind(authority = "app.lectrice"))
        assertNull(kind(authority = "10@app.lectrice.exports"))
        assertNull(kind(authority = "APP.LECTRICE.exports"))
        assertNull(kind(authority = ""))
        assertEquals(IncomingKind.Pdf, kind(authority = "app.lectricefiles.provider"))
    }

    @Test
    fun `other actions and types are dropped`() {
        assertNull(kind(action = "android.intent.action.MAIN"))
        assertNull(kind(action = null))
        assertNull(kind(type = "text/html"))
        assertNull(kind(type = "audio/mpeg"))
        assertNull(kind(type = null))
    }

    @Test
    fun `a name from another app is made safe to show`() {
        assertEquals("book.pdf", IncomingRules.displayName("  book\n.pdf\u0000 ", "document.pdf"))
        assertEquals("fdp.exe", IncomingRules.displayName("‮fdp.exe", "document.pdf"))
        assertEquals("document.pdf", IncomingRules.displayName(null, "document.pdf"))
        assertEquals("document.pdf", IncomingRules.displayName("\n\t", "document.pdf"))
        assertEquals(80, IncomingRules.displayName("x".repeat(5_000), "document.pdf").length)
    }

    @Test
    fun `the waiting list is capped and ignores a repeated file`() {
        val files = IncomingFiles(maxWaiting = 2)
        assertTrue(files.offer("content://a/1", IncomingKind.Pdf, "one.pdf"))
        assertFalse(files.offer("content://a/1", IncomingKind.Pdf, "one.pdf"))
        assertTrue(files.offer("content://a/2", IncomingKind.Audiobook, "two.m4b"))
        assertFalse(files.offer("content://a/3", IncomingKind.Pdf, "three.pdf"))
        assertEquals(listOf("one.pdf", "two.m4b"), files.waiting.value.map { it.displayName })

        val first = files.waiting.value.first()
        assertEquals(first, files.take(first.key))
        assertNull(files.take(first.key))
        assertTrue(files.offer("content://a/3", IncomingKind.Pdf, "three.pdf"))
    }
}
