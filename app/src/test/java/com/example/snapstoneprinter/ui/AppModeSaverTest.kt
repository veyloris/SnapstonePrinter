package com.example.snapstoneprinter.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeSaverTest {
    @Test
    fun unknownSavedModeFallsBack() {
        for (stored in listOf("", "momirvig", "MomirVig", "Snapstone Wielder", "REMOVED_MODE")) {
            assertEquals(AppMode.SNAPSTONE_WIELDER, AppModeSaver.restore(stored))
        }
    }

    @Test
    fun everyModeRoundTripsByName() {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        for (mode in AppMode.entries) {
            val saved = with(AppModeSaver) { scope.save(mode) }
            assertEquals(mode.name, saved)
            assertEquals(mode, AppModeSaver.restore(requireNotNull(saved)))
        }
    }
}
