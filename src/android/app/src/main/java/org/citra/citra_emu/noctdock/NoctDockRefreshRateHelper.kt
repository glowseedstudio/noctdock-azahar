// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.app.Activity
import android.os.Build
import android.view.Window
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

internal enum class NoctDockRefreshRateHelperResult {
    NOT_REQUESTED,
    APPLIED_60HZ,
    ALREADY_AT_TARGET,
    UNSUPPORTED,
    MANUAL_GUIDANCE_REQUIRED,
    FAILED,
}

internal data class NoctDockRefreshRateStatus(
    val requested60Hz: Boolean = false,
    val activeRefreshRateHz: Float? = null,
    val result: NoctDockRefreshRateHelperResult = NoctDockRefreshRateHelperResult.NOT_REQUESTED,
    val guidanceMessage: String? = null,
) {
    fun resultLabel(): String =
        when (result) {
            NoctDockRefreshRateHelperResult.NOT_REQUESTED -> "Not requested"
            NoctDockRefreshRateHelperResult.APPLIED_60HZ -> "Applied 60 Hz where supported"
            NoctDockRefreshRateHelperResult.ALREADY_AT_TARGET -> "Already at 60 Hz or lower"
            NoctDockRefreshRateHelperResult.UNSUPPORTED -> "Unsupported on this device"
            NoctDockRefreshRateHelperResult.MANUAL_GUIDANCE_REQUIRED -> "Manual display settings may be required"
            NoctDockRefreshRateHelperResult.FAILED -> "Request failed safely"
        }
}

internal object NoctDockRefreshRateHelper {
    private const val TARGET_HZ = 60f
    private const val TARGET_TOLERANCE_HZ = 1.5f

    private val activeActivity = AtomicReference<WeakReference<Activity>>(WeakReference(null))
    private val lastStatus = AtomicReference(NoctDockRefreshRateStatus())

    fun bindActivity(activity: Activity) {
        activeActivity.set(WeakReference(activity))
    }

    fun clearActivity(activity: Activity) {
        val current = activeActivity.get()?.get()
        if (current == activity) {
            activeActivity.set(WeakReference(null))
        }
    }

    fun status(): NoctDockRefreshRateStatus = lastStatus.get()

    fun requestFor3dsMode(): NoctDockRefreshRateStatus {
        val activity = activeActivity.get()?.get() ?: return notRequested()
        return applyToWindow(activity.window, activity).also { lastStatus.set(it) }
    }

    fun clearFor3dsMode() {
        val activity = activeActivity.get()?.get()
        activity?.window?.let(::clearWindow)
        lastStatus.set(NoctDockRefreshRateStatus())
    }

    private fun applyToWindow(window: Window, activity: Activity): NoctDockRefreshRateStatus {
        val display = window.decorView.display ?: activity.display
        val currentHz =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { display?.mode?.refreshRate }.getOrNull()
            } else {
                null
            }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return unsupported(currentHz)
        }
        val modes = runCatching { display?.supportedModes }.getOrNull().orEmpty()
        if (modes.isEmpty()) {
            return NoctDockRefreshRateStatus(
                requested60Hz = true,
                activeRefreshRateHz = currentHz,
                result = NoctDockRefreshRateHelperResult.MANUAL_GUIDANCE_REQUIRED,
                guidanceMessage = "This handheld may need Display settings changed manually.",
            )
        }
        if (currentHz != null && currentHz <= TARGET_HZ + TARGET_TOLERANCE_HZ) {
            return NoctDockRefreshRateStatus(
                requested60Hz = true,
                activeRefreshRateHz = currentHz,
                result = NoctDockRefreshRateHelperResult.ALREADY_AT_TARGET,
            )
        }
        val preferred =
            modes
                .filter { it.refreshRate in (TARGET_HZ - TARGET_TOLERANCE_HZ)..(TARGET_HZ + TARGET_TOLERANCE_HZ) }
                .minByOrNull { kotlin.math.abs(it.refreshRate - TARGET_HZ) }
                ?: modes.filter { it.refreshRate <= TARGET_HZ + TARGET_TOLERANCE_HZ }.maxByOrNull { it.refreshRate }
        if (preferred == null) {
            return NoctDockRefreshRateStatus(
                requested60Hz = true,
                activeRefreshRateHz = currentHz,
                result = NoctDockRefreshRateHelperResult.MANUAL_GUIDANCE_REQUIRED,
                guidanceMessage = "This handheld may need Display settings changed manually.",
            )
        }
        return runCatching {
            val params = window.attributes
            params.preferredDisplayModeId = preferred.modeId
            window.attributes = params
            val appliedHz = window.decorView.display?.mode?.refreshRate ?: preferred.refreshRate
            NoctDockRefreshRateStatus(
                requested60Hz = true,
                activeRefreshRateHz = appliedHz,
                result =
                    if (appliedHz <= TARGET_HZ + TARGET_TOLERANCE_HZ) {
                        NoctDockRefreshRateHelperResult.APPLIED_60HZ
                    } else {
                        NoctDockRefreshRateHelperResult.MANUAL_GUIDANCE_REQUIRED
                    },
                guidanceMessage =
                    if (appliedHz > TARGET_HZ + TARGET_TOLERANCE_HZ) {
                        "This handheld may need Display settings changed manually."
                    } else {
                        null
                    },
            )
        }.getOrElse {
            NoctDockRefreshRateStatus(
                requested60Hz = true,
                activeRefreshRateHz = currentHz,
                result = NoctDockRefreshRateHelperResult.FAILED,
            )
        }
    }

    private fun clearWindow(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = window.attributes
                params.preferredDisplayModeId = 0
                window.attributes = params
            }
        }
    }

    private fun notRequested(): NoctDockRefreshRateStatus = NoctDockRefreshRateStatus()

    private fun unsupported(currentHz: Float?): NoctDockRefreshRateStatus =
        NoctDockRefreshRateStatus(
            requested60Hz = true,
            activeRefreshRateHz = currentHz,
            result = NoctDockRefreshRateHelperResult.UNSUPPORTED,
            guidanceMessage = "This Android version does not expose display mode APIs.",
        )
}
