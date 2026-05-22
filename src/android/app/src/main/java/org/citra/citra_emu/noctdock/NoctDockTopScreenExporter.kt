// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import android.widget.Toast
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class RgbaFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val queuedAtNs: Long = System.nanoTime()
)

private data class EmulationPerfStats(
    val systemFps: Double,
    val gameFps: Double,
    val speed: Double,
    val gpuMs: Double,
    val swapMs: Double
)

enum class NoctDockExportPath(val label: String) {
    OPENGL_ENCODER_SURFACE("OPENGL_ENCODER_SURFACE"),
    VULKAN_ENCODER_SURFACE("VULKAN_ENCODER_SURFACE"),
    OPENGL_READBACK_FALLBACK("OPENGL_READBACK_FALLBACK"),
    VULKAN_READBACK_FALLBACK("VULKAN_READBACK_FALLBACK"),
    VULKAN_UNAVAILABLE("VULKAN_UNAVAILABLE")
}

enum class NoctDockExportState {
    IDLE,
    STARTING,
    EXPORTING,
    RECEIVER_UNREACHABLE,
    ENCODER_ERROR,
    FRAME_READBACK_TOO_SLOW,
    STOPPED
}

class NoctDockNativeTopScreenSource : NoctDockTopScreenSource {
    override fun startTopScreenExport(config: NoctDockExportConfig) {
        val receiverAddress = config.receiverAddress
            ?: throw NoctDockExportUnavailableException("NoctDock could not start 3DS Mode. Playing normally.")

        val exporter = NoctDockTopScreenExportManager
        runCatching {
            exporter.start(
                config = config.copy(receiverAddress = receiverAddress),
                rendererBackend = null
            )
        }.onFailure { error ->
            NativeLibrary.stopNoctDockTopScreenExport()
            NativeLibrary.clearNoctDockEncoderInputSurface()
            exporter.stop()
            throw error
        }
        Log.info("[NoctDock] Top-screen export started")
    }

    override fun stopTopScreenExport() {
        NativeLibrary.stopNoctDockTopScreenExport()
        NativeLibrary.clearNoctDockEncoderInputSurface()
        NoctDockTopScreenExportManager.stop()
    }

    override fun isExporting(): Boolean =
        NativeLibrary.isNoctDockTopScreenExporting() || NoctDockTopScreenExportManager.isRunning()
}

object NoctDockTopScreenExportManager {
    private val running = AtomicBoolean(false)
    @Volatile
    private var exportState = NoctDockExportState.IDLE
    private var encoder: NoctDockMediaCodecFrameEncoder? = null
    private var diagnostics: ScheduledExecutorService? = null
    private val exportFrames = AtomicLong(0)
    private val exportFrameTimeUs = AtomicLong(0)
    private val readbackTimeUs = AtomicLong(0)
    private val readbackMaxTimeUs = AtomicLong(0)
    private val slowReadbacks = AtomicLong(0)
    @Volatile
    private var currentConfig: NoctDockExportConfig? = null
    @Volatile
    private var effectiveConfig: NoctDockExportConfig? = null
    @Volatile
    private var currentBitrate: Int = 0
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var receiverReachable: Boolean = false
    @Volatile
    private var currentRendererBackend: String = "OpenGL"
    @Volatile
    private var currentExportPath = NoctDockExportPath.OPENGL_READBACK_FALLBACK
    @Volatile
    private var vulkanBlocker: String? = null
    @Volatile
    private var currentPerformanceMode = NoctDockBridgeSettings.ExportPerformanceMode.BALANCED
    @Volatile
    private var safetyMessageShown = false
    @Volatile
    private var fallbackMessageShown = false

