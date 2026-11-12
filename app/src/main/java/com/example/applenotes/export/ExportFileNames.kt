package com.example.applenotes.export

/**
 * File names for exported notes: the note title with characters that are
 * unsafe on common file systems (and in SAF providers) replaced, trimmed to a
 * sane length, and made unique within one export.
 */
internal class ExportFileNames {
    private val used = HashSet<String>()

    /** A unique `<base>.<extension>` (case-insensitive) for [title]. */
    fun next(title: String?, extension: String): String {
        val base = sanitize(title)
        var candidate = "$base.$extension"
        var n = 2
        while (!used.add(candidate.lowercase())) {
            candidate = "$base ($n).$extension"
            n++
        }
        return candidate
    }

    /** Reserves a name that was not produced by [next] (e.g. an image file). */
    fun reserve(name: String): Boolean = used.add(name.lowercase())

    companion object {
        private const val MAX_BASE_LENGTH = 80
        private val RESERVED = setOf(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
        )

        fun sanitize(title: String?): String {
            val cleaned = buildString {
                for (c in title.orEmpty()) {
                    append(
                        when {
                            c in "/\\:*?\"<>|" -> '_'
                            c.isISOControl() || c == '\uFFFC' -> ' '
                            else -> c
                        },
                    )
                }
            }.replace(Regex("\\s+"), " ").trim().trim('.', ' ')
            var base = cleaned.take(MAX_BASE_LENGTH).trim()
            if (base.isEmpty()) base = "Untitled note"
            if (base.lowercase() in RESERVED) base = "_$base"
            return base
        }
    }
}
