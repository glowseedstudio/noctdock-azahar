// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.noctdock

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import java.util.UUID

object NoctDockBridge {
    private const val KEY_PENDING_MODE = "noctdock_pending_mode"
    private const val KEY_PENDING_RECEIVER_NAME = "noctdock_pending_receiver_name"
    private const val KEY_PENDING_RECEIVER_ADDRESS = "noctdock_pending_receiver_address"
    private const val KEY_PENDING_RECEIVER_PORT = "noctdock_pending_receiver_port"
    private const val KEY_PENDING_PREFERRED_CODEC = "noctdock_pending_preferred_codec"
    private const val KEY_PENDING_SOUND_MODE = "noctdock_pending_sound_mode"
    private const val KEY_PENDING_PROMPT_USER = "noctdock_pending_prompt_user"

    fun rememberLaunchRequest(context: Context, intent: Intent?) {
        val request = NoctDockLaunchRequest.fromIntent(intent)
        if (request == null) {
            if (intent != null &&
                intent.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            ) {
                clearLaunchRequest(context)
            }
            return
        }
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(KEY_PENDING_MODE, request.route.name)
            .putString(KEY_PENDING_RECEIVER_NAME, request.receiverName)
            .putString(KEY_PENDING_RECEIVER_ADDRESS, request.receiverAddress)
            .putInt(KEY_PENDING_RECEIVER_PORT, request.receiverPort)
            .putString(KEY_PENDING_PREFERRED_CODEC, request.preferredCodec)
            .putString(KEY_PENDING_SOUND_MODE, request.soundMode)
            .putBoolean(KEY_PENDING_PROMPT_USER, request.promptUser)
            .apply()
    }

    fun resolveLaunchRequest(context: Context, intent: Intent?): NoctDockLaunchRequest? =
        NoctDockLaunchRequest.fromIntent(intent) ?: pendingLaunchRequest(context)

    fun clearLaunchRequest(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .remove(KEY_PENDING_MODE)
            .remove(KEY_PENDING_RECEIVER_NAME)
            .remove(KEY_PENDING_RECEIVER_ADDRESS)
            .remove(KEY_PENDING_RECEIVER_PORT)
            .remove(KEY_PENDING_PREFERRED_CODEC)
            .remove(KEY_PENDING_SOUND_MODE)
            .remove(KEY_PENDING_PROMPT_USER)
            .apply()
    }

    fun createTopScreenSource(): NoctDockTopScreenSource = NoctDockNativeTopScreenSource()

    fun defaultExportConfig(context: Context, request: NoctDockLaunchRequest?): NoctDockExportConfig =
        buildExportConfig(NoctDockBridgeSettings(context).resolvedExportSettings(), request)

    fun defaultExportConfig(request: NoctDockLaunchRequest?): NoctDockExportConfig {
        val balanced = NoctDockBridgeSettings.ExportPerformanceMode.BALANCED
        return buildExportConfig(
            ResolvedNoctDockExportSettings(
                width = balanced.width,
                height = balanced.height,
                fps = balanced.fps,
                performanceModeLabel = balanced.label,
            ),
            request,
        )
    }

    private fun buildExportConfig(
        resolved: ResolvedNoctDockExportSettings,
        request: NoctDockLaunchRequest?,
    ): NoctDockExportConfig =
        NoctDockExportConfig(
            width = resolved.width,
            height = resolved.height,
            fps = resolved.fps,
            preferredCodec = request?.preferredCodec?.ifBlank { DEFAULT_CODEC } ?: DEFAULT_CODEC,
            sessionId = UUID.randomUUID().toString(),
            receiverAddress = request?.receiverAddress?.ifBlank { null },
            receiverPort = request?.receiverPort?.takeIf { it > 0 } ?: DEFAULT_RECEIVER_PORT,
            audioMode = request?.soundMode?.ifBlank { DEFAULT_SOUND_MODE } ?: DEFAULT_SOUND_MODE,
            performanceMode = resolved.performanceModeLabel,
        )

    private fun pendingLaunchRequest(context: Context): NoctDockLaunchRequest? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val mode = preferences.getString(KEY_PENDING_MODE, null) ?: return null
        val route = runCatching { NoctDockScreenRoute.valueOf(mode) }.getOrNull() ?: return null
        return NoctDockLaunchRequest(
            route = route,
            receiverName = preferences.getString(KEY_PENDING_RECEIVER_NAME, null),
            receiverAddress = preferences.getString(KEY_PENDING_RECEIVER_ADDRESS, null),
            receiverPort = preferences.getInt(KEY_PENDING_RECEIVER_PORT, DEFAULT_RECEIVER_PORT),
            preferredCodec = preferences.getString(KEY_PENDING_PREFERRED_CODEC, DEFAULT_CODEC) ?: DEFAULT_CODEC,
            soundMode = preferences.getString(KEY_PENDING_SOUND_MODE, DEFAULT_SOUND_MODE) ?: DEFAULT_SOUND_MODE,
            promptUser = preferences.getBoolean(KEY_PENDING_PROMPT_USER, true)
        )
    }

    private const val DEFAULT_CODEC = "avc"
    private const val DEFAULT_SOUND_MODE = "retroid"
    private const val DEFAULT_RECEIVER_PORT = 45454
}

data class NoctDockLaunchRequest(
    val route: NoctDockScreenRoute,
    val receiverName: String?,
    val receiverAddress: String?,
    val receiverPort: Int,
    val preferredCodec: String,
    val soundMode: String,
    val promptUser: Boolean
) {
    companion object {
        fun fromIntent(intent: Intent?): NoctDockLaunchRequest? {
            if (intent == null) return null
            val mode = intent.getStringExtra(NoctDockIntentContract.EXTRA_MODE) ?: return null
            if (mode != NoctDockIntentContract.MODE_THREE_DS_TOP_SCREEN) return null
            return NoctDockLaunchRequest(
                route = NoctDockScreenRoute.THREE_DS_TOP_SCREEN,
                receiverName = intent.getStringExtra(NoctDockIntentContract.EXTRA_RECEIVER_NAME),
                receiverAddress = intent.getStringExtra(NoctDockIntentContract.EXTRA_RECEIVER_ADDRESS),
                receiverPort = intent.getIntExtra(NoctDockIntentContract.EXTRA_RECEIVER_PORT, 45454),
                preferredCodec = intent.getStringExtra(NoctDockIntentContract.EXTRA_PREFERRED_CODEC) ?: "avc",
                soundMode = intent.getStringExtra(NoctDockIntentContract.EXTRA_SOUND_MODE) ?: "retroid",
                promptUser = intent.getBooleanExtra(NoctDockIntentContract.EXTRA_PROMPT_USER, true)
            )
        }
    }
}
