// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

data class ResolvedNoctDockExportSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val performanceModeLabel: String,
)

object NoctDockExportSettingsResolver {
    fun resolve(settings: NoctDockBridgeSettings): ResolvedNoctDockExportSettings =
        resolve(
            performanceMode = settings.exportPerformanceMode,
            exportResolution = settings.exportResolution,
            exportFps = settings.exportFps,
        )

    fun resolve(
        performanceMode: NoctDockBridgeSettings.ExportPerformanceMode,
        exportResolution: NoctDockBridgeSettings.ExportResolution,
        exportFps: NoctDockBridgeSettings.ExportFps,
    ): ResolvedNoctDockExportSettings {
        val fps = exportFps.fps
        val (width, height) =
            when (exportResolution) {
                NoctDockBridgeSettings.ExportResolution.AUTO ->
                    performanceMode.width to performanceMode.height
                else -> exportResolution.width to exportResolution.height
            }
        val label =
            when (exportResolution) {
                NoctDockBridgeSettings.ExportResolution.AUTO -> performanceMode.label
                else -> "${width}x${height} @ ${fps}fps"
            }
        return ResolvedNoctDockExportSettings(
            width = width,
            height = height,
            fps = fps,
            performanceModeLabel = label,
        )
    }

    fun applyPerformanceMode(
        settings: NoctDockBridgeSettings,
        mode: NoctDockBridgeSettings.ExportPerformanceMode,
    ) {
        settings.exportPerformanceMode = mode
        settings.exportResolution =
            when (mode) {
                NoctDockBridgeSettings.ExportPerformanceMode.BATTERY_SAFE ->
                    NoctDockBridgeSettings.ExportResolution.NATIVE_400_240
                NoctDockBridgeSettings.ExportPerformanceMode.BALANCED,
                NoctDockBridgeSettings.ExportPerformanceMode.SHARP,
                -> NoctDockBridgeSettings.ExportResolution.SHARP_800_480
                NoctDockBridgeSettings.ExportPerformanceMode.TV,
                NoctDockBridgeSettings.ExportPerformanceMode.EXPERIMENTAL,
                -> NoctDockBridgeSettings.ExportResolution.TV_1280_720
            }
        settings.exportFps =
            if (mode.fps >= 60) {
                NoctDockBridgeSettings.ExportFps.NORMAL_60
            } else {
                NoctDockBridgeSettings.ExportFps.SAFE_30
            }
    }
}
