package com.example.applenotes.cli

import com.example.applenotes.proto.CompressionFormat
import com.example.applenotes.proto.Fx
import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.TopotextInvariants
import com.example.applenotes.proto.loadFixture
import com.example.applenotes.proto.testUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.encoding.Base64

class NofCliTest {
    private val us = testUuid(1)
    private val them = testUuid(2)

    private class Out(val code: Int, val out: String, val err: String)

    /** Runs the CLI against in-memory "files". */
    private fun cli(vararg args: String, files: Map<String, ByteArray> = emptyMap()): Out {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = NofCli.run(args.toList(), PrintStream(o, true), PrintStream(e, true)) { path ->
            files[path] ?: throw java.io.FileNotFoundException("$path (No such file or directory)")
        }
        return Out(code, o.toString(), e.toString())
    }

    // --- argument parsing --------------------------------------------------

    @Test
    fun parsesCommandsAndOptions() {
        val p = NofCli.parse(listOf("--verbose", "check", "--strict", "a.proto")) as NofCli.Parsed.Run
        assertEquals(NofCli.Command.CHECK, p.invocation.command)
        assertEquals(listOf("a.proto"), p.invocation.files)
        assertTrue(p.invocation.strict)
        assertTrue(p.invocation.verbose)

        val d = NofCli.parse(listOf("diff", "a", "b", "--field", "TextDataEncrypted")) as NofCli.Parsed.Run
        assertEquals(listOf("a", "b"), d.invocation.files)
        assertEquals("TextDataEncrypted", d.invocation.field)
        val eq = NofCli.parse(listOf("table", "--field=MergeableDataEncrypted", "-")) as NofCli.Parsed.Run
        assertEquals("MergeableDataEncrypted", eq.invocation.field)
        assertEquals(listOf("-"), eq.invocation.files)
        // "--" ends options, so a file may start with a dash.
        val dash = NofCli.parse(listOf("text", "--", "-odd-name")) as NofCli.Parsed.Run
        assertEquals(listOf("-odd-name"), dash.invocation.files)
    }

    @Test
    fun rejectsBadUsage() {
        fun err(vararg a: String) = (NofCli.parse(a.toList()) as NofCli.Parsed.Error).message
        assertEquals("missing command", err())
        assertEquals("unknown command: frobnicate", err("frobnicate", "x"))
        assertEquals("diff takes 2 files, got 1", err("diff", "a"))
        assertEquals("summary takes one file, got 2", err("summary", "a", "b"))
        assertEquals("unknown option: --nope", err("summary", "--nope", "a"))
        assertEquals("--field needs a field name", err("summary", "a", "--field"))
        assertEquals("--strict only applies to check", err("summary", "--strict", "a"))
        assertEquals(NofCli.Parsed.Help, NofCli.parse(listOf("--help")))
        assertEquals(NofCli.Parsed.Help, NofCli.parse(listOf("help")))
    }

    @Test
    fun usageErrorsExitWith2AndPrintUsage() {
        val r = cli("diff", "a")
        assertEquals(NofCli.EXIT_USAGE, r.code)
        assertTrue(r.err.contains("usage: nof-cli"))
        val help = cli("-h")
        assertEquals(0, help.code)
        for (c in NofCli.Command.entries) assertTrue(c.cliName, help.out.contains("  ${c.cliName} "))
    }

    @Test
    fun missingFileIsReportedNotThrown() {
        val r = cli("summary", "nope.proto")
        assertEquals(NofCli.EXIT_FAILED, r.code)
        assertTrue(r.err, r.err.contains("nope.proto"))
    }

    // --- input formats -----------------------------------------------------

