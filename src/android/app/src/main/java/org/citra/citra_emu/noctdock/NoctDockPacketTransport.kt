// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import org.citra.citra_emu.utils.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

internal data class NoctDockPacketMetrics(
    val packetsSent: Long,
    val bytesSent: Long,
    val framesSent: Long,
    val droppedFrames: Long,
    val queueFullEvents: Long,
    val sendErrors: Long,
    val receiverFeedback: NoctDockReceiverFeedback?
)

internal data class NoctDockReceiverFeedback(
    val receivedFps: Int,
    val reassemblyDrops: Int,
    val decoderErrors: Int,
    val queueDepth: Int,
    val audioPacketsReceived: Int,
    val audioUnderruns: Int,
    val audioDrops: Int,
    val audioBufferMs: Int,
    val avOffsetMs: Int
)

internal data class NoctDockStreamConfig(
    val streamId: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codecConfigSps: ByteArray,
    val codecConfigPps: ByteArray,
    val mime: String,
    val sourceType: Int = 1,
    val sourceAppName: String = "NoctDock Azahar",
    val sourceMode: String = "THREE_DS_TOP_SCREEN",
    val friendlyTitle: String = "3DS Top Screen",
    val friendlySubtitle: String = "Touch stays on handheld"
)

internal data class NoctDockEncodedFrame(
    val streamId: Int,
    val frameId: Long,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
    val bytes: ByteArray
)

internal object NoctDockPacketCodec {
    private const val MAGIC = 0x4E44564F
    private const val VERSION = 1
    private const val HEADER_SIZE = 42
    private const val MAX_DATAGRAM_SIZE = 1400
    private const val MAX_FRAGMENT_PAYLOAD = MAX_DATAGRAM_SIZE - HEADER_SIZE
    private const val TYPE_CONFIG = 1
    private const val TYPE_VIDEO_FRAGMENT = 2
    private const val TYPE_HEARTBEAT = 3
    private const val TYPE_STOP = 4
    private const val TYPE_RECEIVER_FEEDBACK = 5
    private const val TYPE_CONNECTION_TEST = 10
    private const val FLAG_KEY_FRAME = 1
    private const val FLAG_CODEC_CONFIG = 1 shl 1
    private const val LATENCY_BALANCED = 1
    private const val MAX_SOURCE_METADATA_STRING_BYTES = 160
    private const val RECEIVER_FEEDBACK_PAYLOAD_SIZE = 4 * 9

    fun encodeConfig(config: NoctDockStreamConfig): ByteArray {
        val mimeBytes = config.mime.encodeToByteArray()
        val sourceAppBytes = config.sourceAppName.encodeToByteArray().takeBoundedSourceBytes()
        val sourceModeBytes = config.sourceMode.encodeToByteArray().takeBoundedSourceBytes()
        val friendlyTitleBytes = config.friendlyTitle.encodeToByteArray().takeBoundedSourceBytes()
        val friendlySubtitleBytes = config.friendlySubtitle.encodeToByteArray().takeBoundedSourceBytes()
        val metadataSize = 4 + 4 + sourceAppBytes.size + 4 + sourceModeBytes.size + 4 + friendlyTitleBytes.size + 4 + friendlySubtitleBytes.size
        val payload = ByteBuffer
            .allocate(4 * 8 + mimeBytes.size + config.codecConfigSps.size + config.codecConfigPps.size + metadataSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(config.width)
            .putInt(config.height)
            .putInt(config.fps)
            .putInt(config.bitrate)
            .putInt(mimeBytes.size)
            .put(mimeBytes)
            .putInt(config.codecConfigSps.size)
            .put(config.codecConfigSps)
            .putInt(config.codecConfigPps.size)
            .put(config.codecConfigPps)
            .putInt(LATENCY_BALANCED)
            .putInt(config.sourceType)
            .putBoundedString(sourceAppBytes)
            .putBoundedString(sourceModeBytes)
            .putBoundedString(friendlyTitleBytes)
            .putBoundedString(friendlySubtitleBytes)
            .array()
        return encodePacket(
            type = TYPE_CONFIG,
            streamId = config.streamId,
            frameId = 0,
            fragmentIndex = 0,
            fragmentCount = 1,
            timestampUs = 0,
            flags = FLAG_CODEC_CONFIG,
            payload = payload
        )
    }

    fun forEachFrameFragment(frame: NoctDockEncodedFrame, consume: (ByteArray) -> Unit) {
        val fragmentCount = ceil(frame.bytes.size / MAX_FRAGMENT_PAYLOAD.toDouble()).toInt().coerceAtLeast(1)
        for (index in 0 until fragmentCount) {
            val start = index * MAX_FRAGMENT_PAYLOAD
            val end = minOf(start + MAX_FRAGMENT_PAYLOAD, frame.bytes.size)
            val payload = frame.bytes.copyOfRange(start, end)
            consume(
                encodePacket(
                    type = TYPE_VIDEO_FRAGMENT,
                    streamId = frame.streamId,
                    frameId = frame.frameId,
                    fragmentIndex = index,
                    fragmentCount = fragmentCount,
                    timestampUs = frame.presentationTimeUs,
                    flags = if (frame.keyFrame) FLAG_KEY_FRAME else 0,
                    payload = payload
                )
            )
        }
    }

    fun encodeHeartbeat(streamId: Int): ByteArray =
        encodePacket(TYPE_HEARTBEAT, streamId, 0, 0, 1, System.nanoTime() / 1000L, 0, ByteArray(0))

    fun encodeStop(streamId: Int): ByteArray =
        encodePacket(TYPE_STOP, streamId, 0, 0, 1, 0, 0, ByteArray(0))

    fun encodeConnectionTest(streamId: Int, testId: Int, sentAtUs: Long): ByteArray {
        val payloadSize = 52
        val payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.BIG_ENDIAN)
            .putInt(testId)
            .putInt(0)
            .putLong(1L)
            .putLong(sentAtUs)
            .putInt(0)
            .putInt(1)
            .putInt(1)
            .putInt(payloadSize)
            .putInt(1)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        return encodePacket(TYPE_CONNECTION_TEST, streamId, 1, 0, 1, sentAtUs, 0, payload)
    }

