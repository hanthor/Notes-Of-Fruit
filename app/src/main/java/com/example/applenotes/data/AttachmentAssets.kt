package com.example.applenotes.data

import com.example.applenotes.client.NoteRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A CKAsset as CloudKit Web Services returns it:
 * `{ "downloadURL": "...", "size": N, "fileChecksum": "..." }`, either as a
 * field's `value` (ASSETID) or as an element of a list value (ASSETID_LIST).
 */
data class AssetRef(
    val downloadUrl: String,
    val fileChecksum: String?,
    val size: Long?,
)

/** Something to download and cache: the bytes behind [downloadUrl], stored under [key]. */
data class ImageSource(
    val key: AttachmentCacheKey,
    val downloadUrl: String,
)

/**
 * Parse a CKAsset from either the field shape (`{type, value: {downloadURL…}}`)
 * or the bare asset dictionary (`{downloadURL…}`, as found inside an
 * ASSETID_LIST). Null when there's no download URL.
 */
fun parseAsset(element: JsonElement?): AssetRef? {
    val obj = element as? JsonObject ?: return null
    val asset = (obj["value"] as? JsonObject)?.takeIf { "downloadURL" in it } ?: obj
    val url = (asset["downloadURL"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: return null
    return AssetRef(
        downloadUrl = url,
        fileChecksum = (asset["fileChecksum"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() },
        size = (asset["size"] as? JsonPrimitive)?.longOrNull,
    )
}

/** Where an attachment's preview image might be found. */
sealed interface PreviewLocation {
    /** The preview asset is right on the attachment record. */
    data class Direct(val asset: AssetRef) : PreviewLocation

    /** A reference to a separate preview record that holds the asset. */
    data class Reference(val recordName: String) : PreviewLocation
}

/**
 * The `PreviewImages` entries of an attachment record. Apple generates these
 * thumbnails for images, scans, sketches, PDFs, maps, link cards….
 *
 * NOT verified against a real account: the field has been seen in record
 * key lists, but whether its value is a list of assets (ASSETID_LIST) or of
 * references to separate preview records (REFERENCE_LIST) isn't recorded, so
 * both shapes (and a single, non-list value) are accepted.
 */
fun previewLocations(record: NoteRecord): List<PreviewLocation> {
    val field = record.rawFields["PreviewImages"] ?: return emptyList()
    val value = (field as? JsonObject)?.get("value") ?: field
    val elements = (value as? JsonArray)?.toList() ?: listOf(value)
    return elements.mapNotNull { e ->
        parseAsset(e)?.let { return@mapNotNull PreviewLocation.Direct(it) }
        val obj = e as? JsonObject ?: return@mapNotNull null
        val ref = (obj["value"] as? JsonObject)?.takeIf { "recordName" in it } ?: obj
        (ref["recordName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { PreviewLocation.Reference(it) }
    }
}

/** Fields of a Media / preview record that may hold the binary, in order of preference. */
val ASSET_FIELD_CANDIDATES = listOf("MediaEncrypted", "Media", "Asset", "AssetEncrypted", "EncryptedAsset", "MediaURL")

/** The first asset found in [record]'s [ASSET_FIELD_CANDIDATES]. */
fun firstAsset(record: NoteRecord): AssetRef? =
    ASSET_FIELD_CANDIDATES.firstNotNullOfOrNull { parseAsset(record.rawFields[it]) }

/**
 * Cache key for an asset of [ownerRecordName]: the asset's checksum when
 * present, else the owning record's change tag (bytes can only change with
 * the record).
 */
fun assetCacheKey(ownerRecordName: String, variant: String, asset: AssetRef, ownerChangeTag: String?): AttachmentCacheKey =
    AttachmentCacheKey(
        recordName = ownerRecordName,
        variant = variant,
        version = asset.fileChecksum?.let { "sum:$it" } ?: "tag:${ownerChangeTag ?: "none"}",
    )

/** Pick the preview to show inline: the largest by byte size (sharpest), else the first. */
fun bestPreview(assets: List<AssetRef>): AssetRef? =
    assets.maxByOrNull { it.size ?: -1L }
