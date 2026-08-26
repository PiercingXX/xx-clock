package com.piercingxx.xxclock.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * XX Clock is the family's alarm app: SHOW_ALARMS and APP_CLOCK must resolve
 * here so xx-launcher's clock widget (and the system clock picker) open this
 * app. Intent extras are stubbed on the JVM; the routing is pinned in source.
 */
class ClockSystemIntentsTest {

    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    private val mainBlock: String =
        manifestText.substringAfter(".ui.MainActivity").substringBefore("</activity>")

    private val mainSource: String =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxclock/ui/MainActivity.kt"),
            File("app/src/main/java/com/piercingxx/xxclock/ui/MainActivity.kt"),
        ).first { it.exists() }.readText()

    @Test
    fun `manifest registers SHOW_ALARMS on MainActivity`() {
        assertTrue(mainBlock.contains("android.intent.action.SHOW_ALARMS"))
        assertTrue(mainBlock.contains("android.intent.category.DEFAULT"))
    }

    @Test
    fun `manifest registers APP_CLOCK on MainActivity`() {
        assertTrue(mainBlock.contains("android.intent.category.APP_CLOCK"))
    }

    @Test
    fun `SHOW_ALARMS selects the alarms tab`() {
        val fn = mainSource.substringAfter("fun tabFrom").substringBefore("fun normalizeTab")
        assertTrue(fn.contains("ACTION_SHOW_ALARMS"))
        assertTrue(fn.contains("TAB_ALARMS"))
    }
}
