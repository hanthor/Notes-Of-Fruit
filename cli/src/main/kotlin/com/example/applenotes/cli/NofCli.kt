package com.example.applenotes.cli

import com.example.applenotes.log.CoreLog
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.MergeableDataDecoder
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteProtoKind
import com.example.applenotes.proto.TopotextInvariants
import java.io.File
import java.io.PrintStream
import kotlin.io.encoding.Base64

/**
 * `nof-cli`: inspect captured Apple Notes protos offline. See docs/cli.md.
 * Works on files only; there is no network code in this module.
 */
object NofCli {
    enum class Command(val cliName: String, val files: Int, val summary: String) {
        SUMMARY("summary", 1, "one-line wire-shape summary (ops, replicas, attribute runs)"),
        TREE("tree", 1, "substring tree: char IDs, lengths, tombstones, children"),
        RUNS("runs", 1, "attribute runs: raw fields, then decoded formatting per run"),
        TEXT("text", 1, "visible text"),
        DIFF("diff", 2, "structural diff: substrings added/tombstoned, runs changed, replica clocks"),
        CHECK("check", 1, "run the invariants every write-path test runs (exit 1 on a violation)"),
        TABLE("table", 1, "decode a table attachment (MergeableDataEncrypted)"),
        RAW("raw", 1, "write the decompressed protobuf bytes to stdout (e.g. to save a fixture)"),
        ;

        companion object {
            fun byName(name: String): Command? = entries.firstOrNull { it.cliName == name }
        }
    }

    data class Invocation(
        val command: Command,
        val files: List<String>,
        /** check: also require a single linear chain in array order (what this app writes). */
        val strict: Boolean = false,
        /** Which FIELD_B64 field to take from a DUMP_RECORD capture. */
        val field: String? = null,
        val verbose: Boolean = false,
    )

    sealed interface Parsed {
        data class Run(val invocation: Invocation) : Parsed
        data object Help : Parsed
        data class Error(val message: String) : Parsed
    }

    const val EXIT_OK = 0
    const val EXIT_FAILED = 1
    const val EXIT_USAGE = 2

    fun usage(): String = buildString {
        appendLine("usage: nof-cli [--verbose] <command> [options] <file>...")
        appendLine()
        appendLine("Inspects a captured note body offline. <file> may be raw or gzip/zlib protobuf,")
        appendLine("base64 of either, or a logcat capture of DebugReceiver's DUMP_B64 / FIELD_B64")
        appendLine("lines; '-' reads standard input.")
        appendLine()
        appendLine("commands:")
        for (c in Command.entries) {
            val args = if (c.files == 2) "<a> <b>" else "<file>"
            appendLine("  %-22s %s".format("${c.cliName} $args", c.summary))
        }
        appendLine()
        appendLine("options:")
        appendLine("  --strict          check: also require one linear chain in array order")
        appendLine("  --field NAME      take FIELD_B64 NAME from a DUMP_RECORD capture")
        appendLine("  --verbose         print the protocol code's diagnostic log to stderr")
        appendLine("  -h, --help        show this help")
    }

    fun parse(args: List<String>): Parsed {
        var strict = false
        var verbose = false
        var field: String? = null
        val positional = ArrayList<String>()
        var i = 0
        var onlyFiles = false
        while (i < args.size) {
            val a = args[i]
            when {
                onlyFiles -> positional.add(a)
                a == "--" -> onlyFiles = true
                a == "-h" || a == "--help" -> return Parsed.Help
                a == "--strict" -> strict = true
                a == "--verbose" || a == "-v" -> verbose = true
                a == "--field" -> {
                    field = args.getOrNull(++i) ?: return Parsed.Error("--field needs a field name")
                }
                a.startsWith("--field=") -> field = a.removePrefix("--field=").ifEmpty {
                    return Parsed.Error("--field needs a field name")
                }
                a.startsWith("-") && a != "-" -> return Parsed.Error("unknown option: $a")
                else -> positional.add(a)
            }
            i++
        }
        if (positional.isEmpty()) return Parsed.Error("missing command")
        val name = positional.removeAt(0)
        if (name == "help") return Parsed.Help
        val command = Command.byName(name) ?: return Parsed.Error("unknown command: $name")
        if (positional.size != command.files) {
            val want = if (command.files == 1) "one file" else "${command.files} files"
            return Parsed.Error("${command.cliName} takes $want, got ${positional.size}")
        }
        if (strict && command != Command.CHECK) return Parsed.Error("--strict only applies to check")
        return Parsed.Run(Invocation(command, positional.toList(), strict, field, verbose))
    }

