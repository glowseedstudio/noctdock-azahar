// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.Log
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

enum class BottomScreenAutoDimMode {
    Off,
    Gentle,
    Dark,
    MaximumDark,
}

data class NoctDockBottomScreenAutoDimStatus(
    val enabled: Boolean = false,
    val mode: BottomScreenAutoDimMode = BottomScreenAutoDimMode.Off,
    val dimmed: Boolean = false,
    val idleSeconds: Int = 0,
    val restoreState: String = "none",
)

internal object NoctDockBottomScreenAutoDimLogic {
    const val IDLE_DIM_MS = 10_000L

    fun brightnessFor(mode: BottomScreenAutoDimMode): Float? =
        when (mode) {
            BottomScreenAutoDimMode.Off -> null
            BottomScreenAutoDimMode.Gentle -> 0.35f
            BottomScreenAutoDimMode.Dark -> 0.15f
            BottomScreenAutoDimMode.MaximumDark -> 0.03f
        }

    fun isEnabled(mode: BottomScreenAutoDimMode): Boolean = mode != BottomScreenAutoDimMode.Off

    fun shouldDim(
        nowMs: Long,
        lastTouchMs: Long,
        mode: BottomScreenAutoDimMode,
        exportActive: Boolean,
        sessionArmed: Boolean,
    ): Boolean =
        exportActive &&
            sessionArmed &&
            isEnabled(mode) &&
            lastTouchMs > 0L &&
            nowMs - lastTouchMs >= IDLE_DIM_MS

    fun idleSeconds(
        nowMs: Long,
        lastTouchMs: Long,
    ): Int =
        if (lastTouchMs <= 0L) {
            0
        } else {
            max(0, ((nowMs - lastTouchMs) / 1000L).toInt())
        }
}

internal object NoctDockBottomScreenAutoDim {
    private val handler = Handler(Looper.getMainLooper())
    private val statusRef = AtomicReference(NoctDockBottomScreenAutoDimStatus())
    private var boundActivity = WeakReference<Activity>(null)
    private var exportActive = false
    private var sessionArmed = false
    private var dimmed = false
    private var lastTouchAtMs = 0L
    private var currentMode = BottomScreenAutoDimMode.Gentle
    private var restoreState = "none"
    private val dimRunnable = Runnable { applyDimIfStillIdle() }

    fun status(): NoctDockBottomScreenAutoDimStatus = statusRef.get()

    fun bindActivity(activity: Activity) {
        boundActivity = WeakReference(activity)
    }

    fun clearActivity(activity: Activity) {
        if (boundActivity.get() == activity) {
            boundActivity = WeakReference(null)
        }
        stopSession("activity_cleared")
    }

    fun onExportSessionStarting() {
        exportActive = true
        sessionArmed = true
        currentMode = NoctDockBridgeSettings(CitraApplication.appContext).bottomScreenAutoDimMode
        publishStatus()
        if (!NoctDockBottomScreenAutoDimLogic.isEnabled(currentMode)) {
            restoreBrightness("setting_off")
            return
        }
        markTouchInteraction()
        maybeShowFirstTimeToast()
    }

    fun onExportSessionEnded(reason: String) {
        exportActive = false
        sessionArmed = false
        handler.removeCallbacks(dimRunnable)
        restoreBrightness(reason)
        publishStatus()
    }

    fun onEmulationPaused() {
        handler.removeCallbacks(dimRunnable)
        if (dimmed) {
            restoreBrightness("emulation_paused")
        }
        publishStatus()
    }

    fun onEmulationResumed() {
        if (!exportActive || !sessionArmed || !NoctDockBottomScreenAutoDimLogic.isEnabled(currentMode)) {
            return
        }
        markTouchInteraction()
    }

    fun onTouchInteraction(event: MotionEvent? = null) {
        if (event != null && !isTouchscreenInteraction(event)) {
            return
        }
        if (!exportActive || !sessionArmed || !NoctDockBottomScreenAutoDimLogic.isEnabled(currentMode)) {
            return
        }
        markTouchInteraction()
    }

    fun isTouchscreenInteraction(event: MotionEvent): Boolean {
        val source = event.source
        val fromTouchscreen =
            (source and InputDevice.SOURCE_TOUCHSCREEN) != 0 ||
                (source and InputDevice.SOURCE_TOUCHPAD) != 0 ||
                (source and InputDevice.SOURCE_STYLUS) != 0
        if (!fromTouchscreen) {
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> true
            else -> false
        }
    }

    private fun markTouchInteraction() {
        lastTouchAtMs = SystemClock.uptimeMillis()
        if (dimmed) {
            restoreBrightness("touch")
        }
        scheduleDimCheck()
        publishStatus()
    }

    private fun scheduleDimCheck() {
        handler.removeCallbacks(dimRunnable)
        if (!exportActive || !sessionArmed || !NoctDockBottomScreenAutoDimLogic.isEnabled(currentMode)) {
            return
        }
        handler.postDelayed(dimRunnable, NoctDockBottomScreenAutoDimLogic.IDLE_DIM_MS)
    }

    private fun applyDimIfStillIdle() {
        val now = SystemClock.uptimeMillis()
        if (!NoctDockBottomScreenAutoDimLogic.shouldDim(now, lastTouchAtMs, currentMode, exportActive, sessionArmed)) {
            publishStatus()
            return
        }
        val brightness = NoctDockBottomScreenAutoDimLogic.brightnessFor(currentMode) ?: return
        val window = boundActivity.get()?.window ?: return
        if (applyWindowBrightness(window, brightness)) {
            dimmed = true
            restoreState = "dimmed"
            publishStatus()
        }
    }

    private fun restoreBrightness(reason: String) {
        handler.removeCallbacks(dimRunnable)
        val window = boundActivity.get()?.window
        if (window != null) {
            applyWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        dimmed = false
        restoreState = "restored_$reason"
        publishStatus()
    }

    private fun stopSession(reason: String) {
        if (exportActive || dimmed) {
            onExportSessionEnded(reason)
        } else {
            handler.removeCallbacks(dimRunnable)
            restoreBrightness(reason)
        }
    }

    private fun applyWindowBrightness(
        window: Window,
        brightness: Float,
    ): Boolean =
        runCatching {
            val params = window.attributes
            params.screenBrightness = brightness
            window.attributes = params
            true
        }.getOrElse { error ->
            Log.warning("[NoctDock] Bottom screen brightness request failed: ${error.message}")
            false
        }

    private fun maybeShowFirstTimeToast() {
        val context = CitraApplication.appContext
        val settings = NoctDockBridgeSettings(context)
        if (settings.bottomScreenAutoDimHintShown) {
            return
        }
        settings.bottomScreenAutoDimHintShown = true
        val activity = boundActivity.get() ?: return
        handler.post {
            Toast.makeText(
                activity,
                context.getString(R.string.noctdock_bottom_screen_auto_dim_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun publishStatus() {
        val now = SystemClock.uptimeMillis()
        statusRef.set(
            NoctDockBottomScreenAutoDimStatus(
                enabled = NoctDockBottomScreenAutoDimLogic.isEnabled(currentMode) && exportActive && sessionArmed,
                mode = currentMode,
                dimmed = dimmed,
                idleSeconds = NoctDockBottomScreenAutoDimLogic.idleSeconds(now, lastTouchAtMs),
                restoreState = restoreState,
            ),
        )
    }
}
