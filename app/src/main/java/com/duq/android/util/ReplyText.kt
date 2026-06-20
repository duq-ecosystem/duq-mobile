package com.duq.android.util

/**
 * Normalizes assistant reply text before it is shown to the user.
 *
 * The app subscribes to the RAW session feed (`sessions.messages.subscribe`),
 * which broadcasts every frame of the `main` session — including the heartbeat
 * self-poll that runs in the main session (it needs full context for proactive
 * messages). The gateway strips control sentinels from `chat.history` and from
 * channel delivery, but the live WS stream carries them verbatim, so the client
 * must strip them from anything it renders.
 *
 * Stripped here — control sentinels only (точечные строки, не эвристика по тексту):
 *  - `NO_REPLY` — "don't surface anything", alone or appended to real text
 *    ("Астана, без изменений.\n\nNO_REPLY").
 *  - `HEARTBEAT_OK` — heartbeat ack sentinel; leaks from the main-session feed.
 *  - `SESSION_STATUS` — display marker of the built-in `session_status` tool;
 *    leaks glued to the reply ("SESSION_STATUSПроблема — …").
 *
 * NB: tool-failure notices ("⚠️ … failed") are NOT scrubbed here anymore — they
 * are suppressed at the source via `heartbeat.suppressToolErrorWarnings` on the
 * gateway (config/openclaw.json), instead of a brittle text regex that could clip
 * legitimate ⚠️ content. A real failure in a user-facing run (e.g. morning-brief)
 * still surfaces — that's intended signal, not noise.
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

    // Collapse blank-line runs left behind after stripping a mid-text sentinel.
    private val BLANK_RUNS = Regex("\\n{3,}")

    /** Strips control sentinels and tidies surrounding whitespace. */
    fun clean(text: String): String {
        val stripped = SENTINELS.fold(text) { acc, re -> re.replace(acc, "") }
        return BLANK_RUNS.replace(stripped, "\n\n").trim()
    }

    /** True when the message is only sentinels and should be suppressed. */
    fun isSuppressed(text: String): Boolean = clean(text).isEmpty()
}
