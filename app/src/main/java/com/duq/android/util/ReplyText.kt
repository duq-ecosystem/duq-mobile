package com.duq.android.util

/**
 * Normalizes assistant reply text before it is shown to the user.
 *
 * The app subscribes to the RAW session feed (`sessions.messages.subscribe`),
 * which streams every assistant turn verbatim. The gateway strips control tokens
 * from `chat.history` (server-side `isSuppressedControlReplyText`), but the LIVE
 * stream carries them, so the client must strip the same tokens from anything it
 * renders — otherwise heartbeat acks etc. surface as junk bubbles.
 *
 *  - `NO_REPLY` — "don't surface anything", alone or appended to real text.
 *  - `HEARTBEAT_OK` — heartbeat ack token. Heartbeat runs in the MAIN session (so
 *    its proactive messages reach the app), and on quiet ticks replies HEARTBEAT_OK;
 *    the engine marks it suppressible, so it must never render. (Stripping it here
 *    is what lets heartbeat stay in main instead of an isolated session.)
 *  - `SESSION_STATUS` — display marker of the built-in `session_status` tool;
 *    leaks glued to the reply ("SESSION_STATUSПроблема — …").
 *
 * NB: tool-failure notices ("⚠️ … failed") are NOT scrubbed here — those were a
 * brittle regex (could clip real ⚠️ content); the toolResult-as-message leak is
 * fixed properly in fetchHistory (role filter).
 */
object ReplyText {

    // Control/tool sentinels that must never be shown to the user. Matched
    // case-insensitively; SESSION_STATUS can be glued directly to following text
    // (incl. Cyrillic), so it is NOT word-boundary anchored.
    private val SENTINELS = listOf(
        Regex("(?i)\\bno_reply\\b"),
        Regex("(?i)\\bheartbeat_ok\\b"),
        Regex("(?i)SESSION_STATUS"),
    )

    /** Strips control/tool sentinels and trims surrounding whitespace. */
    fun clean(text: String): String =
        SENTINELS.fold(text) { acc, re -> re.replace(acc, "") }.trim()

    /** True when the message is only sentinels and should be suppressed. */
    fun isSuppressed(text: String): Boolean = clean(text).isEmpty()
}
