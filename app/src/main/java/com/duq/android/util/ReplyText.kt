package com.duq.android.util

/**
 * Normalizes assistant reply text before it is shown to the user.
 *
 * The engine persona sometimes emits control sentinels / tool markers that leak
 * into the streamed chat text:
 *  - `NO_REPLY` — "don't surface anything", either alone or appended to real
 *    text ("Астана, без изменений.\n\nNO_REPLY").
 *  - `SESSION_STATUS` — the display marker of the built-in `session_status` tool;
 *    it leaks as a prefix glued to the reply ("SESSION_STATUSПроблема — …").
 *
 * The gateway strips pure sentinel rows from history, but streamed deltas still
 * carry them, so the client must clean anything it renders.
 */
object ReplyText {

    // Control/tool sentinels that must never be shown to the user. Matched
    // case-insensitively; SESSION_STATUS can be glued directly to following text
    // (incl. Cyrillic), so it is NOT word-boundary anchored.
    private val SENTINELS = listOf(
        Regex("(?i)\\bno_reply\\b"),
        Regex("(?i)SESSION_STATUS"),
    )

    /** Strips control/tool sentinels and trims surrounding whitespace. */
    fun clean(text: String): String =
        SENTINELS.fold(text) { acc, re -> re.replace(acc, "") }.trim()

    /** True when the message is only sentinels and should be suppressed. */
    fun isSuppressed(text: String): Boolean = clean(text).isEmpty()
}
