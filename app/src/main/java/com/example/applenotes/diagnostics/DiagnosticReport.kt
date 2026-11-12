package com.example.applenotes.diagnostics

import com.example.applenotes.client.NoteRecord
import com.example.applenotes.data.isLockedNote
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteProtoKind
import kotlin.io.encoding.Base64

/**
 * "Copy diagnostic report" (issue #22): what a bug report needs, and nothing
 * personal. Never titles, snippets, body text, attachment or record names,
 * the Apple ID, dsid or cookies: only versions, yes/no and counts.
 */
data class DiagnosticInfo(
    val appVersion: String,
    val buildType: String,
    val appId: String,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val signedIn: Boolean,
    val cachedNotes: Int,
    val cachedFolders: Int,
    val pendingDrafts: Int,
    /** The open (or last opened) note's wire shape, counts only. */
    val lastNote: NoteShape?,
)

/**
 * Counts that describe a note body's protobuf without any of its content.
 * Built here rather than with [NoteBodyEditor.summarize], which also prints
 * replica UUID prefixes and op offsets.
 */
data class NoteShape(
    val kind: String,
    val locked: Boolean,
    val compressedBytes: Int,
    val substrings: Int,
    val tombstones: Int,
    val replicas: Int,
    val attributeRuns: Int,
    val attachments: Int,
) {
    companion object {
        // topotext field numbers, as documented in docs/protocol.md.
        private const val DOCUMENT = 2
        private const val NOTE = 3
        private const val SUBSTRING = 3
        private const val SUB_TOMBSTONE = 4
        private const val REPLICA_REGISTRY = 4
        private const val REPLICA_ENTRY = 1
        private const val ATTRIBUTE_RUN = 5

        fun of(record: NoteRecord): NoteShape? {
            if (isLockedNote(record)) return NoteShape("locked", true, 0, 0, 0, 0, 0, 0)
            val b64 = record.stringField("TextDataEncrypted") ?: return null
            return runCatching { ofBase64(b64) }.getOrNull()
        }

        fun ofBase64(textDataEncryptedB64: String): NoteShape {
            val compressed = Base64.decode(textDataEncryptedB64)
            val kind = NoteBodyEditor.probeBase64(textDataEncryptedB64)
            if (kind != NoteProtoKind.NOTE_STORE_PROTO) {
                return NoteShape(kind.name, false, compressed.size, 0, 0, 0, 0, 0)
            }
            val noteFields = Wire.decode(Gzip.decompress(compressed))
                .firstOrNull { it.fieldNumber == DOCUMENT && it.wireType == Wire.LEN }
                ?.let { Wire.decode(it.payload) }
                ?.firstOrNull { it.fieldNumber == NOTE && it.wireType == Wire.LEN }
                ?.let { Wire.decode(it.payload) }
                .orEmpty()
            val subs = noteFields.filter { it.fieldNumber == SUBSTRING && it.wireType == Wire.LEN }
            val tombstones = subs.count { s ->
                Wire.decode(s.payload).any {
                    it.fieldNumber == SUB_TOMBSTONE && it.wireType == Wire.VARINT &&
                        it.varint != 0L
                }
            }
            val replicas = noteFields
                .firstOrNull { it.fieldNumber == REPLICA_REGISTRY && it.wireType == Wire.LEN }
                ?.let { reg -> Wire.decode(reg.payload).count { it.fieldNumber == REPLICA_ENTRY } }
                ?: 0
            val runs = noteFields.count { it.fieldNumber == ATTRIBUTE_RUN && it.wireType == Wire.LEN }
            val attachments = runCatching { NoteBodyEditor.parseAttributeRunsFromBase64(textDataEncryptedB64) }
                .getOrNull().orEmpty().count { it.isAttachment }
            return NoteShape(kind.name, false, compressed.size, subs.size, tombstones, replicas, runs, attachments)
        }
    }
}

/** The last note opened in this process, as counts only (set by the editor screen). */
object LastOpenedNote {
    @Volatile
    var shape: NoteShape? = null
}

/** The text that goes on the clipboard. Plain English: it's for the issue tracker. */
fun buildDiagnosticReport(info: DiagnosticInfo): String = buildString {
    appendLine("Notes of Fruit diagnostic report")
    appendLine("App: ${info.appVersion} (${info.buildType}), ${info.appId}")
    appendLine("Android: ${info.androidRelease} (API ${info.sdkInt})")
    appendLine("Device: ${info.deviceManufacturer} ${info.deviceModel}")
    appendLine("Signed in: ${if (info.signedIn) "yes" else "no"}")
    appendLine("Cache: ${info.cachedNotes} notes, ${info.cachedFolders} folders")
    appendLine("Unsent drafts: ${info.pendingDrafts}")
    val n = info.lastNote
    if (n == null) {
        appendLine("Last opened note: none")
    } else {
        append("Last opened note: kind=${n.kind}")
        if (n.locked) append(" locked=yes")
        append(" bytes=${n.compressedBytes} substrings=${n.substrings} tombstones=${n.tombstones}")
        appendLine(" replicas=${n.replicas} attr_runs=${n.attributeRuns} attachments=${n.attachments}")
    }
}

/**
 * A minimal protobuf field reader for the counts above. Kept here (instead
 * of using the proto layer's internal ProtobufWire) so diagnostics only
 * depend on the proto layer's public API.
 */
private object Wire {
    const val VARINT = 0
    const val LEN = 2

    class Field(val fieldNumber: Int, val wireType: Int, val varint: Long, val payload: ByteArray)

    fun decode(bytes: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(i < bytes.size) { "truncated varint" }
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
        while (i < bytes.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            when (val type = (key and 7).toInt()) {
                VARINT -> out += Field(number, type, readVarint(), ByteArray(0))
                LEN -> {
                    val len = readVarint().toInt()
                    require(len >= 0 && i + len <= bytes.size) { "bad length" }
                    out += Field(number, type, 0, bytes.copyOfRange(i, i + len))
                    i += len
                }
                1 -> { require(i + 8 <= bytes.size); i += 8 }
                5 -> { require(i + 4 <= bytes.size); i += 4 }
                else -> error("unsupported wire type $type")
            }
        }
        return out
    }
}
