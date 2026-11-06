package io.github.eliasmeireles.obdblackbox.data.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoNamesTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `clip names say what kept them`() {
        assertEquals("marcação 1", VideoNames.reason("obd-20261106-150619-marca_1.mp4"))
        assertEquals("motor apagou sozinho", VideoNames.reason("obd-20261106-150619-desligou_sozinho_2.mp4"))
        assertEquals("gravação", VideoNames.reason("obd-20261106-150619.mp4"))
        assertEquals("outro evento", VideoNames.reason("obd-20261106-150619-outro_evento.mp4"))
    }

    @Test fun `the start time comes from the name`() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals(1_793_891_179_000L, VideoNames.startedAt("obd-20261105-150619-marca_1.mp4", utc))
        assertEquals(null, VideoNames.startedAt("other.mp4", utc))
    }

    @Test fun `gallery names keep only safe characters and round-trip the label`() {
        val name = galleryName(0L, "marca 1/x")
        assertEquals(true, name.startsWith("obd-") && name.endsWith("-marca_1_x.mp4"))
        assertEquals("marcação 1", VideoNames.reason(galleryName(0L, "marca_1")))
    }
}
