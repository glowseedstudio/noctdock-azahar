// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

internal data class NoctDockStreamWatchUpdate(
    val exportState: String,
    val rendererBackend: String,
    val exportPath: String,
    val encoderSurfaceActive: Boolean,
    val secondaryWindowActive: Boolean,
    val readbackFallbackActive: Boolean,
    val vulkanAvailable: Boolean,
    val vulkanBlocker: String?,
    val exportMode: String,
    val exportWidth: Int,
    val exportHeight: Int,
    val targetFps: Int,
    val actualExportFps: Double,
    val emulationSystemFps: Double?,
    val emulationGameFps: Double?,
    val emulationSpeed: Double?,
    val emulationGpuMs: Double?,
    val emulationSwapMs: Double?,
    val exportRenderAvgMs: Double,
    val glReadPixelsAvgMs: Double,
    val glReadPixelsMaxMs: Double,
    val vulkanAcquireMs: Double?,
    val vulkanPresentMs: Double?,
    val encoderQueueDepth: Int,
    val encoderQueueDrops: Long,
    val encoderInputAvgMs: Double,
    val packetsSent: Long,
    val bytesSent: Long,
    val sendErrors: Long,
    val receiverReachable: Boolean,
    val receiverFps: Int?,
    val receiverPacketLoss: Double?,
    val receiverDrops: Int?,
    val receiverDecoderErrors: Int?,
    val receiverQueueDepth: Int?,
    val receiverAudioBufferMs: Int?,
    val receiverAvOffsetMs: Int?,
    val currentCodec: String,
    val currentBitrate: Int,
    val activeProfile: String,
    val gameplayFpsImpact: Double?,
    val lastError: String?
)

internal object NoctDockStreamWatch {
    private const val PORT = 45456
    private const val MAX_EVENTS = 300
    private const val MAX_HISTORY = 300

