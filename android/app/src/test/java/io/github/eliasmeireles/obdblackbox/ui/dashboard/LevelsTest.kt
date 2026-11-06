package io.github.eliasmeireles.obdblackbox.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `fuel warns strictly below fifteen and is critical strictly below eight percent`() {
        assertEquals(Level.UNKNOWN, Levels.fuel(null))
        assertEquals(Level.NORMAL, Levels.fuel(15.0))
        assertEquals(Level.WARNING, Levels.fuel(14.9))
        assertEquals(Level.WARNING, Levels.fuel(8.0))
        assertEquals(Level.CRITICAL, Levels.fuel(7.9))
    }
}
