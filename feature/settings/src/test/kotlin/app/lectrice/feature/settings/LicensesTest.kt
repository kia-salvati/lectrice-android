package app.lectrice.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicensesTest {
    @Test
    fun everyComponentHasItsTexts() {
        val folder = File("../../app/src/main/assets/licenses")
        Licenses.components.flatMap { it.files }.forEach { name -> assertTrue("missing $name", File(folder, name).isFile) }
    }
}
