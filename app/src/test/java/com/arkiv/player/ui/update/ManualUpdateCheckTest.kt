package com.arkiv.player.ui.update

import com.arkiv.player.data.update.UpdateCheckResult
import com.arkiv.player.data.update.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualUpdateCheckTest {
    @Test
    fun `a failed check never claims there is nothing new`() {
        assertEquals(
            "No pudimos revisar si hay una versión nueva. Revisa tu conexión e intenta de nuevo.",
            ManualUpdateCheck.message(UpdateCheckResult.Failed("dns")),
        )
    }

    @Test
    fun `only a real UpToDate says you have the latest version`() {
        assertEquals("Ya tienes la última versión", ManualUpdateCheck.message(UpdateCheckResult.UpToDate))
    }

    @Test
    fun `an available update shows the dialog, not a message`() {
        assertNull(ManualUpdateCheck.message(UpdateCheckResult.Available(UpdateInfo(74, "0.9.43", "https://archive.org/x.apk", ""))))
    }
}