    fun isConnectionTestEcho(bytes: ByteArray, length: Int, streamId: Int, testId: Int): Boolean {
        if (length < HEADER_SIZE + 52) return false
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) return false
        if (buffer.get().toInt() != VERSION) return false
        if (buffer.get().toInt() != TYPE_CONNECTION_TEST) return false
        if (buffer.int != streamId) return false
        buffer.long
        buffer.int
        buffer.int
        buffer.long
        buffer.int
        val payloadSize = buffer.int
        if (payloadSize < 52 || payloadSize > length - HEADER_SIZE) return false
        return buffer.int == testId &&
            buffer.int == 0 &&
            buffer.long == 1L &&
            buffer.long > 0L &&
            buffer.int == 1
    }

    fun decodeReceiverFeedback(bytes: ByteArray, length: Int, expectedStreamId: Int): NoctDockReceiverFeedback? {
        if (length < HEADER_SIZE + RECEIVER_FEEDBACK_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) return null
        if (buffer.get().toInt() != VERSION) return null
        if (buffer.get().toInt() != TYPE_RECEIVER_FEEDBACK) return null
        if (buffer.int != expectedStreamId) return null
        buffer.long
        buffer.int
        buffer.int
        buffer.long
        buffer.int
        if (buffer.int != RECEIVER_FEEDBACK_PAYLOAD_SIZE) return null
        return NoctDockReceiverFeedback(
            receivedFps = buffer.int,
            reassemblyDrops = buffer.int,
            decoderErrors = buffer.int,
            queueDepth = buffer.int,
            audioPacketsReceived = buffer.int,
            audioUnderruns = buffer.int,
            audioDrops = buffer.int,
            audioBufferMs = buffer.int,
            avOffsetMs = buffer.int
        )
    }

    private fun encodePacket(
        type: Int,
        streamId: Int,
        frameId: Long,
        fragmentIndex: Int,
        fragmentCount: Int,
        timestampUs: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(type.toByte())
            .putInt(streamId)
            .putLong(frameId)
            .putInt(fragmentIndex)
            .putInt(fragmentCount)
            .putLong(timestampUs)
            .putInt(flags)
            .putInt(payload.size)
            .put(payload)
            .array()

    private fun ByteArray.takeBoundedSourceBytes(): ByteArray =
        if (size <= MAX_SOURCE_METADATA_STRING_BYTES) this else copyOf(MAX_SOURCE_METADATA_STRING_BYTES)

    private fun ByteBuffer.putBoundedString(bytes: ByteArray): ByteBuffer =
        putInt(bytes.size).put(bytes)
}

