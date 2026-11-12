package com.example.applenotes.automation

import com.example.applenotes.integrations.isPlausibleRecordName

/**
 * The public automation intent API (docs/automation.md). Action and extra
 * names are part of the contract: don't rename them.
 */
object AutomationApi {
    const val ACTION_CREATE_NOTE = "io.github.hanthor.notesoffruit.action.CREATE_NOTE"
    const val ACTION_APPEND_TO_NOTE = "io.github.hanthor.notesoffruit.action.APPEND_TO_NOTE"
    const val ACTION_OPEN_NOTE = "io.github.hanthor.notesoffruit.action.OPEN_NOTE"

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_FOLDER = "folder"
    const val EXTRA_RECORD_NAME = "record_name"
    const val EXTRA_RESULT_PENDING_INTENT = "result_pending_intent"

    /** Result extras filled into [EXTRA_RESULT_PENDING_INTENT]. */
    const val RESULT_SUCCESS = "success"
    const val RESULT_RECORD_NAME = "record_name"
    const val RESULT_ERROR = "error"

    /** Longest title / text accepted in one request (chars). */
    const val MAX_TEXT_CHARS = 100_000
    const val MAX_TITLE_CHARS = 1_000
}

/** A validated automation request. */
internal sealed interface AutomationRequest {
    data class Create(val title: String, val text: String, val folderName: String?) : AutomationRequest

    /** Exactly one of [recordName] / [title] is set. */
    data class Append(val recordName: String?, val title: String?, val text: String) : AutomationRequest

    /** Exactly one of [recordName] / [title] is set. */
    data class Open(val recordName: String?, val title: String?) : AutomationRequest
}

internal sealed interface Parsed {
    data class Ok(val request: AutomationRequest) : Parsed
    data class Invalid(val error: String) : Parsed
}

/** The extras of an automation intent, decoupled from Android's Intent for tests. */
internal data class AutomationExtras(
    val title: String? = null,
    val text: String? = null,
    val folder: String? = null,
    val recordName: String? = null,
)

internal fun parseAutomationRequest(action: String?, e: AutomationExtras): Parsed {
    val title = e.title?.trim()?.takeIf { it.isNotEmpty() }
    val text = e.text?.replace("\r\n", "\n")
    val recordName = e.recordName?.trim()?.takeIf { it.isNotEmpty() }
    if (title != null && title.length > AutomationApi.MAX_TITLE_CHARS) return Parsed.Invalid("title is too long")
    if (text != null && text.length > AutomationApi.MAX_TEXT_CHARS) return Parsed.Invalid("text is too long")
    if (recordName != null && !isPlausibleRecordName(recordName)) return Parsed.Invalid("record_name is not valid")

    fun target(): String? = when {
        recordName != null && title != null -> "give either record_name or title, not both"
        recordName == null && title == null -> "record_name or title is required"
        else -> null
    }

    return when (action) {
        AutomationApi.ACTION_CREATE_NOTE -> {
            if (title == null && text.isNullOrBlank()) return Parsed.Invalid("title or text is required")
            if (title != null && title.contains('\n')) return Parsed.Invalid("title must be a single line")
            val folder = e.folder?.trim()?.takeIf { it.isNotEmpty() }
            Parsed.Ok(AutomationRequest.Create(title.orEmpty(), text?.trim('\n').orEmpty(), folder))
        }
        AutomationApi.ACTION_APPEND_TO_NOTE -> {
            target()?.let { return Parsed.Invalid(it) }
            if (text.isNullOrBlank()) return Parsed.Invalid("text is required")
            Parsed.Ok(AutomationRequest.Append(recordName, title, text.trim('\n')))
        }
        AutomationApi.ACTION_OPEN_NOTE -> {
            target()?.let { return Parsed.Invalid(it) }
            Parsed.Ok(AutomationRequest.Open(recordName, title))
        }
        else -> Parsed.Invalid("unknown action")
    }
}
