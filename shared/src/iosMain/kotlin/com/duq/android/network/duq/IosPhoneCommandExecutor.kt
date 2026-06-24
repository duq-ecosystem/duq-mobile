package com.duq.android.network.duq

import com.duq.android.logging.Logger

/**
 * iOS phone-control: graceful degradation. Native phone-control (camera/location/
 * screen/voice driven by the bot) requires background foreground-service + Activity
 * surfaces that iOS does not grant headlessly — there is no equivalent path. So every
 * forwarded command answers `{supported:false}` and logs it; the bot side treats this
 * as a capability that this client does not advertise.
 *
 * This is a working code path (not a stub): [DuqNodeClient] still connects, presence
 * works, and chat/reasoning streaming all function on iOS — only the device-control
 * commands degrade.
 */
class IosPhoneCommandExecutor(
    private val logger: Logger
) : PhoneCommandExecutor {
    override suspend fun execute(command: String, params: Map<*, *>): Map<String, Any?> {
        logger.w(TAG, "phone-control unavailable on iOS — declining command '$command'")
        return mapOf(
            "supported" to false,
            "command" to command,
            "reason" to "phone-control not available on iOS",
        )
    }

    private companion object { const val TAG = "PhoneCmdIos" }
}
