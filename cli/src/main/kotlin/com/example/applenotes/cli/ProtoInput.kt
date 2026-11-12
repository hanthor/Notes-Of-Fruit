package com.example.applenotes.cli

import com.example.applenotes.proto.CompressionFormat
import com.example.applenotes.proto.Gzip
import kotlin.io.encoding.Base64

/**
 * Turns whatever the user captured into raw (decompressed) protobuf bytes.
 *
 * Accepted inputs, detected from the content (not the file name):
 *  - raw protobuf (the `core/src/testFixtures/resources/` format);
 *  - gzip or zlib compressed protobuf (`TextDataEncrypted` as stored);
 *  - base64 of either (what CloudKit JSON and `DUMP_B64` carry), with any
 *    whitespace / line breaks;
 *  - a logcat capture from the debug build's `DebugReceiver`: the
 *    `DUMP_B64[i/n] …` chunks of `DUMP_BY_TITLE`, or the
 *    `FIELD_B64 <name> [i/n] …` chunks of `DUMP_RECORD` (pick the field
 *    with `--field`, default `TextDataEncrypted`, else the first one).
 */
object ProtoInput {
    data class Loaded(
        /** Decompressed protobuf bytes. */
        val bytes: ByteArray,
        /** How the input was wrapped, for `summary`. */
        val description: String,
    )

    class InputException(message: String) : Exception(message)

    private val DUMP_CHUNK = Regex("""DUMP_B64\[(\d+)/(\d+)] (\S+)""")
    private val FIELD_CHUNK = Regex("""FIELD_B64 (\S+) \[(\d+)/(\d+)] (\S+)""")
    private val BASE64_TEXT = Regex("""^[A-Za-z0-9+/=_\-\s]+$""")

    fun load(input: ByteArray, field: String? = null): Loaded {
        if (input.isEmpty()) throw InputException("input is empty")
        // Binary input (raw or compressed protobuf) is never valid UTF-8 text
        // made only of base64 / logcat characters, so try the text forms first.
        val text = if (looksLikeText(input)) input.decodeToString() else null
        if (text != null) {
            logcatBase64(text, field)?.let { (b64, what) -> return decode(b64, what) }
            val compact = text.trim()
            if (compact.isNotEmpty() && BASE64_TEXT.matches(compact)) {
                // A binary file that happens to look like base64 falls through.
                runCatching { decode(compact, "base64") }.onSuccess { return it }
            }
        }
        return unwrap(input, "binary")
    }

    private fun decode(b64: String, what: String): Loaded {
        val clean = b64.filterNot { it.isWhitespace() }
        val bytes = try {
            // Accept the URL-safe alphabet and missing padding as well.
            val normalised = clean.replace('-', '+').replace('_', '/')
            val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
            Base64.decode(padded)
        } catch (e: IllegalArgumentException) {
            throw InputException("not valid base64 (${e.message})")
        }
        return unwrap(bytes, what)
    }

    private fun unwrap(bytes: ByteArray, what: String): Loaded {
        val format = Gzip.detect(bytes)
        val raw = try {
            Gzip.decompress(bytes)
        } catch (e: java.io.IOException) {
            throw InputException("looks like ${format.name.lowercase()} but doesn't decompress (${e.message})")
        }
        val wrap = when (format) {
            CompressionFormat.GZIP -> "gzip"
            CompressionFormat.ZLIB -> "zlib"
            CompressionFormat.NONE -> "uncompressed"
        }
        return Loaded(raw, "$what, $wrap, ${raw.size} bytes protobuf")
    }

    /** Reassembles DebugReceiver chunks; null when [text] has none. */
    private fun logcatBase64(text: String, field: String?): Pair<String, String>? {
        val dump = DUMP_CHUNK.findAll(text).map { it.groupValues }.toList()
        if (dump.isNotEmpty() && field == null) {
            return join(dump.map { it[1].toInt() to it[3] }, dump.first()[2].toInt(), "DUMP_B64") to "logcat DUMP_B64"
        }
        val fields = FIELD_CHUNK.findAll(text).map { it.groupValues }.toList()
        if (fields.isEmpty()) {
            if (field != null && dump.isNotEmpty()) {
                throw InputException("--field $field: the capture has DUMP_B64 chunks but no FIELD_B64 lines")
            }
            return null
        }
        val names = fields.map { it[1] }.distinct()
        val name = field ?: names.firstOrNull { it == "TextDataEncrypted" } ?: names.first()
        val mine = fields.filter { it[1] == name }
        if (mine.isEmpty()) throw InputException("no FIELD_B64 lines for $name (found: ${names.joinToString()})")
        return join(mine.map { it[2].toInt() to it[4] }, mine.first()[3].toInt(), "FIELD_B64 $name") to
            "logcat FIELD_B64 $name"
    }

    private fun join(chunks: List<Pair<Int, String>>, total: Int, what: String): String {
        val byIndex = chunks.toMap()
        val missing = (0 until total).filter { it !in byIndex }
        if (missing.isNotEmpty()) {
            throw InputException("$what: missing chunk(s) $missing of $total (logcat dropped lines? capture again)")
        }
        return (0 until total).joinToString("") { byIndex.getValue(it) }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 4096))
        return sample.all { b ->
            val c = b.toInt() and 0xFF
            c == 0x09 || c == 0x0A || c == 0x0D || c in 0x20..0x7E || c >= 0x80
        } && runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isSuccess
    }
}
