// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

interface NoctDockTopScreenSource {
    fun startTopScreenExport(config: NoctDockExportConfig)
    fun stopTopScreenExport()
    fun isExporting(): Boolean
}

data class NoctDockExportConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val preferredCodec: String,
    val sessionId: String,
    val receiverAddress: String?,
    val receiverPort: Int,
    val audioMode: String,
    val performanceMode: String = "Balanced"
)

class NoctDockExportUnavailableException(message: String) : IllegalStateException(message)

class NoctDockUnavailableTopScreenSource : NoctDockTopScreenSource {
    override fun startTopScreenExport(config: NoctDockExportConfig) {
        throw NoctDockExportUnavailableException("NoctDock top-screen export is not connected to the renderer yet.")
    }

    override fun stopTopScreenExport() {
        // Nothing has been exported yet.
    }

    override fun isExporting(): Boolean = false
}