    /** Runs the CLI; returns the process exit code. */
    fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        readFile: (String) -> ByteArray = ::readInput,
    ): Int {
        val invocation = when (val p = parse(args)) {
            is Parsed.Help -> { out.print(usage()); return EXIT_OK }
            is Parsed.Error -> { err.println("nof-cli: ${p.message}"); err.print(usage()); return EXIT_USAGE }
            is Parsed.Run -> p.invocation
        }
        val savedSink = CoreLog.sink
        if (invocation.verbose) CoreLog.sink = StderrSink(err)
        return try {
            val inputs = invocation.files.map { path ->
                try {
                    ProtoInput.load(readFile(path), invocation.field)
                } catch (e: ProtoInput.InputException) {
                    err.println("nof-cli: $path: ${e.message}")
                    return EXIT_FAILED
                } catch (e: java.io.IOException) {
                    err.println("nof-cli: $path: ${e.message ?: e.javaClass.simpleName}")
                    return EXIT_FAILED
                }
            }
            execute(invocation, inputs, out, err)
        } finally {
            CoreLog.sink = savedSink
        }
    }

    private fun execute(inv: NofCli.Invocation, inputs: List<ProtoInput.Loaded>, out: PrintStream, err: PrintStream): Int {
        val bytes = inputs.first().bytes
        when (inv.command) {
            Command.SUMMARY -> {
                out.println("input: ${inputs.first().description}")
                out.println(NoteBodyEditor.summarize(bytes))
                runCatching { TopotextInvariants.parse(bytes) }.onSuccess { d ->
                    out.println(
                        "substrings=${d.subs.size} (${d.subs.count { it.tombstone }} tombstoned) " +
                            "visible.chars=${d.rawVisible().length} live.chars=${d.liveLength()} " +
                            "replicas=${d.clocks.size} runs=${d.runs.size}",
                    )
                }
            }
            Command.TREE -> out.println(NoteBodyEditor.dumpSubstringTreeBase64(b64(bytes)))
            Command.RUNS -> {
                out.print(NoteBodyEditor.dumpAttributeRunsBase64(b64(bytes)))
                val text = NoteBodyEditor.readVisibleText(bytes)
                val spans = NoteBodyEditor.parseAttributeRuns(bytes)
                out.println("decoded (${spans.size} runs):")
                var pos = 0
                for ((i, s) in spans.withIndex()) {
                    val excerpt = text?.let { t ->
                        if (pos + s.length <= t.length) quote(t.substring(pos, pos + s.length)) else "(past end of text)"
                    } ?: ""
                    out.println("  [$i] len=${s.length} ${ProtoDiff.describe(s)} $excerpt")
                    pos += s.length
                }
            }
            Command.TEXT -> {
                val text = NoteBodyEditor.readVisibleText(bytes)
                    ?: NoteBodyEditor.readText(bytes)
                    ?: run { err.println("nof-cli: no topotext body in this proto"); return EXIT_FAILED }
                out.println(text)
            }
            Command.CHECK -> {
                val kind = NoteBodyEditor.probe(bytes)
                if (kind != NoteProtoKind.NOTE_STORE_PROTO) {
                    err.println("nof-cli: not a topotext note body (kind=$kind)")
                    return EXIT_FAILED
                }
                return try {
                    TopotextInvariants.checkInvariants(bytes, strictChain = inv.strict)
                    out.println("OK: all invariants hold" + if (inv.strict) " (strict chain)" else "")
                    EXIT_OK
                } catch (e: AssertionError) {
                    out.println("FAILED: ${e.message}")
                    EXIT_FAILED
                }
            }
            Command.DIFF -> {
                val r = try {
                    ProtoDiff.diff(bytes, inputs[1].bytes)
                } catch (e: AssertionError) {
                    err.println("nof-cli: can't parse as topotext: ${e.message}")
                    return EXIT_FAILED
                } catch (e: RuntimeException) {
                    err.println("nof-cli: can't parse as topotext: ${e.message ?: e.javaClass.simpleName}")
                    return EXIT_FAILED
                }
                printDiff(r, out)
            }
            Command.RAW -> {
                out.write(bytes)
                out.flush()
            }
            Command.TABLE -> {
                val grid = MergeableDataDecoder.decodeTable(bytes)
                    ?: run { err.println("nof-cli: not a table (no ICTable object found)"); return EXIT_FAILED }
                out.println("table ${grid.rows} rows x ${grid.cols} cols")
                for (row in grid.cells) out.println(row.joinToString(" | ") { it.replace("\n", "\\n") })
            }
        }
        return EXIT_OK
    }

    private fun printDiff(r: ProtoDiff.Result, out: PrintStream) {
        if (r.identical) {
            out.println("no structural differences")
            return
        }
        out.println("text: ${r.textBefore.length} -> ${r.textAfter.length} chars" +
            if (r.textBefore == r.textAfter) " (unchanged)" else "")
        out.println("substrings: ${r.substringsBefore} -> ${r.substringsAfter}")
        section(out, "added", "+", r.added)
        section(out, "tombstoned", "-", r.tombstoned)
        section(out, "VANISHED (CRDT violation: char ids must never disappear)", "!", r.vanished)
        section(out, "RESURRECTED (CRDT violation: tombstones are final)", "!", r.resurrected)
        out.println("attribute runs: ${r.runsBefore} -> ${r.runsAfter}")
        when {
            r.attrChanges == null -> out.println("  (formatting not compared: runs don't line up with the text)")
            r.attrChanges.isEmpty() -> out.println("  formatting of kept chars unchanged")
            else -> for (c in r.attrChanges) out.println("  ~ ${quote(c.text)}: ${c.before} -> ${c.after}")
        }
        out.println("replica clocks:")
        for (c in r.clocks) {
            val id = c.uuid.take(8)
            when {
                c.before == null -> out.println("  + $id ${c.after} (new replica)")
                c.after == null -> out.println("  - $id ${c.before} (replica removed)")
                c.before == c.after -> out.println("  = $id ${c.after}")
                else -> out.println("  ~ $id ${c.before} -> ${c.after}")
            }
        }
    }

    private fun section(out: PrintStream, title: String, mark: String, ranges: List<ProtoDiff.Range>) {
        if (ranges.isEmpty()) return
        out.println("$title (${ranges.sumOf { it.count }} chars):")
        for (r in ranges) {
            val end = r.fromClock + r.count - 1
            val clocks = if (r.count == 1) "${r.fromClock}" else "${r.fromClock}..$end"
            out.println("  $mark ${r.uuid.take(8)} clock $clocks ${r.text?.let(::quote) ?: "(text not in either capture)"}")
        }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\u2028", "\\u2028")
            .replace("\uFFFC", "\\uFFFC").replace("\"", "\\\"") + "\""

    private fun b64(raw: ByteArray): String {
        // The *Base64 dump helpers decompress first; raw protobuf never starts
        // with a gzip/zlib magic, so they see it unchanged.
        check(Gzip.detect(raw) == com.example.applenotes.proto.CompressionFormat.NONE)
        return Base64.encode(raw)
    }

    private fun readInput(path: String): ByteArray =
        if (path == "-") System.`in`.readBytes() else File(path).readBytes()

    private class StderrSink(private val err: PrintStream) : CoreLog.Sink {
        override val debugEnabled get() = true
        override val verboseEnabled get() = true
        override fun write(level: CoreLog.Level, tag: String, message: String, t: Throwable?) {
            err.println("${level.name.first()}/$tag: $message" + (t?.let { " ($it)" } ?: ""))
        }
    }
}
