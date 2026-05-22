// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoctDockExportCodecTest {
    @Test
    fun exportConfigCopyCanSwitchPreferredCodecToAvc() {
        val hevc =
            NoctDockExportConfig(
                width = 1280,
                height = 720,
                fps = 60,
                preferredCodec = "hevc",
                sessionId = "session",
                receiverAddress = "192.168.1.10",
                receiverPort = 45454,
                audioMode = "retroid",
            )
        val avc = hevc.copy(preferredCodec = "avc")
        assertEquals("avc", avc.preferredCodec)
        assertEquals(hevc.width, avc.width)
    }

    @Test
    fun hevcPreferredCodecStringsAreRecognized() {
        assertTrue(NoctDockExportCodecPolicy.isHevcPreferred("hevc"))
        assertTrue(NoctDockExportCodecPolicy.isHevcPreferred(MediaFormat.MIMETYPE_VIDEO_HEVC))
        assertFalse(NoctDockExportCodecPolicy.isHevcPreferred("avc"))
        assertFalse(NoctDockExportCodecPolicy.isHevcPreferred(MediaFormat.MIMETYPE_VIDEO_AVC))
    }
}
