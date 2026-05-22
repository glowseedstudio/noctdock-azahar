// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import org.junit.Assert.assertEquals
import org.junit.Test

class NoctDockExportSettingsResolverTest {
    @Test
    fun autoResolutionFollowsPerformancePreset() {
        val resolved =
            NoctDockExportSettingsResolver.resolve(
                performanceMode = NoctDockBridgeSettings.ExportPerformanceMode.TV,
                exportResolution = NoctDockBridgeSettings.ExportResolution.AUTO,
                exportFps = NoctDockBridgeSettings.ExportFps.SAFE_30,
            )

        assertEquals(1280, resolved.width)
        assertEquals(720, resolved.height)
        assertEquals(30, resolved.fps)
        assertEquals("TV", resolved.performanceModeLabel)
    }

    @Test
    fun explicitResolutionOverridesPresetSize() {
        val resolved =
            NoctDockExportSettingsResolver.resolve(
                performanceMode = NoctDockBridgeSettings.ExportPerformanceMode.BALANCED,
                exportResolution = NoctDockBridgeSettings.ExportResolution.TV_1280_720,
                exportFps = NoctDockBridgeSettings.ExportFps.NORMAL_60,
            )

        assertEquals(1280, resolved.width)
        assertEquals(720, resolved.height)
        assertEquals(60, resolved.fps)
        assertEquals("1280x720 @ 60fps", resolved.performanceModeLabel)
    }

    @Test
    fun sharpPerformancePresetMapsToEightHundredByFourEightyAtSixtyFps() {
        val resolved =
            NoctDockExportSettingsResolver.resolve(
                performanceMode = NoctDockBridgeSettings.ExportPerformanceMode.SHARP,
                exportResolution = NoctDockBridgeSettings.ExportResolution.AUTO,
                exportFps = NoctDockBridgeSettings.ExportFps.NORMAL_60,
            )

        assertEquals(800, resolved.width)
        assertEquals(480, resolved.height)
        assertEquals(60, resolved.fps)
    }
}
