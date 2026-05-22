// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object NoctDockAvailabilityChecker {
    fun isSenderInstalled(context: Context): Boolean =
        isPackageInstalled(context, NoctDockIntentContract.NOCTDOCK_SENDER_PACKAGE)

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
}