internal class NoctDockUdpVideoSender(
    private val host: String,
    private val port: Int,
    private val streamId: Int,
    private val onError: (Throwable) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val pendingTasks = AtomicInteger(0)
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val framesSent = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val queueFullEvents = AtomicLong(0)
    private val sendErrors = AtomicLong(0)
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var executor: ScheduledExecutorService? = null
    private var feedbackThread: Thread? = null
    @Volatile
    private var receiverFeedback: NoctDockReceiverFeedback? = null
    @Volatile
    private var latestConfig: NoctDockStreamConfig? = null
    private var configSent = false
    fun validateReceiver(): Boolean =
        runCatching {
            DatagramSocket().use { probeSocket ->
                probeSocket.soTimeout = RECEIVER_PROBE_TIMEOUT_MS
                val target = InetAddress.getByName(host)
                val testId = ((System.nanoTime() xor streamId.toLong()) and 0x7FFFFFFF).toInt()
                val probe = NoctDockPacketCodec.encodeConnectionTest(
                    streamId = streamId,
                    testId = testId,
                    sentAtUs = System.nanoTime() / 1000L
                )
                probeSocket.send(DatagramPacket(probe, probe.size, target, port))
                val responseBytes = ByteArray(512)
                val response = DatagramPacket(responseBytes, responseBytes.size)
                probeSocket.receive(response)
                NoctDockPacketCodec.isConnectionTestEcho(response.data, response.length, streamId, testId)
            }
        }.onFailure { error ->
            Log.warning("[NoctDock] Receiver probe failed for $host:$port: ${error.message}")
        }.getOrDefault(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket = DatagramSocket().apply {
            sendBufferSize = 1_048_576
            trafficClass = 0x10
        }
        address = InetAddress.getByName(host)
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "NoctDockUdpSender").apply { priority = Thread.NORM_PRIORITY + 1 }
        }.also { worker ->
            worker.scheduleAtFixedRate({
                if (running.get()) {
                    sendBytes(NoctDockPacketCodec.encodeHeartbeat(streamId))
                }
            }, 500, 500, TimeUnit.MILLISECONDS)
        }
        Log.info("[NoctDock] Packet sender started for $host:$port stream=$streamId")
    }

    fun sendConfig(config: NoctDockStreamConfig) {
        latestConfig = config
        configSent = false
        post {
            sendBytes(NoctDockPacketCodec.encodeConfig(config))
            configSent = true
        }
    }

    fun sendFrame(frame: NoctDockEncodedFrame) {
        if (!running.get()) return
        if (pendingTasks.get() >= MAX_PENDING_SENDS) {
            droppedFrames.incrementAndGet()
            queueFullEvents.incrementAndGet()
            NoctDockStreamWatch.event("queue_full", "Encoded packet queue full")
            return
        }
        pendingTasks.incrementAndGet()
        post {
            runCatching {
                val config = latestConfig
                if (config != null && (!configSent || frame.keyFrame)) {
                    sendBytes(NoctDockPacketCodec.encodeConfig(config))
                    configSent = true
                }
                NoctDockPacketCodec.forEachFrameFragment(frame) { sendBytes(it) }
                framesSent.incrementAndGet()
            }.also {
                pendingTasks.decrementAndGet()
            }
        }
    }

    fun stop() {
        if (running.getAndSet(false)) {
            runCatching { sendBytes(NoctDockPacketCodec.encodeStop(streamId)) }
        }
        runCatching { socket?.close() }
        runCatching { executor?.shutdownNow() }
        feedbackThread = null
        socket = null
        address = null
        executor = null
        receiverFeedback = null
        Log.info("[NoctDock] Packet sender stopped")
    }

    fun snapshotMetrics(): NoctDockPacketMetrics =
        NoctDockPacketMetrics(
            packetsSent = packetsSent.getAndSet(0),
            bytesSent = bytesSent.getAndSet(0),
            framesSent = framesSent.getAndSet(0),
            droppedFrames = droppedFrames.getAndSet(0),
            queueFullEvents = queueFullEvents.getAndSet(0),
            sendErrors = sendErrors.getAndSet(0),
            receiverFeedback = receiverFeedback
        )

    private fun post(block: () -> Unit) {
        executor?.execute {
            if (running.get()) block()
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        val activeSocket = socket ?: return
        val targetAddress = address ?: return
        runCatching {
            activeSocket.send(DatagramPacket(bytes, bytes.size, targetAddress, port))
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(bytes.size.toLong())
        }.onFailure { error ->
            sendErrors.incrementAndGet()
            Log.warning("[NoctDock] Packet send failed: ${error.message}")
            NoctDockStreamWatch.event("packet_send_error", error.message ?: "Packet send failed")
            if (running.get()) {
                onError(error)
            }
        }
    }

    private fun receiveFeedbackLoop() {
        val buffer = ByteArray(512)
        var feedbackSeen = false
        while (running.get()) {
            val activeSocket = socket ?: break
            runCatching {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                NoctDockPacketCodec.decodeReceiverFeedback(packet.data, packet.length, streamId)?.let { feedback ->
                    receiverFeedback = feedback
                    if (!feedbackSeen) {
                        feedbackSeen = true
                        NoctDockStreamWatch.event(
                            "receiver_feedback",
                            "Receiver feedback active: render=${feedback.receivedFps}fps drops=${feedback.reassemblyDrops} decoderErrors=${feedback.decoderErrors}"
                        )
                    }
                }
            }.onFailure { error ->
                if (running.get() && error !is java.net.SocketTimeoutException && error !is java.net.SocketException) {
                    Log.warning("[NoctDock] Receiver feedback read failed: ${error.message}")
                }
            }
        }
    }

    private companion object {
        const val MAX_PENDING_SENDS = 4
        const val RECEIVER_PROBE_TIMEOUT_MS = 350
        const val RECEIVER_FEEDBACK_TIMEOUT_MS = 500
    }
}
