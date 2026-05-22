// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.content.Context
import androidx.preference.PreferenceManager

class NoctDockBridgeSettings(context: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    var launchBehavior: LaunchBehavior
        get() =
            preferences.getString(KEY_LAUNCH_BEHAVIOR, LaunchBehavior.ASK_EACH_TIME.name)
                ?.let { runCatching { LaunchBehavior.valueOf(it) }.getOrNull() }
                ?: LaunchBehavior.ASK_EACH_TIME
        set(value) = preferences.edit().putString(KEY_LAUNCH_BEHAVIOR, value.name).apply()

    var exportPerformanceMode: ExportPerformanceMode
        get() =
            preferences.getString(KEY_EXPORT_PERFORMANCE_MODE, ExportPerformanceMode.BALANCED.name)
                ?.let { runCatching { ExportPerformanceMode.valueOf(it) }.getOrNull() }
                ?: ExportPerformanceMode.BALANCED
        set(value) = preferences.edit().putString(KEY_EXPORT_PERFORMANCE_MODE, value.name).apply()

    var exportResolution: ExportResolution
        get() =
            preferences.getString(KEY_EXPORT_RESOLUTION, ExportResolution.AUTO.name)
                ?.let { runCatching { ExportResolution.valueOf(it) }.getOrNull() }
                ?: ExportResolution.AUTO
        set(value) = preferences.edit().putString(KEY_EXPORT_RESOLUTION, value.name).apply()

    var exportFps: ExportFps
        get() =
            preferences.getString(KEY_EXPORT_FPS, ExportFps.SAFE_30.name)
                ?.let { runCatching { ExportFps.valueOf(it) }.getOrNull() }
                ?: ExportFps.SAFE_30
        set(value) = preferences.edit().putString(KEY_EXPORT_FPS, value.name).apply()

    var streamWatchEnabled: Boolean
        get() = preferences.getBoolean(KEY_STREAM_WATCH_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_STREAM_WATCH_ENABLED, value).apply()

    var bottomScreenAutoDimMode: BottomScreenAutoDimMode
        get() =
            preferences.getString(KEY_BOTTOM_SCREEN_AUTO_DIM_MODE, BottomScreenAutoDimMode.Gentle.name)
                ?.let { runCatching { BottomScreenAutoDimMode.valueOf(it) }.getOrNull() }
                ?: BottomScreenAutoDimMode.Gentle
        set(value) = preferences.edit().putString(KEY_BOTTOM_SCREEN_AUTO_DIM_MODE, value.name).apply()

    var bottomScreenAutoDimHintShown: Boolean
        get() = preferences.getBoolean(KEY_BOTTOM_SCREEN_AUTO_DIM_HINT_SHOWN, false)
        set(value) = preferences.edit().putBoolean(KEY_BOTTOM_SCREEN_AUTO_DIM_HINT_SHOWN, value).apply()

    var exportQualityGuideShown: Boolean
        get() = preferences.getBoolean(KEY_EXPORT_QUALITY_GUIDE_SHOWN, false)
        set(value) = preferences.edit().putBoolean(KEY_EXPORT_QUALITY_GUIDE_SHOWN, value).apply()

    fun resolvedExportSettings(): ResolvedNoctDockExportSettings =
        NoctDockExportSettingsResolver.resolve(this)

    enum class LaunchBehavior {
        ASK_EACH_TIME,
        ALWAYS_SEND_FROM_NOCTDOCK,
        PLAY_NORMALLY
    }

    enum class ExportResolution(val width: Int, val height: Int) {
        /** Follows the selected export performance preset (Balanced, TV, etc.). */
        AUTO(0, 0),
        NATIVE_400_240(400, 240),
        SHARP_800_480(800, 480),
        TV_1280_720(1280, 720)
    }

    enum class ExportFps(val fps: Int) {
        SAFE_30(30),
        NORMAL_60(60)
    }

    enum class ExportPerformanceMode(
        val label: String,
        val width: Int,
        val height: Int,
        val fps: Int
    ) {
        BATTERY_SAFE("Battery / Safe", 400, 240, 30),
        BALANCED("Balanced", 800, 480, 30),
        SHARP("Sharp", 800, 480, 60),
        TV("TV", 1280, 720, 30),
        EXPERIMENTAL("Experimental", 1280, 720, 60);

        fun nextSafer(): ExportPerformanceMode? =
            when (this) {
                EXPERIMENTAL -> TV
                TV -> BALANCED
                SHARP -> BALANCED
                BALANCED -> BATTERY_SAFE
                BATTERY_SAFE -> null
            }
    }

    companion object {
        private const val KEY_ENABLED = "noctdock_3ds_mode_enabled"
        private const val KEY_LAUNCH_BEHAVIOR = "noctdock_3ds_mode_launch_behavior"
        private const val KEY_EXPORT_PERFORMANCE_MODE = "noctdock_3ds_mode_export_performance_mode"
        private const val KEY_EXPORT_RESOLUTION = "noctdock_3ds_mode_export_resolution"
        private const val KEY_EXPORT_FPS = "noctdock_3ds_mode_export_fps"
        private const val KEY_STREAM_WATCH_ENABLED = "noctdock_3ds_mode_stream_watch_enabled"
        private const val KEY_BOTTOM_SCREEN_AUTO_DIM_MODE = "noctdock_3ds_mode_bottom_screen_auto_dim_mode"
        private const val KEY_BOTTOM_SCREEN_AUTO_DIM_HINT_SHOWN = "noctdock_3ds_mode_bottom_screen_auto_dim_hint_shown"
        private const val KEY_EXPORT_QUALITY_GUIDE_SHOWN = "noctdock_3ds_mode_export_quality_guide_shown"
    }
}
