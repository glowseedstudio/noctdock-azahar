// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.media.MediaFormat

internal object NoctDockExportCodecPolicy {
    fun isHevcPreferred(codec: String): Boolean =
        codec.equals("hevc", ignoreCase = true) ||
            codec.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
}
