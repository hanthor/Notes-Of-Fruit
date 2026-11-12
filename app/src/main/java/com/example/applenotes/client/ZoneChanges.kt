package com.example.applenotes.client

import com.example.applenotes.auth.LENIENT_JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One page of the "recents" SearchIndex query. */
data class RecentsPage(
    val notes: List<NoteSummary>,
    /** Pass back to get the next page; null when this was the last one. */
    val continuationMarker: String?,
)

/**
 * One page of CloudKit Web Services `changes/zone` for the Notes zone: what
 * changed since a sync token (or everything, without one).
 */
data class ZoneChanges(
    /** Notes that were created or changed (including ones moved to Recently Deleted). */
    val notes: List<NoteSummary>,
    /** Folders that were created or changed. */
    val folders: List<FolderSummary>,
    /**
     * Records that are gone: `deleted: true`, a Note/Folder record carrying no
     * fields at all, or one whose `Deleted` field is set. The type of a
     * deleted record is often not reported, so callers drop the name from
     * both the note and the folder cache.
     */
    val removedRecordNames: List<String>,
    /** Records of other types (Attachment, Media, SearchIndexes, …), counted for logging only. */
    val ignored: Int,
    val syncToken: String,
    val moreComing: Boolean,
)

/** The record fields the note list needs, for `desiredKeys`. */
internal val SUMMARY_KEYS = listOf("TitleEncrypted", "SnippetEncrypted", "ModificationDate", "Deleted", "Folder")

/**
 * Parses a `changes/zone` response. Pure, so it is unit tested with
 * representative JSON.
 *
 * Accepts the shape documented for `POST [path]/changes/zone` with a single
 * zone — `{ "zoneID", "records", "syncToken", "moreComing" }` — and, in case
 * the server answers in its batched form, `{ "zones": [ { …same… } ] }`.
 *
 * Throws [NotesError] (Server for a `serverErrorCode` — e.g. ZONE_NOT_FOUND,
 * BAD_REQUEST or an expired token —, Decode for anything we don't
 * understand, including a missing `syncToken`): the caller then falls back
 * to a full refetch, so a surprise here never corrupts the cache.
 */
object ZoneChangesParser {
    fun parse(raw: String): ZoneChanges {
        val root = runCatching { LENIENT_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw NotesError.Decode("changes/zone: not a JSON object", details = raw.take(4000))
        root.errorCode()?.let { throw CloudKitErrors.fromRecord(it, root.string("reason")) }
        val zone = when (val zones = root["zones"]) {
            is JsonArray -> zones.firstOrNull() as? JsonObject
                ?: throw NotesError.Decode("changes/zone: empty zones array", details = raw.take(4000))
            else -> root
        }
        zone.errorCode()?.let { throw CloudKitErrors.fromRecord(it, zone.string("reason")) }
        val token = zone.string("syncToken")
            ?: throw NotesError.Decode("changes/zone: no syncToken", details = raw.take(4000))
        val moreComing = (zone["moreComing"] as? JsonPrimitive)?.booleanOrNull ?: false
        val records = when (val r = zone["records"]) {
            null -> emptyList()
            is JsonArray -> r
            else -> throw NotesError.Decode("changes/zone: records is not an array", details = raw.take(4000))
        }

        val notes = mutableListOf<NoteSummary>()
        val folders = mutableListOf<FolderSummary>()
        val removed = mutableListOf<String>()
        var ignored = 0
        for (element in records) {
            val rec = element as? JsonObject ?: continue
            val name = rec.string("recordName") ?: continue
            if (rec.errorCode() != null) {
                // A per-record error tells us nothing about the record's state.
                ignored++
                continue
            }
            val deleted = (rec["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            val type = rec.string("recordType")
            val fields: Map<String, JsonElement>? = rec["fields"] as? JsonObject
            when {
                deleted -> removed += name
                type == "Note" -> {
                    if (fields.isNullOrEmpty()) {
                        removed += name
                    } else {
                        val summary = noteSummaryOf(name, fields)
                        if (summary.deleted) removed += name else notes += summary
                    }
                }
                type == "Folder" -> {
                    if (fields.isNullOrEmpty() || fields["Deleted"]?.let(::extractStringValue) == "1") {
                        removed += name
                    } else {
                        folders += FolderSummary(
                            recordName = name,
                            displayName = fields["TitleEncrypted"]?.let(::decodeEncryptedString),
                            isTrash = name == "TrashFolder-CloudKit",
                            isDefault = name == "DefaultFolder-CloudKit",
                        )
                    }
                }
                else -> ignored++
            }
        }
        return ZoneChanges(notes, folders, removed, ignored, token, moreComing)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.errorCode(): String? = string("serverErrorCode")
}

/** A [NoteSummary] from a Note record's fields (recents query and zone changes alike). */
internal fun noteSummaryOf(recordName: String, fields: Map<String, JsonElement>?): NoteSummary = NoteSummary(
    recordName = recordName,
    title = fields?.get("TitleEncrypted")?.let(::decodeEncryptedString),
    snippet = fields?.get("SnippetEncrypted")?.let(::decodeEncryptedString),
    modificationTimestampMs = fields?.get("ModificationDate")?.let(::extractStringValue)?.toLongOrNull(),
    deleted = fields?.get("Deleted")?.let(::extractStringValue) == "1",
    folderRecordName = fields?.get("Folder")?.let(::extractReferenceRecordName),
)