    private val running = AtomicBoolean(false)
    private val currentSnapshot = AtomicReference(defaultSnapshot())
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "NoctDockStreamWatch") }
    private val events = ArrayDeque<JSONObject>()
    private val history = ArrayDeque<JSONObject>()
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var lastRecommendation = ""

    fun isEnabledBySettings(): Boolean =
        NoctDockBridgeSettings(CitraApplication.appContext).streamWatchEnabled

    fun start() {
        if (!isEnabledBySettings()) return
        if (!running.compareAndSet(false, true)) return
        event("stream_watch_started", "NoctDock Stream Watch started on port $PORT")
        executor.execute {
            runCatching {
                ServerSocket(PORT, 16, InetAddress.getByName("0.0.0.0")).use { server ->
                    serverSocket = server
                    while (running.get()) {
                        val client = server.accept()
                        executor.execute { handleClient(client) }
                    }
                }
            }.onFailure { error ->
                if (running.get()) {
                    event("stream_watch_error", error.message ?: "Stream Watch server error")
                    Log.warning("[NoctDock] Stream Watch server failed: ${error.message}")
                }
            }.also {
                serverSocket = null
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        event("stream_watch_stopped", "NoctDock Stream Watch stopped")
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun event(type: String, message: String) {
        val event = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("type", type)
            .put("message", message)
        synchronized(lock) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun update(update: NoctDockStreamWatchUpdate) {
        val health = healthFor(update)
        val recommendation = recommendationFor(update)
        if (recommendation != lastRecommendation) {
            lastRecommendation = recommendation
            event("setting_recommendation_changed", recommendation)
        }
        val snapshot = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("exportState", update.exportState)
            .put("rendererBackend", update.rendererBackend)
            .put("exportPath", update.exportPath)
            .put("encoderSurfaceActive", update.encoderSurfaceActive)
            .put("secondaryWindowActive", update.secondaryWindowActive)
            .put("readbackFallbackActive", update.readbackFallbackActive)
            .put("vulkanAvailable", update.vulkanAvailable)
            .put("vulkanBlocker", update.vulkanBlocker ?: JSONObject.NULL)
            .put("exportMode", update.exportMode)
            .put("exportResolution", "${update.exportWidth}x${update.exportHeight}")
            .put("targetFps", update.targetFps)
            .put("actualExportFps", round(update.actualExportFps))
            .put("encodedFps", round(update.actualExportFps))
            .put("emulationSystemFps", update.emulationSystemFps?.let { round(it) } ?: JSONObject.NULL)
            .put("emulationGameFps", update.emulationGameFps?.let { round(it) } ?: JSONObject.NULL)
            .put("emulationSpeedPercent", update.emulationSpeed?.let { round(it * 100.0) } ?: JSONObject.NULL)
            .put("emulationGpuMs", update.emulationGpuMs?.let { round(it) } ?: JSONObject.NULL)
            .put("emulationSwapMs", update.emulationSwapMs?.let { round(it) } ?: JSONObject.NULL)
            .put("readbackAvgMs", round(update.glReadPixelsAvgMs))
            .put("readbackMaxMs", round(update.glReadPixelsMaxMs))
            .put("glReadPixelsAvgMs", round(update.glReadPixelsAvgMs))
            .put("glReadPixelsMaxMs", round(update.glReadPixelsMaxMs))
            .put("vulkanExportActive", update.rendererBackend.equals("Vulkan", ignoreCase = true))
            .put("vulkanSurfaceBridgeAttempted", update.rendererBackend.equals("Vulkan", ignoreCase = true))
            .put("vulkanSurfaceBridgeSuccess", update.exportPath == "VULKAN_ENCODER_SURFACE")
            .put("vulkanSwapchainCreated", update.exportPath == "VULKAN_ENCODER_SURFACE")
            .put("vulkanAhbBridgeAttempted", false)
            .put("vulkanAhbBridgeSuccess", false)
            .put("vulkanCopyRenderAvgMs", if (update.rendererBackend.equals("Vulkan", ignoreCase = true)) round(update.exportRenderAvgMs) else JSONObject.NULL)
            .put("vulkanAcquireMs", update.vulkanAcquireMs?.let { round(it) } ?: JSONObject.NULL)
            .put("vulkanPresentMs", update.vulkanPresentMs?.let { round(it) } ?: JSONObject.NULL)
            .put("vulkanCopyAvgMs", if (update.rendererBackend.equals("Vulkan", ignoreCase = true)) round(update.glReadPixelsAvgMs) else JSONObject.NULL)
            .put("vulkanCopyMaxMs", if (update.rendererBackend.equals("Vulkan", ignoreCase = true)) round(update.glReadPixelsMaxMs) else JSONObject.NULL)
            .put("encoderQueueDepth", update.encoderQueueDepth)
            .put("encoderQueueDrops", update.encoderQueueDrops)
            .put("exportQueueDepth", update.encoderQueueDepth)
            .put("droppedExportFrames", update.encoderQueueDrops)
            .put("encoderInputAvgMs", round(update.encoderInputAvgMs))
            .put("packetsSent", update.packetsSent)
            .put("bytesSent", update.bytesSent)
            .put("sendErrors", update.sendErrors)
            .put("droppedPackets", update.sendErrors)
            .put("receiverReachable", update.receiverReachable)
            .put("receiverFps", update.receiverFps ?: JSONObject.NULL)
            .put("receiverPacketLoss", update.receiverPacketLoss?.let { round(it) } ?: JSONObject.NULL)
            .put("receiverDrops", update.receiverDrops ?: JSONObject.NULL)
            .put("receiverDecoderErrors", update.receiverDecoderErrors ?: JSONObject.NULL)
            .put("receiverQueueDepth", update.receiverQueueDepth ?: JSONObject.NULL)
            .put("receiverAudioBufferMs", update.receiverAudioBufferMs ?: JSONObject.NULL)
            .put("receiverAvOffsetMs", update.receiverAvOffsetMs ?: JSONObject.NULL)
            .put("currentCodec", update.currentCodec)
            .put("currentBitrate", update.currentBitrate)
            .put("activeProfile", update.activeProfile)
            .put("gameplayFpsImpact", update.gameplayFpsImpact ?: JSONObject.NULL)
            .put("lastError", update.lastError ?: JSONObject.NULL)
            .put("streamHealth", health)
            .put("recommendation", recommendation)
        putRefreshRateFields(snapshot)
        putBottomScreenAutoDimFields(snapshot)
        currentSnapshot.set(snapshot)
        synchronized(lock) {
            history.addLast(snapshot)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream())
            val request = reader.readLine().orEmpty()
            val path = request.split(" ").getOrNull(1).orEmpty()
            when {
                path == "/health" -> sendJson(writer, JSONObject().put("status", if (running.get()) "ok" else "stopped"))
                path == "/metrics" -> sendJson(writer, currentSnapshot.get())
                path == "/report" -> sendJson(writer, report())
                path == "/watch" -> streamWatch(writer)
                else -> {
                    writer.print("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
                    writer.flush()
                }
            }
        }
    }

    private fun sendJson(writer: PrintWriter, body: JSONObject) {
        val text = body.toString(2)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Content-Length: ${text.toByteArray().size}\r\n")
        writer.print("\r\n")
        writer.print(text)
        writer.flush()
    }

    private fun streamWatch(writer: PrintWriter) {
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/event-stream\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: keep-alive\r\n")
        writer.print("\r\n")
        writer.flush()
        while (running.get() && !writer.checkError()) {
            writer.print("data: ${currentSnapshot.get()}\n\n")
            writer.flush()
            Thread.sleep(1000)
        }
    }

    private fun report(): JSONObject {
        val historyCopy: List<JSONObject>
        val eventsCopy: List<JSONObject>
        synchronized(lock) {
            historyCopy = history.toList()
            eventsCopy = events.toList()
        }
        val avgReadback = historyCopy.mapNotNull { it.optDoubleOrNull("readbackAvgMs") ?: it.optDoubleOrNull("glReadPixelsAvgMs") }.averageOrZero()
        val maxReadback = historyCopy.mapNotNull { it.optDoubleOrNull("readbackMaxMs") ?: it.optDoubleOrNull("glReadPixelsMaxMs") }.maxOrNull() ?: 0.0
        val totalDrops = historyCopy.sumOf { it.optLong("encoderQueueDrops", 0L) }
        val totalErrors = historyCopy.sumOf { it.optLong("sendErrors", 0L) }
        val exportOffSamples = historyCopy.filter { it.optString("exportState") == "IDLE" || it.optString("exportState") == "STOPPED" }
        val openGlSamples = historyCopy.filter { it.optString("rendererBackend").equals("OpenGL", ignoreCase = true) }
        val vulkanSamples = historyCopy.filter { it.optString("rendererBackend").equals("Vulkan", ignoreCase = true) }
        return JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("summary", JSONObject()
                .put("samples", historyCopy.size)
                .put("readbackAvgMs", round(avgReadback))
                .put("readbackMaxMs", round(maxReadback))
                .put("glReadPixelsAvgMs", round(avgReadback))
                .put("glReadPixelsMaxMs", round(maxReadback))
                .put("encoderQueueDrops", totalDrops)
                .put("sendErrors", totalErrors)
                .put("latestHealth", currentSnapshot.get().optString("streamHealth"))
                .put("latestRecommendation", currentSnapshot.get().optString("recommendation"))
                .put("recommendedExportMode", recommendedMode(historyCopy)))
            .put("exportOffBaseline", summarizeSamples(exportOffSamples))
            .put("openGlExportResult", summarizeSamples(openGlSamples))
            .put("vulkanExportResult", summarizeSamples(vulkanSamples))
            .put("latest", currentSnapshot.get())
            .put("events", JSONArray(eventsCopy))
    }

    private fun putRefreshRateFields(snapshot: JSONObject) {
        val refresh = NoctDockRefreshRateHelper.status()
        snapshot
            .put("requested60Hz", refresh.requested60Hz)
            .put("activeRefreshRate", refresh.activeRefreshRateHz?.toDouble() ?: JSONObject.NULL)
            .put("refreshRateHelperResult", refresh.resultLabel())
    }

    private fun putBottomScreenAutoDimFields(snapshot: JSONObject) {
        val dim = NoctDockBottomScreenAutoDim.status()
        snapshot
            .put("bottomScreenAutoDimEnabled", dim.enabled)
            .put("bottomScreenDimMode", dim.mode.name)
            .put("bottomScreenDimmed", dim.dimmed)
            .put("idleSeconds", dim.idleSeconds)
            .put("brightnessRestoreState", dim.restoreState)
    }

    private fun healthFor(update: NoctDockStreamWatchUpdate): String =
        when {
            !update.receiverReachable || update.sendErrors > 0 -> "POOR"
            (update.receiverDecoderErrors ?: 0) > 0 || (update.receiverDrops ?: 0) > 0 -> "FAIR"
            (update.receiverQueueDepth ?: 0) > 1 -> "GOOD"
            update.glReadPixelsAvgMs > 12.0 || update.encoderQueueDrops > 5 -> "FAIR"
            update.glReadPixelsAvgMs > 8.0 || update.encoderQueueDepth > 0 -> "GOOD"
            else -> "EXCELLENT"
        }

    private fun recommendationFor(update: NoctDockStreamWatchUpdate): String =
        when {
            !update.receiverReachable -> "Check the NoctDock receiver and make sure both devices are on the same Wi-Fi/LAN."
            (update.receiverDecoderErrors ?: 0) > 0 -> "Receiver decoder errors detected. Try AVC/HEVC swap or lower bitrate."
            (update.receiverDrops ?: 0) > 0 -> "Receiver is dropping/reassembling frames. Check Wi-Fi quality or lower bitrate before lowering resolution."
            (update.receiverQueueDepth ?: 0) > 1 -> "Receiver decode queue is backing up. Prefer lower latency mode or lower bitrate."
            update.glReadPixelsAvgMs > 12.0 -> "Readback is slow. Try 30fps or a lower export resolution."
            update.encoderQueueDepth > 1 || update.encoderQueueDrops > 0 -> "Encoder queue is backing up. Lower FPS first, then lower resolution."
            update.sendErrors > 0 -> "Network sends are failing. Try a lower bitrate/profile or test both devices on the same router/Ethernet."
            update.actualExportFps >= update.targetFps * 0.95 && update.glReadPixelsAvgMs < 8.0 -> "Stream looks stable. Try the next quality step if you want more detail."
            update.encoderSurfaceActive && update.glReadPixelsAvgMs == 0.0 && update.encoderQueueDepth == 0 &&
                update.sendErrors == 0L && (update.emulationGameFps ?: 0.0) >= update.targetFps * 0.95 &&
                update.actualExportFps < update.targetFps * 0.95 ->
                "Azahar is at full game speed; NoctDock Vulkan capture is missing frames before encode."
            update.encoderSurfaceActive && update.glReadPixelsAvgMs == 0.0 && update.encoderQueueDepth == 0 && update.sendErrors == 0L ->
                "NoctDock transport is clean; FPS is limited by emulator/game frame production."
            else -> "Keep testing this profile and watch for drops, readback spikes, or send errors."
        }

    private fun defaultSnapshot(): JSONObject =
        JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("exportState", "IDLE")
            .put("rendererBackend", "OpenGL")
            .put("exportPath", "GL_READBACK_FALLBACK")
            .put("encoderSurfaceActive", false)
            .put("secondaryWindowActive", false)
            .put("readbackFallbackActive", false)
            .put("vulkanAvailable", false)
            .put("vulkanBlocker", JSONObject.NULL)
            .put("exportMode", "Off")
            .put("exportResolution", JSONObject.NULL)
            .put("targetFps", 0)
            .put("actualExportFps", 0)
            .put("encodedFps", 0)
            .put("emulationSystemFps", JSONObject.NULL)
            .put("emulationGameFps", JSONObject.NULL)
            .put("emulationSpeedPercent", JSONObject.NULL)
            .put("emulationGpuMs", JSONObject.NULL)
            .put("emulationSwapMs", JSONObject.NULL)
            .put("readbackAvgMs", 0)
            .put("readbackMaxMs", 0)
            .put("glReadPixelsAvgMs", 0)
            .put("glReadPixelsMaxMs", 0)
            .put("vulkanExportActive", false)
            .put("vulkanSurfaceBridgeAttempted", false)
            .put("vulkanSurfaceBridgeSuccess", false)
            .put("vulkanSwapchainCreated", false)
            .put("vulkanAhbBridgeAttempted", false)
            .put("vulkanAhbBridgeSuccess", false)
            .put("vulkanCopyRenderAvgMs", JSONObject.NULL)
            .put("vulkanAcquireMs", JSONObject.NULL)
            .put("vulkanPresentMs", JSONObject.NULL)
            .put("vulkanCopyAvgMs", JSONObject.NULL)
            .put("vulkanCopyMaxMs", JSONObject.NULL)
            .put("encoderQueueDepth", 0)
            .put("encoderQueueDrops", 0)
            .put("exportQueueDepth", 0)
            .put("droppedExportFrames", 0)
            .put("encoderInputAvgMs", 0)
            .put("packetsSent", 0)
            .put("bytesSent", 0)
            .put("sendErrors", 0)
            .put("droppedPackets", 0)
            .put("receiverReachable", false)
            .put("receiverFps", JSONObject.NULL)
            .put("receiverPacketLoss", JSONObject.NULL)
            .put("receiverDrops", JSONObject.NULL)
            .put("receiverDecoderErrors", JSONObject.NULL)
            .put("receiverQueueDepth", JSONObject.NULL)
            .put("receiverAudioBufferMs", JSONObject.NULL)
            .put("receiverAvOffsetMs", JSONObject.NULL)
            .put("currentCodec", JSONObject.NULL)
            .put("currentBitrate", 0)
            .put("activeProfile", JSONObject.NULL)
            .put("gameplayFpsImpact", JSONObject.NULL)
            .put("lastError", JSONObject.NULL)
            .put("streamHealth", "POOR")
            .put("recommendation", "Start NoctDock 3DS Mode with Stream Watch enabled.")
            .also {
                putRefreshRateFields(it)
                putBottomScreenAutoDimFields(it)
            }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun summarizeSamples(samples: List<JSONObject>): JSONObject {
        val avgReadback = samples.mapNotNull { it.optDoubleOrNull("readbackAvgMs") }.averageOrZero()
        val maxReadback = samples.mapNotNull { it.optDoubleOrNull("readbackMaxMs") }.maxOrNull() ?: 0.0
        return JSONObject()
            .put("samples", samples.size)
            .put("avgReadbackMs", round(avgReadback))
            .put("maxReadbackMs", round(maxReadback))
            .put("avgExportFps", round(samples.mapNotNull { it.optDoubleOrNull("actualExportFps") }.averageOrZero()))
            .put("drops", samples.sumOf { it.optLong("droppedExportFrames", 0L) })
            .put("sendErrors", samples.sumOf { it.optLong("sendErrors", 0L) })
            .put("latestMode", samples.lastOrNull()?.optString("exportMode") ?: JSONObject.NULL)
    }

    private fun recommendedMode(samples: List<JSONObject>): String {
        val latest = samples.lastOrNull() ?: return "Balanced"
        val readback = latest.optDouble("readbackAvgMs", 0.0)
        val drops = latest.optLong("droppedExportFrames", 0L)
        val errors = latest.optLong("sendErrors", 0L)
        return when {
            readback > 16.0 || drops > 10 || errors > 0 -> "Battery / Safe"
            readback > 10.0 || drops > 0 -> "Balanced"
            latest.optString("exportMode").contains("Experimental", ignoreCase = true) -> "TV"
            else -> latest.optString("exportMode", "Balanced")
        }
    }

    private fun Iterable<Double>.averageOrZero(): Double =
        if (count() == 0) 0.0 else average()

    private fun round(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
