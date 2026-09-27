package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConfirmationTest {

    @Test
    fun `what waits its turn is removed from the queue`() {
        assertEquals(
            DownloadAction.REMOVE_FROM_QUEUE,
            DownloadConfirmation.actionFor(DownloadDisplayState.Queued),
        )
    }

    @Test
    fun `what is downloading is cancelled`() {
        assertEquals(
            DownloadAction.CANCEL,
            DownloadConfirmation.actionFor(DownloadDisplayState.Downloading(0.3f)),
        )
        assertEquals(
            DownloadAction.CANCEL,
            DownloadConfirmation.actionFor(DownloadDisplayState.Downloading(null)),
        )
    }

    @Test
    fun `what is already downloaded is deleted`() {
        assertEquals(
            DownloadAction.DELETE,
            DownloadConfirmation.actionFor(DownloadDisplayState.Done),
        )
    }

    @Test
    fun `what failed offers nothing to confirm`() {
        // Its action is retrying, which destroys nothing and so doesn't ask.
        assertNull(DownloadConfirmation.actionFor(DownloadDisplayState.Failed("lo que sea")))
    }

    @Test
    fun `what was refused can only be taken off the list, which loses nothing`() {
        assertEquals(
            DownloadAction.REMOVE_REFUSED,
            DownloadConfirmation.actionFor(DownloadDisplayState.Refused("Este video no se puede descargar")),
        )
    }

    /** "todavía no empezó a bajar" read as if it might still start; a refused one never will. */
    @Test
    fun `removing a refused download says it cannot be downloaded, not that it has not started`() {
        val text = DownloadConfirmation.text(DownloadAction.REMOVE_REFUSED, "E1")
        assertTrue(text.body.contains("E1"))
        assertTrue(text.body.contains("no se puede descargar"))
        assertTrue(!text.body.contains("todavía"))
        assertEquals("Quitar", text.confirm)
    }

    @Test
    fun `what nobody queued offers nothing`() {
        assertNull(DownloadConfirmation.actionFor(DownloadDisplayState.NotDownloaded))
        assertNull(DownloadConfirmation.actionFor(DownloadDisplayState.NeedsConfirmation))
    }

    @Test
    fun `cancel warns that what downloaded is not lost`() {
        val text = DownloadConfirmation.text(DownloadAction.CANCEL, "E1")
        assertTrue(text.body.contains("E1"))
        assertTrue(text.body.contains("reintentar"))
        // The dismiss button can NOT be called "Cancelar" in this dialog: next to
        // "Cancelar la descarga" it wouldn't be clear which is which.
        assertEquals("Seguir bajando", text.dismiss)
    }

    @Test
    fun `delete clarifies the chapter stays in the library`() {
        val text = DownloadConfirmation.text(DownloadAction.DELETE, "E1")
        assertTrue(text.body.contains("biblioteca"))
        assertEquals("Borrar", text.confirm)
    }

    @Test
    fun `with no chapter name the text still makes sense`() {
        val text = DownloadConfirmation.text(DownloadAction.REMOVE_FROM_QUEUE, null)
        assertTrue(text.body.isNotBlank())
        assertTrue(text.body.contains("«").not())
    }
}
