package com.example.applenotes.ui.detail

import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The editor's unsaved state, as stored in the detail ViewModel's
 * SavedStateHandle so a draft survives process death. Encoded as one JSON
 * string because [NoteBodyEditor.AttrSpan] isn't Parcelable.
 */
@Serializable
internal data class DraftSnapshot(
    val title: String,
    val body: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val spans: List<SavedSpan>,
    val viewMode: Boolean,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun decodeOrNull(s: String?): DraftSnapshot? =
            s?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

@Serializable
data class SavedSpan(
    val length: Int,
    val style: String,
    val checked: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val linkUrl: String? = null,
    val isAttachment: Boolean = false,
    val attachmentRecordName: String? = null,
    val attachmentType: String? = null,
) {
    fun toAttrSpan(): NoteBodyEditor.AttrSpan = NoteBodyEditor.AttrSpan(
        length = length,
        style = NoteBodyEditor.ParagraphStyle.valueOf(style),
        checked = checked,
        bold = bold,
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        linkUrl = linkUrl,
        isAttachment = isAttachment,
        attachmentRecordName = attachmentRecordName,
        attachmentType = attachmentType,
    )

    companion object {
        fun of(s: NoteBodyEditor.AttrSpan) = SavedSpan(
            length = s.length,
            style = s.style.name,
            checked = s.checked,
            bold = s.bold,
            italic = s.italic,
            underline = s.underline,
            strikethrough = s.strikethrough,
            linkUrl = s.linkUrl,
            isAttachment = s.isAttachment,
            attachmentRecordName = s.attachmentRecordName,
            attachmentType = s.attachmentType,
        )
    }
}