    @Test
    fun acceptsRawCompressedBase64AndLogcatCaptures() {
        val raw = Fx.create("Hello\nworld", us)
        val gz = Gzip.compress(raw, CompressionFormat.GZIP)
        val zl = Gzip.compress(raw, CompressionFormat.ZLIB)
        val b64 = Base64.encode(zl)
        val chunks = b64.chunked(10)
        val logcat = buildString {
            appendLine("11-12 10:00:00.000  1234  1234 I AppleNotesDebug: DUMP_KIND NOTE_STORE_PROTO")
            appendLine("11-12 10:00:00.000  1234  1234 I AppleNotesDebug: DUMP_B64_BEGIN total=${b64.length} chunks=${chunks.size}")
            // Out of order on purpose: chunks are reassembled by index.
            for ((i, c) in chunks.withIndex().reversed()) {
                appendLine("11-12 10:00:00.001  1234  1234 I AppleNotesDebug: DUMP_B64[$i/${chunks.size}] $c")
            }
            appendLine("11-12 10:00:00.002  1234  1234 I AppleNotesDebug: DUMP_B64_END")
        }
        val inputs = mapOf(
            "raw" to raw,
            "gz" to gz,
            "zl" to zl,
            "b64" to (b64.chunked(60).joinToString("\n") + "\n").toByteArray(),
            "log" to logcat.toByteArray(),
        )
        for (name in inputs.keys) {
            val r = cli("text", name, files = inputs)
            assertEquals(name + r.err, 0, r.code)
            assertEquals(name, "Hello\nworld\n", r.out)
        }
        val s = cli("summary", "log", files = inputs)
        assertTrue(s.out, s.out.contains("input: logcat DUMP_B64, zlib"))
        assertTrue(cli("summary", "gz", files = inputs).out.contains("input: binary, gzip"))
    }

    @Test
    fun picksAFieldFromADumpRecordCapture() {
        val body = Base64.encode(Gzip.compress(Fx.create("body", us)))
        val other = Base64.encode(Fx.create("other", us))
        val log = """
            I AppleNotesDebug: FIELD_B64 TitleEncrypted [0/1] ${Base64.encode("t".toByteArray())}
            I AppleNotesDebug: FIELD_B64 TextDataEncrypted [0/1] $body
            I AppleNotesDebug: FIELD_B64 Other [0/1] $other
        """.trimIndent().toByteArray()
        val files = mapOf("rec" to log)
        assertEquals("body\n", cli("text", "rec", files = files).out)
        assertEquals("other\n", cli("text", "--field", "Other", "rec", files = files).out)
        val missing = cli("text", "--field", "Nope", "rec", files = files)
        assertEquals(1, missing.code)
        assertTrue(missing.err, missing.err.contains("no FIELD_B64 lines for Nope"))
    }

    @Test
    fun missingLogcatChunkIsAnError() {
        val log = "DUMP_B64[0/2] AAAA\n".toByteArray()
        val r = cli("text", "log", files = mapOf("log" to log))
        assertEquals(1, r.code)
        assertTrue(r.err, r.err.contains("missing chunk(s) [1] of 2"))
    }

    // --- summary / check / diff on fixtures ---------------------------------

    @Test
    fun summaryOfTheMacFixture() {
        val r = cli("summary", "f", files = mapOf("f" to loadFixture("fmt-baseline-1.proto")))
        assertEquals(r.err, 0, r.code)
        val lines = r.out.lines()
        assertTrue(lines[0], lines[0].startsWith("input: binary, uncompressed"))
        assertTrue(lines[1], lines[1].startsWith("kind=NOTE_STORE_PROTO"))
        assertTrue(lines[1], lines[1].contains(" replicas=") && lines[1].contains(" attr_runs="))
        assertTrue(lines[2], lines[2].startsWith("substrings="))
    }

    @Test
    fun checkPassesOnHealthyProtos() {
        val files = mapOf(
            "mac" to loadFixture("fmt-baseline-1.proto"),
            "ours" to NoteAppender.appendBytes(Fx.create("Title\nbody", us), us, "\nmore", 0),
        )
        val mac = cli("check", "mac", files = files)
        assertEquals(mac.out, 0, mac.code)
        assertTrue(mac.out.startsWith("OK"))
        val ours = cli("check", "--strict", "ours", files = files)
        assertEquals(ours.out, 0, ours.code)
        assertTrue(ours.out.contains("strict"))
    }

    @Test
    fun checkReportsAViolation() {
        // Attribute runs that cover more text than there is.
        val broken = Fx.withRuns(Fx.create("abc", us), listOf(Fx.run(10, Fx.style(3))))
        val r = cli("check", "b", files = mapOf("b" to broken))
        assertEquals(NofCli.EXIT_FAILED, r.code)
        assertTrue(r.out, r.out.startsWith("FAILED: attribute-run lengths sum to visible length"))
    }

