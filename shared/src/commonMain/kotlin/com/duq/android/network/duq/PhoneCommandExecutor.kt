package com.duq.android.network.duq

/**
 * Transport-agnostic executor of native phone-control commands (bot → phone).
 *
 * The core forwards native commands over the bidirectional /duq/ws socket
 * ({type:"phone.command", command, params}); [DuqNodeClient] frames them and
 * delegates the *how-to-do-it* here. The command set (location / notify / voice /
 * camera / screen) is the phone's capability surface.
 *
 * Multiplatform contract (commonMain). Concrete implementations:
 *  - androidMain: full capability — CameraX, FusedLocation, MediaProjection, on-device
 *    STT (wired in the Android app where the platform collaborators live).
 *  - iosMain: [IosPhoneCommandExecutor] — graceful degradation (phone-control is not
 *    available on iOS), every command answers "not supported" and logs it.
 */
interface PhoneCommandExecutor {
    /** Run a forwarded command and return its result payload. Throws on failure. */
    suspend fun execute(command: String, params: Map<*, *>): Map<String, Any?>

    companion object {
        /** Commands the phone can serve. The core gates on this surface. */
        val SUPPORTED = setOf(
            "location.get", "notify.show", "voice.activate", "camera.snap", "screen.record"
        )
    }
}
