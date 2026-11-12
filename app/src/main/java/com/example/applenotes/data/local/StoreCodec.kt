package com.example.applenotes.data.local

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.proto.NoteBodyEditor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure (JVM-testable) conversions used by the local store: a [NoteRecord] is
 * kept as the raw CloudKit record JSON (recordName, recordType,
 * recordChangeTag, fields) so nothing — TextDataEncrypted,
 * ReplicaIDToNotesVersionDataEncrypted, unknown fields — is lost between
 * caching it and saving onto it.
 */
object StoreCodec {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun encodeRecord(record: NoteRecord): String = buildJsonObject {
        put("recordName", record.recordName)
        record.recordType?.let { put("recordType", it) }
        record.recordChangeTag?.let { put("recordChangeTag", it) }
        put("fields", JsonObject(record.rawFields))
    }.toString()

    /** Null for anything that isn't a record we wrote (corrupt row, older format). */
    fun decodeRecord(json: String?): NoteRecord? {
        if (json == null) return null
        return runCatching {
            val obj = JSON.parseToJsonElement(json).jsonObject
            NoteRecord(
                recordName = obj.getValue("recordName").jsonPrimitive.content,
                recordType = (obj["recordType"] as? JsonPrimitive)?.content,
                recordChangeTag = (obj["recordChangeTag"] as? JsonPrimitive)?.content,
                rawFields = (obj["fields"] as? JsonObject)?.toMap() ?: emptyMap(),
            )
        }.getOrNull()
    }
}

/**
 * A stable text form of a span list, to compare the formatting of two drafts
 * (data-class toString is stable across processes; enum hashCodes are not).
 */
fun spansSignature(spans: List<NoteBodyEditor.AttrSpan>?): String =
    spans?.joinToString("|") { it.toString() } ?: ""
