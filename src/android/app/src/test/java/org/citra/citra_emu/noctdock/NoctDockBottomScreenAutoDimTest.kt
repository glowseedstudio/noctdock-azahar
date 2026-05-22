// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoctDockBottomScreenAutoDimTest {
    @Test
    fun brightnessMappingMatchesModes() {
        assertNull(NoctDockBottomScreenAutoDimLogic.brightnessFor(BottomScreenAutoDimMode.Off))
        assertEquals(0.35f, NoctDockBottomScreenAutoDimLogic.brightnessFor(BottomScreenAutoDimMode.Gentle)!!, 0.001f)
        assertEquals(0.15f, NoctDockBottomScreenAutoDimLogic.brightnessFor(BottomScreenAutoDimMode.Dark)!!, 0.001f)
        assertEquals(0.03f, NoctDockBottomScreenAutoDimLogic.brightnessFor(BottomScreenAutoDimMode.MaximumDark)!!, 0.001f)
    }

    @Test
    fun dimTriggersAfterTenSecondsIdle() {
        val lastTouch = 1_000L
        val now = lastTouch + NoctDockBottomScreenAutoDimLogic.IDLE_DIM_MS
        assertTrue(
            NoctDockBottomScreenAutoDimLogic.shouldDim(
                nowMs = now,
                lastTouchMs = lastTouch,
                mode = BottomScreenAutoDimMode.Gentle,
                exportActive = true,
                sessionArmed = true,
            ),
        )
    }

    @Test
    fun touchWindowRestartsIdleTimer() {
        val lastTouch = 5_000L
        val now = lastTouch + 5_000L
        assertFalse(
            NoctDockBottomScreenAutoDimLogic.shouldDim(
                nowMs = now,
                lastTouchMs = lastTouch,
                mode = BottomScreenAutoDimMode.Gentle,
                exportActive = true,
                sessionArmed = true,
            ),
        )
    }

    @Test
    fun settingOffNeverDims() {
        assertFalse(NoctDockBottomScreenAutoDimLogic.isEnabled(BottomScreenAutoDimMode.Off))
        assertFalse(
            NoctDockBottomScreenAutoDimLogic.shouldDim(
                nowMs = 20_000L,
                lastTouchMs = 0L,
                mode = BottomScreenAutoDimMode.Off,
                exportActive = true,
                sessionArmed = true,
            ),
        )
    }

    @Test
    fun normalPlayWithoutExportNeverDims() {
        assertFalse(
            NoctDockBottomScreenAutoDimLogic.shouldDim(
                nowMs = 20_000L,
                lastTouchMs = 0L,
                mode = BottomScreenAutoDimMode.Gentle,
                exportActive = false,
                sessionArmed = false,
            ),
        )
    }

    @Test
    fun idleSecondsTracksTimeSinceTouch() {
        assertEquals(0, NoctDockBottomScreenAutoDimLogic.idleSeconds(10_000L, 0L))
        assertEquals(7, NoctDockBottomScreenAutoDimLogic.idleSeconds(17_000L, 10_000L))
    }
}