    fun start(config: NoctDockExportConfig, rendererBackend: String? = "OpenGL") {
        if (!running.compareAndSet(false, true)) return
        NoctDockBottomScreenAutoDim.onExportSessionStarting()
        exportState = NoctDockExportState.STARTING
        currentConfig = config
        currentPerformanceMode = profileFor(config.width, config.height, config.fps)
        effectiveConfig = config.copy(
            width = currentPerformanceMode.width,
            height = currentPerformanceMode.height,
            fps = currentPerformanceMode.fps,
            performanceMode = currentPerformanceMode.label
        )
        currentRendererBackend =
            rendererBackend?.ifBlank { "Unknown" } ?: NativeLibrary.getNoctDockCurrentRendererBackend()
        currentExportPath = fallbackPathForRenderer(currentRendererBackend)
        vulkanBlocker = null
        lastError = null
        receiverReachable = false
        safetyMessageShown = false
        fallbackMessageShown = false
        try {
            val activeConfig = requireNotNull(effectiveConfig)
            val address = activeConfig.receiverAddress?.takeIf { it.isNotBlank() }
                ?: throw NoctDockExportUnavailableException("NoctDock screen is not available. Playing normally.")
            NativeLibrary.updateNoctDockTopScreenExportProfile(
                activeConfig.width,
                activeConfig.height,
                activeConfig.fps
            )
            NoctDockStreamWatch.start()
            startEncoderPipeline(activeConfig, validateReceiver = true, preferSurfaceInput = true)
            val started = NativeLibrary.startNoctDockTopScreenExport(
                activeConfig.width,
                activeConfig.height,
                activeConfig.fps,
                activeConfig.preferredCodec,
                activeConfig.sessionId,
                address,
                activeConfig.receiverPort,
                activeConfig.audioMode
            )
            if (!started) {
                throw NoctDockExportUnavailableException(
                    "NoctDock could not start 3DS Mode. Playing normally."
                )
            }
            currentRendererBackend = NativeLibrary.getNoctDockTopScreenExportBackend()
            if (currentRendererBackend.equals("Vulkan", ignoreCase = true) &&
                currentExportPath == NoctDockExportPath.VULKAN_READBACK_FALLBACK
            ) {
                vulkanBlocker = VULKAN_ENCODER_SURFACE_BLOCKER
                NoctDockStreamWatch.event("vulkan_surface_blocked", VULKAN_ENCODER_SURFACE_BLOCKER)
            }
            NoctDockStreamWatch.event("export_started", "$currentRendererBackend export starting in ${activeConfig.performanceMode} at ${activeConfig.width}x${activeConfig.height}@${activeConfig.fps} path=${currentExportPath.label}")
            diagnostics = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "NoctDockDiagnostics")
            }.also { worker ->
                worker.scheduleAtFixedRate({ logHealth() }, 2, 2, TimeUnit.SECONDS)
            }
            exportState = NoctDockExportState.EXPORTING
            NoctDockRefreshRateHelper.requestFor3dsMode()
            Log.info(
                "[NoctDock] Exporting to $address:${activeConfig.receiverPort}, codec=${activeConfig.preferredCodec}, " +
                    "mode=${activeConfig.performanceMode}, output=${activeConfig.width}x${activeConfig.height}@${activeConfig.fps}, bitrate=$currentBitrate"
            )
        } catch (error: Throwable) {
            lastError = error.message ?: error::class.java.simpleName
            runCatching { diagnostics?.shutdownNow() }
            diagnostics = null
            NativeLibrary.stopNoctDockTopScreenExport()
            NativeLibrary.clearNoctDockEncoderInputSurface()
            runCatching { encoder?.stop() }
            encoder = null
            receiverReachable = false
            exportState = NoctDockExportState.IDLE
            running.set(false)
            NoctDockBottomScreenAutoDim.onExportSessionEnded("export_start_failed")
            NoctDockStreamWatch.event("export_stopped", "$currentRendererBackend export failed to start cleanly")
            NoctDockStreamWatch.stop()
            throw error
        }
    }

    fun offerFrame(
        bytes: ByteArray,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        readbackUs: Long,
        exportUs: Long
    ) {
        if (!running.get()) return
        exportFrames.incrementAndGet()
        exportFrameTimeUs.addAndGet(exportUs.coerceAtLeast(0))
        readbackTimeUs.addAndGet(readbackUs.coerceAtLeast(0))
        readbackMaxTimeUs.updateAndGet { current -> maxOf(current, readbackUs.coerceAtLeast(0)) }
        if (readbackUs > SLOW_READBACK_WARNING_US) {
            slowReadbacks.incrementAndGet()
            exportState = NoctDockExportState.FRAME_READBACK_TOO_SLOW
            NoctDockStreamWatch.event("readback_slow", "$currentRendererBackend readback took ${readbackUs / 1000.0}ms")
        }
        encoder?.offerFrame(RgbaFrame(bytes, width, height, presentationTimeUs))
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        exportState = NoctDockExportState.STOPPED
        runCatching { diagnostics?.shutdownNow() }
        diagnostics = null
        runCatching { encoder?.stop() }
        encoder = null
        receiverReachable = false
        NativeLibrary.clearNoctDockEncoderInputSurface()
        NoctDockStreamWatch.event("export_stopped", "$currentRendererBackend export stopped")
        NoctDockRefreshRateHelper.clearFor3dsMode()
        NoctDockBottomScreenAutoDim.onExportSessionEnded("export_stopped")
        NoctDockStreamWatch.stop()
        Log.info("[NoctDock] Top-screen export stopped")
    }

    fun isRunning(): Boolean = running.get()

    fun state(): NoctDockExportState = exportState

    fun handleNativeExportFailure(message: String) {
        lastError = message
        Log.error("[NoctDock] Native top-screen export failure: $message")
        NoctDockStreamWatch.event("encoder_error", message)
        stop()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(CitraApplication.appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleSurfaceExportFallback(message: String) {
        if (!running.get()) return
        val config = saferFallbackConfig(effectiveConfig ?: return)
        effectiveConfig = config
        NativeLibrary.updateNoctDockTopScreenExportProfile(config.width, config.height, config.fps)
        currentExportPath = fallbackPathForRenderer(currentRendererBackend)
        NativeLibrary.clearNoctDockEncoderInputSurface()
        runCatching { encoder?.stop() }
        encoder = null
        runCatching {
            startEncoderPipeline(config, validateReceiver = false, preferSurfaceInput = false)
        }.onFailure { error ->
            exportState = NoctDockExportState.ENCODER_ERROR
            handleRuntimeError(error)
            return
        }
        exportState = NoctDockExportState.EXPORTING
        lastError = null
        NoctDockStreamWatch.event("encoder_fallback", "$message path=${currentExportPath.label}")
        showCompatibilityExportToastOnce()
    }

    private fun showCompatibilityExportToastOnce() {
        if (fallbackMessageShown) return
        fallbackMessageShown = true
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                CitraApplication.appContext,
                R.string.noctdock_3ds_mode_compatibility_export,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun onSurfaceFramePresented(presentationTimeUs: Long, exportUs: Long) {
        if (!running.get()) return
        exportFrames.incrementAndGet()
        exportFrameTimeUs.addAndGet(exportUs.coerceAtLeast(0))
    }

    private fun handleRuntimeError(error: Throwable) {
        lastError = error.message ?: error::class.java.simpleName
        Log.error("[NoctDock] Top-screen export runtime failure: ${error.message}")
        NoctDockStreamWatch.event("encoder_error", lastError ?: "Export runtime failure")
        if (currentExportPath == NoctDockExportPath.OPENGL_ENCODER_SURFACE ||
            currentExportPath == NoctDockExportPath.VULKAN_ENCODER_SURFACE
        ) {
            handleSurfaceExportFallback("Surface encoder failed: ${lastError ?: "unknown error"}")
            return
        }
        stop()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(CitraApplication.appContext, R.string.noctdock_3ds_mode_stopped, Toast.LENGTH_SHORT).show()
        }
    }

    private fun logHealth() {
        val activeEncoder = encoder ?: return
        val frameCount = exportFrames.getAndSet(0)
        val averageExportUs = averageUs(exportFrameTimeUs.getAndSet(0), frameCount)
        val averageReadbackUs = averageUs(readbackTimeUs.getAndSet(0), frameCount)
        val maxReadbackUs = readbackMaxTimeUs.getAndSet(0)
        val slow = slowReadbacks.getAndSet(0)
        val encoderMetrics = activeEncoder.snapshotMetrics()
        val packetMetrics = activeEncoder.snapshotPacketMetrics()
        val receiverFeedback = packetMetrics.receiverFeedback
        if (!receiverReachable &&
            (packetMetrics.framesSent > 0 || receiverFeedback != null) &&
            packetMetrics.sendErrors == 0L
        ) {
            receiverReachable = true
        }
        if (receiverReachable && lastError == "receiver_unreachable") {
            lastError = null
        }
        applyGameplaySafetyIfNeeded(averageReadbackUs, encoderMetrics, packetMetrics)
        val config = effectiveConfig
        val emulationStats = config?.let { emulationPerfStats() }
        val emulationLog = emulationStats?.let {
            " emuSystemFps=${"%.1f".format(it.systemFps)} emuGameFps=${"%.1f".format(it.gameFps)} " +
                "emuSpeed=${"%.1f".format(it.speed * 100.0)}% emuGpu=${"%.2f".format(it.gpuMs)}ms " +
                "emuSwap=${"%.2f".format(it.swapMs)}ms"
        }.orEmpty()
        Log.info(
            "[NoctDock] health state=$exportState frames=$frameCount avgExport=${averageExportUs}us " +
                "avgReadback=${averageReadbackUs}us slowReadbacks=$slow " +
                "rawDropped=${encoderMetrics.rawDroppedFrames} inputQueueFull=${encoderMetrics.rawQueueFullEvents} " +
                "avgInputQueue=${encoderMetrics.averageInputQueueUs}us encodedFrames=${packetMetrics.framesSent} " +
                "packets=${packetMetrics.packetsSent} bytes=${packetMetrics.bytesSent} packetDrops=${packetMetrics.droppedFrames} " +
                "packetQueueFull=${packetMetrics.queueFullEvents} sendErrors=${packetMetrics.sendErrors}$emulationLog"
        )
        if (config != null) {
            val actualExportFps = frameCount / HEALTH_INTERVAL_SECONDS.toDouble()
            NoctDockStreamWatch.update(
                NoctDockStreamWatchUpdate(
                    exportState = exportState.name,
                    rendererBackend = currentRendererBackend,
                    exportPath = currentExportPath.label,
                    encoderSurfaceActive = currentExportPath == NoctDockExportPath.OPENGL_ENCODER_SURFACE ||
                        currentExportPath == NoctDockExportPath.VULKAN_ENCODER_SURFACE,
                    secondaryWindowActive = false,
                    readbackFallbackActive = currentExportPath == NoctDockExportPath.OPENGL_READBACK_FALLBACK ||
                        currentExportPath == NoctDockExportPath.VULKAN_READBACK_FALLBACK,
                    vulkanAvailable = !currentRendererBackend.equals("Vulkan", ignoreCase = true) ||
                        currentExportPath == NoctDockExportPath.VULKAN_ENCODER_SURFACE,
                    vulkanBlocker = vulkanBlocker,
                    exportMode = config.performanceMode,
                    exportWidth = config.width,
                    exportHeight = config.height,
                    targetFps = config.fps,
                    actualExportFps = actualExportFps,
                    emulationSystemFps = emulationStats?.systemFps,
                    emulationGameFps = emulationStats?.gameFps,
                    emulationSpeed = emulationStats?.speed,
                    emulationGpuMs = emulationStats?.gpuMs,
                    emulationSwapMs = emulationStats?.swapMs,
                    exportRenderAvgMs = averageExportUs / 1000.0,
                    glReadPixelsAvgMs = averageReadbackUs / 1000.0,
                    glReadPixelsMaxMs = maxReadbackUs / 1000.0,
                    vulkanAcquireMs = null,
                    vulkanPresentMs = null,
                    encoderQueueDepth = encoderMetrics.queueDepth,
                    encoderQueueDrops = encoderMetrics.rawQueueFullEvents,
                    encoderInputAvgMs = encoderMetrics.averageInputQueueUs / 1000.0,
                    packetsSent = packetMetrics.packetsSent,
                    bytesSent = packetMetrics.bytesSent,
                    sendErrors = packetMetrics.sendErrors,
                    receiverReachable = receiverReachable,
                    receiverFps = receiverFeedback?.receivedFps,
                    receiverPacketLoss = receiverPacketLoss(receiverFeedback),
                    receiverDrops = receiverFeedback?.reassemblyDrops,
                    receiverDecoderErrors = receiverFeedback?.decoderErrors,
                    receiverQueueDepth = receiverFeedback?.queueDepth,
                    receiverAudioBufferMs = receiverFeedback?.audioBufferMs,
                    receiverAvOffsetMs = receiverFeedback?.avOffsetMs,
                    currentCodec = config.preferredCodec,
                    currentBitrate = currentBitrate,
                    activeProfile = "${config.width}x${config.height}@${config.fps}",
                    gameplayFpsImpact = (config.fps - actualExportFps).coerceAtLeast(0.0),
                    lastError = lastError
                )
            )
        }
        if (exportState == NoctDockExportState.FRAME_READBACK_TOO_SLOW && slow == 0L) {
            exportState = NoctDockExportState.EXPORTING
        }
    }

    private fun averageUs(totalUs: Long, count: Long): Long = if (count > 0) totalUs / count else 0

    private fun emulationPerfStats(): EmulationPerfStats? =
        runCatching {
            val stats = NativeLibrary.getPerfStats()
            if (stats.size < PERF_STAT_COUNT || stats[PERF_GAME_FPS] <= 0.0) {
                null
            } else {
                EmulationPerfStats(
                    systemFps = stats[PERF_SYSTEM_FPS],
                    gameFps = stats[PERF_GAME_FPS],
                    speed = stats[PERF_SPEED],
                    gpuMs = stats[PERF_TIME_GPU],
                    swapMs = stats[PERF_TIME_SWAP]
                )
            }
        }.getOrNull()

    private fun receiverPacketLoss(feedback: NoctDockReceiverFeedback?): Double? {
        feedback ?: return null
        val observedFrames = feedback.receivedFps + feedback.reassemblyDrops
        if (observedFrames <= 0) return 0.0
        return (feedback.reassemblyDrops.toDouble() / observedFrames.toDouble()) * 100.0
    }

    private fun startEncoderPipeline(
        config: NoctDockExportConfig,
        validateReceiver: Boolean,
        preferSurfaceInput: Boolean = false,
        allowHevcToAvcFallback: Boolean = true,
    ) {
        val address = config.receiverAddress?.takeIf { it.isNotBlank() }
            ?: throw NoctDockExportUnavailableException("NoctDock screen is not available. Playing normally.")
        val streamId = config.sessionId.hashCode()
        val bitrate = bitrateFor(config)
        currentBitrate = bitrate
        val sender = NoctDockUdpVideoSender(address, config.receiverPort, streamId, onError = ::handleRuntimeError)
        if (validateReceiver && !sender.validateReceiver()) {
            exportState = NoctDockExportState.RECEIVER_UNREACHABLE
            lastError = "receiver_unreachable"
            NoctDockStreamWatch.event("receiver_lost", "Receiver did not answer CONNECTION_TEST at $address:${config.receiverPort}; streaming anyway")
            Log.warning("[NoctDock] Receiver probe timed out for $address:${config.receiverPort}; continuing with video config")
        } else if (validateReceiver) {
            receiverReachable = true
            NoctDockStreamWatch.event("receiver_validated", "Receiver validated at $address:${config.receiverPort}")
        }
        val useSurfaceInput = preferSurfaceInput &&
            (currentRendererBackend.equals("OpenGL", ignoreCase = true) ||
                currentRendererBackend.equals("Vulkan", ignoreCase = true))
        val nextEncoder = NoctDockMediaCodecFrameEncoder(
            config = config,
            streamId = streamId,
            bitrate = bitrate,
            sender = sender,
            useSurfaceInput = useSurfaceInput,
            onError = { error ->
                exportState = NoctDockExportState.ENCODER_ERROR
                handleRuntimeError(error)
            }
        )
        runCatching {
            nextEncoder.start()
            val inputSurface = nextEncoder.inputSurface()
            if (inputSurface != null) {
                NativeLibrary.setNoctDockEncoderInputSurface(inputSurface)
                currentExportPath =
                    if (currentRendererBackend.equals("Vulkan", ignoreCase = true)) {
                        NoctDockExportPath.VULKAN_ENCODER_SURFACE
                    } else {
                        NoctDockExportPath.OPENGL_ENCODER_SURFACE
                    }
            } else {
                NativeLibrary.clearNoctDockEncoderInputSurface()
                currentExportPath = fallbackPathForRenderer(currentRendererBackend)
            }
        }.onFailure { error ->
            runCatching { nextEncoder.stop() }
            NativeLibrary.clearNoctDockEncoderInputSurface()
            if (allowHevcToAvcFallback && NoctDockExportCodecPolicy.isHevcPreferred(config.preferredCodec)) {
                val avcConfig = config.copy(preferredCodec = "avc")
                effectiveConfig = avcConfig
                NoctDockStreamWatch.event(
                    "codec_fallback",
                    "HEVC encoder failed; using compatibility video (AVC): ${error.message}"
                )
                showCompatibilityExportToastOnce()
                startEncoderPipeline(
                    config = avcConfig,
                    validateReceiver = false,
                    preferSurfaceInput = preferSurfaceInput,
                    allowHevcToAvcFallback = false,
                )
                return
            }
            if (useSurfaceInput) {
                NoctDockStreamWatch.event("encoder_fallback", "Surface input failed, using compatibility readback export: ${error.message}")
                currentExportPath = fallbackPathForRenderer(currentRendererBackend)
                val fallbackEncoder = NoctDockMediaCodecFrameEncoder(
                    config = config,
                    streamId = streamId,
                    bitrate = bitrate,
                    sender = sender,
                    useSurfaceInput = false,
                    onError = { failure ->
                        exportState = NoctDockExportState.ENCODER_ERROR
                        handleRuntimeError(failure)
                    }
                ).also { it.start() }
                showCompatibilityExportToastOnce()
                sender.start()
                encoder = fallbackEncoder
                return
            }
            throw error
        }
        sender.start()
        encoder = nextEncoder
    }

    private fun applyGameplaySafetyIfNeeded(
        averageReadbackUs: Long,
        encoderMetrics: EncoderMetrics,
        packetMetrics: NoctDockPacketMetrics
    ) {
        val activeConfig = effectiveConfig ?: return
        val readbackMs = averageReadbackUs / 1000.0
        val queuePressure =
            encoderMetrics.queueDepth > 0 ||
                encoderMetrics.rawQueueFullEvents > 0 ||
                packetMetrics.queueFullEvents > 0 ||
                packetMetrics.droppedFrames > 0
        val fpsLimitedConfig =
            if ((readbackMs > READBACK_FPS_CAP_MS || queuePressure) && activeConfig.fps > 30) {
                activeConfig.copy(fps = 30, performanceMode = "${activeConfig.performanceMode} Safe")
            } else {
                activeConfig
            }
        if (fpsLimitedConfig != activeConfig) {
            applySafetyConfig(fpsLimitedConfig, restartEncoder = false)
            return
        }
        if (queuePressure && readbackMs > READBACK_RESOLUTION_DROP_MS) {
            val saferMode = currentPerformanceMode.nextSafer() ?: return
            val saferConfig = activeConfig.copy(
                width = saferMode.width,
                height = saferMode.height,
                fps = saferMode.fps.coerceAtMost(30),
                performanceMode = "${saferMode.label} Safe"
            )
            currentPerformanceMode = saferMode
            applySafetyConfig(saferConfig, restartEncoder = true)
        }
    }

    private fun applySafetyConfig(config: NoctDockExportConfig, restartEncoder: Boolean) {
        effectiveConfig = config
        NativeLibrary.updateNoctDockTopScreenExportProfile(config.width, config.height, config.fps)
        showSafetyToastOnce()
        NoctDockStreamWatch.event(
            "setting_recommendation_changed",
            "Auto safety applied: ${config.performanceMode} ${config.width}x${config.height}@${config.fps}"
        )
        if (restartEncoder) {
            runCatching { encoder?.stop() }
            encoder = null
            val preferSurface = currentExportPath == NoctDockExportPath.OPENGL_ENCODER_SURFACE ||
                currentExportPath == NoctDockExportPath.VULKAN_ENCODER_SURFACE
            runCatching {
                startEncoderPipeline(
                    config,
                    validateReceiver = false,
                    preferSurfaceInput = preferSurface
                )
            }
                .onFailure { error ->
                    exportState = NoctDockExportState.ENCODER_ERROR
                    handleRuntimeError(error)
                }
        }
    }

    private fun showSafetyToastOnce() {
        if (safetyMessageShown) return
        safetyMessageShown = true
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                CitraApplication.appContext,
                R.string.noctdock_3ds_mode_safety_applied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun profileFor(
        width: Int,
        height: Int,
        fps: Int
    ): NoctDockBridgeSettings.ExportPerformanceMode =
        NoctDockBridgeSettings.ExportPerformanceMode.entries.firstOrNull {
            it.width == width && it.height == height && it.fps == fps
        } ?: NoctDockBridgeSettings.ExportPerformanceMode.BALANCED

    private fun saferFallbackConfig(config: NoctDockExportConfig): NoctDockExportConfig {
        val fallbackMode = profileFor(config.width, config.height, config.fps.coerceAtMost(30))
        currentPerformanceMode = fallbackMode
        return config.copy(
            fps = config.fps.coerceAtMost(30),
            performanceMode = "${fallbackMode.label} Compatibility"
        )
    }

    private fun bitrateFor(config: NoctDockExportConfig): Int =
        when {
            config.width <= 400 && config.height <= 240 -> 6_000_000
            config.width <= 800 && config.height <= 480 && config.fps <= 30 -> 15_000_000
            config.width <= 800 && config.height <= 480 -> 18_000_000
            config.fps <= 30 -> 18_000_000
            else -> 24_000_000
        }

    private fun fallbackPathForRenderer(rendererBackend: String): NoctDockExportPath =
        if (rendererBackend.equals("Vulkan", ignoreCase = true)) {
            NoctDockExportPath.VULKAN_READBACK_FALLBACK
        } else {
            NoctDockExportPath.OPENGL_READBACK_FALLBACK
        }

    private const val SLOW_READBACK_WARNING_US = 12_000L
    private const val READBACK_FPS_CAP_MS = 10.0
    private const val READBACK_RESOLUTION_DROP_MS = 16.0
    private const val HEALTH_INTERVAL_SECONDS = 2
    private const val PERF_SYSTEM_FPS = 0
    private const val PERF_GAME_FPS = 1
    private const val PERF_SPEED = 2
    private const val PERF_TIME_GPU = 6
    private const val PERF_TIME_SWAP = 7
    private const val PERF_STAT_COUNT = 9
    private const val VULKAN_ENCODER_SURFACE_BLOCKER =
        "Android MediaCodec exposes an ANativeWindow Surface, but this Vulkan renderer needs a dedicated VkSurfaceKHR/swapchain or Android Hardware Buffer bridge for that target. Azahar currently has only one physical secondary_window, so NoctDock cannot reuse it without breaking external display support."
}

private data class EncoderMetrics(
    val rawDroppedFrames: Long,
    val rawQueueFullEvents: Long,
    val averageInputQueueUs: Long,
    val queueDepth: Int
)

private class NoctDockMediaCodecFrameEncoder(
    private val config: NoctDockExportConfig,
    private val streamId: Int,
    private val bitrate: Int,
    private val sender: NoctDockUdpVideoSender,
    private val useSurfaceInput: Boolean,
    private val onError: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<RgbaFrame>(2)
    private val inputIndexes = ArrayBlockingQueue<Int>(16)
    private val frameCounter = AtomicLong(0)
    private val rawDroppedFrames = AtomicLong(0)
    private val rawQueueFullEvents = AtomicLong(0)
    private val inputQueueSamples = AtomicLong(0)
    private val inputQueueTimeUs = AtomicLong(0)
    private var codec: MediaCodec? = null
    private var inputThread: Thread? = null
    private var callbackThread: HandlerThread? = null
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var yuvScratch = ByteArray(0)
    private var inputSurface: Surface? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val mime = codecMime(config.preferredCodec)
        val codecInfo = chooseEncoder(mime)
        if (!useSurfaceInput) {
            colorFormat = chooseColorFormat(codecInfo, mime)
        }
        val mediaCodec = MediaCodec.createByCodecName(codecInfo.name)
        codec = mediaCodec
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (useSurfaceInput) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    colorFormat
                }
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, config.fps * 1.25f)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codecInfo.applyVideoProfile(this, mime)
        }
        val callbacks = HandlerThread("NoctDock3dsEncoder", Process.THREAD_PRIORITY_VIDEO)
        callbacks.start()
        callbackThread = callbacks
        mediaCodec.setCallback(createCallback(mime), Handler(callbacks.looper))
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (useSurfaceInput) {
            inputSurface = mediaCodec.createInputSurface()
        }
        mediaCodec.start()
        if (!useSurfaceInput) {
            inputThread = Thread({ inputLoop(mediaCodec) }, "NoctDock3dsInput").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
        Log.info(
            "[NoctDock] Encoder started with ${codecInfo.name}, mime=$mime, input=${if (useSurfaceInput) "surface" else "byte-buffer"}, color=$colorFormat, " +
                "output=${config.width}x${config.height}@${config.fps}"
        )
        NoctDockStreamWatch.event("encoder_started", "Encoder ${codecInfo.name} started for ${config.width}x${config.height}@${config.fps}")
    }

    fun offerFrame(frame: RgbaFrame) {
        if (!running.get() || useSurfaceInput) return
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()
            if (!frameQueue.offer(frame)) {
                rawDroppedFrames.incrementAndGet()
                rawQueueFullEvents.incrementAndGet()
                NoctDockStreamWatch.event("frame_dropped", "Raw frame queue full")
                return
            }
            rawDroppedFrames.incrementAndGet()
            rawQueueFullEvents.incrementAndGet()
            NoctDockStreamWatch.event("queue_full", "Dropped stale raw frame")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { inputThread?.join(250) }
        runCatching { inputSurface?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { callbackThread?.quitSafely() }
        runCatching { sender.stop() }
        codec = null
        callbackThread = null
        inputThread = null
        inputSurface = null
        yuvScratch = ByteArray(0)
        frameQueue.clear()
        inputIndexes.clear()
        Log.info("[NoctDock] Encoder stopped")
    }

    fun snapshotMetrics(): EncoderMetrics {
        val samples = inputQueueSamples.getAndSet(0)
        val queueUs = inputQueueTimeUs.getAndSet(0)
        return EncoderMetrics(
            rawDroppedFrames = rawDroppedFrames.getAndSet(0),
            rawQueueFullEvents = rawQueueFullEvents.getAndSet(0),
            averageInputQueueUs = if (samples > 0) queueUs / samples else 0,
            queueDepth = frameQueue.size
        )
    }

    fun snapshotPacketMetrics(): NoctDockPacketMetrics = sender.snapshotMetrics()

    fun inputSurface(): Surface? = inputSurface

    private fun inputLoop(mediaCodec: MediaCodec) {
        while (running.get()) {
            val frame = frameQueue.poll(20, TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                val inputIndex = inputIndexes.poll(20, TimeUnit.MILLISECONDS)
                if (inputIndex == null) {
                    rawDroppedFrames.incrementAndGet()
                    return@runCatching
                }
                val inputBuffer = mediaCodec.getInputBuffer(inputIndex) ?: return@runCatching
                inputBuffer.clear()
                inputQueueTimeUs.addAndGet((System.nanoTime() - frame.queuedAtNs) / 1000L)
                inputQueueSamples.incrementAndGet()
                writeYuv420(frame, inputBuffer)
                mediaCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    yuv420Size(frame.width, frame.height),
                    frame.presentationTimeUs,
                    0
                )
            }.onFailure(onError)
        }
    }

    private fun writeYuv420(frame: RgbaFrame, output: ByteBuffer) {
        val width = frame.width
        val height = frame.height
        val rgba = frame.bytes
        val yPlaneSize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val requiredSize = yuv420Size(width, height)
        if (yuvScratch.size != requiredSize) {
            yuvScratch = ByteArray(requiredSize)
        }
        val yuv = yuvScratch

        for (y in 0 until height) {
            for (x in 0 until width) {
                val source = ((height - 1 - y) * width + x) * 4
                val r = rgba[source].toInt() and 0xFF
                val g = rgba[source + 1].toInt() and 0xFF
                val b = rgba[source + 2].toInt() and 0xFF
                yuv[y * width + x] = clampToByte(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
            }
        }

        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                val source = ((height - 1 - (y * 2)) * width + (x * 2)) * 4
                val r = rgba[source].toInt() and 0xFF
                val g = rgba[source + 1].toInt() and 0xFF
                val b = rgba[source + 2].toInt() and 0xFF
                val u = clampToByte(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                val v = clampToByte(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                when (colorFormat) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                        val offset = yPlaneSize + (y * chromaWidth + x) * 2
                        yuv[offset] = u
                        yuv[offset + 1] = v
                    }
                    else -> {
                        val offset = y * chromaWidth + x
                        yuv[yPlaneSize + offset] = u
                        yuv[yPlaneSize + chromaWidth * chromaHeight + offset] = v
                    }
                }
            }
        }
        output.put(yuv, 0, requiredSize)
    }

    private fun createCallback(mime: String): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (useSurfaceInput) return
                if (!inputIndexes.offer(index)) {
                    runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (info.size > 0 && outputBuffer != null) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outputBuffer.get(bytes)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig) {
                            sender.sendFrame(
                                NoctDockEncodedFrame(
                                    streamId = streamId,
                                    frameId = frameCounter.getAndIncrement(),
                                    presentationTimeUs = info.presentationTimeUs,
                                    keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                                    bytes = bytes
                                )
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }.onFailure(onError)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val csd0 = format.getByteBuffer("csd-0")?.toByteArray() ?: ByteArray(0)
                val csd1 = format.getByteBuffer("csd-1")?.toByteArray() ?: ByteArray(0)
                sender.sendConfig(
                    NoctDockStreamConfig(
                        streamId = streamId,
                        width = config.width,
                        height = config.height,
                        fps = config.fps,
                        bitrate = bitrate,
                        codecConfigSps = csd0,
                        codecConfigPps = csd1,
                        mime = mime
                    )
                )
            }
        }

    private fun requestKeyFrame() {
        val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        runCatching { codec?.setParameters(params) }
    }

    private fun logDroppedFrames() {
        requestKeyFrame()
    }

    private fun codecMime(preferredCodec: String): String =
        if (NoctDockExportCodecPolicy.isHevcPreferred(preferredCodec)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

    private fun MediaCodecInfo.applyVideoProfile(format: MediaFormat, mime: String) {
        val levels =
            runCatching { getCapabilitiesForType(mime).profileLevels.toList() }.getOrDefault(emptyList())
        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            levels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
        ) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
    }

    private fun chooseEncoder(mime: String): MediaCodecInfo =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        } ?: throw NoctDockExportUnavailableException("NoctDock could not start 3DS Mode. Playing normally.")

    private fun chooseColorFormat(codecInfo: MediaCodecInfo, mime: String): Int {
        val supported = codecInfo.getCapabilitiesForType(mime).colorFormats.toSet()
        return ENCODER_COLOR_FORMATS.firstOrNull { it in supported }
            ?: throw NoctDockExportUnavailableException("NoctDock could not start 3DS Mode. Playing normally.")
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun yuv420Size(width: Int, height: Int): Int = width * height * 3 / 2

    private fun clampToByte(value: Int): Byte = value.coerceIn(0, 255).toByte()

    private companion object {
        val ENCODER_COLOR_FORMATS = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
    }
}