    @Test
    fun checkRefusesNonTopotext() {
        val r = cli("check", "x", files = mapOf("x" to byteArrayOf(0x08, 0x01)))
        assertEquals(1, r.code)
        assertTrue(r.err, r.err.contains("not a topotext note body"))
    }

    @Test
    fun diffShowsInsertedAndTombstonedChars() {
        val a = Fx.create("Hello world", them)
        val b = NoteAppender.setBodySpliceBytes(a, us, "Hello brave new world", 0)
        val c = NoteAppender.setBodySpliceBytes(b, us, "Hello world", 0)
        TopotextInvariants.checkInvariants(c, "Hello world")
        val files = mapOf("a" to a, "b" to b, "c" to c)

        val ab = cli("diff", "a", "b", files = files)
        assertEquals(ab.err, 0, ab.code)
        assertTrue(ab.out, ab.out.contains("text: 11 -> 21 chars"))
        assertTrue(ab.out, ab.out.contains("added (10 chars):"))
        assertTrue(ab.out, ab.out.contains("\"brave new \"") || ab.out.contains("\"brave new\""))
        assertFalse(ab.out, ab.out.contains("tombstoned"))
        assertFalse(ab.out, ab.out.contains("VANISHED"))
        // Our replica registered in b; theirs carried over.
        assertTrue(ab.out, ab.out.contains("+ ${TopotextInvariants.hex(us).take(8)}") ||
            ab.out.contains("~ ${TopotextInvariants.hex(us).take(8)}"))

        val bc = cli("diff", "b", "c", files = files)
        assertTrue(bc.out, bc.out.contains("tombstoned (10 chars):"))
        assertTrue(bc.out, bc.out.contains("text: 21 -> 11 chars"))
        assertFalse(bc.out, bc.out.contains("added"))
    }

    @Test
    fun diffShowsFormattingChanges() {
        val base = Fx.create("Title\nbody", us)
        val a = Fx.withRuns(base, listOf(Fx.run(6, Fx.style(0)), Fx.run(4, Fx.style(3))))
        val b = Fx.withRuns(base, listOf(Fx.run(6, Fx.style(1)), Fx.run(4, Fx.style(3))))
        val r = cli("diff", "a", "b", files = mapOf("a" to a, "b" to b))
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, r.out.contains("~ \"Title\\n\": TITLE -> HEADING"))
        assertTrue(r.out, r.out.contains("text: 10 -> 10 chars (unchanged)"))
        assertFalse(r.out, r.out.contains("added"))
    }

    @Test
    fun diffOfIdenticalInputs() {
        val a = loadFixture("fmt-baseline-1.proto")
        val r = cli("diff", "a", "b", files = mapOf("a" to a, "b" to Gzip.compress(a)))
        assertEquals("no structural differences\n", r.out)
    }

    @Test
    fun treeRunsAndTextOnTheMacFixture() {
        val files = mapOf("f" to loadFixture("fmt-baseline-1.proto"))
        val tree = cli("tree", "f", files = files)
        assertTrue(tree.out, tree.out.contains("=> sentinel at idx"))
        val runs = cli("runs", "f", files = files)
        assertTrue(runs.out, runs.out.contains("AR[0]") && runs.out.contains("decoded ("))
        val text = cli("text", "f", files = files)
        assertEquals(NoteBodyEditor.readVisibleText(loadFixture("fmt-baseline-1.proto")) + "\n", text.out)
    }

    @Test
    fun rawWritesTheDecompressedBytes() {
        val raw = loadFixture("fmt-baseline-1.proto")
        val o = ByteArrayOutputStream()
        val code = NofCli.run(listOf("raw", "f"), PrintStream(o, true), PrintStream(ByteArrayOutputStream())) {
            Base64.encode(Gzip.compress(raw, CompressionFormat.ZLIB)).toByteArray()
        }
        assertEquals(0, code)
        org.junit.Assert.assertArrayEquals(raw, o.toByteArray())
    }

    @Test
    fun tableRefusesANoteBody() {
        val r = cli("table", "f", files = mapOf("f" to Fx.create("x", us)))
        assertEquals(1, r.code)
        assertTrue(r.err, r.err.contains("not a table"))
    }
}
