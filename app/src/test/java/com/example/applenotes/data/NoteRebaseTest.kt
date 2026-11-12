package com.example.applenotes.data

import com.example.applenotes.proto.Gzip
import com.example.applenotes.proto.NoteAppender
import com.example.applenotes.proto.NoteBodyEditor
import com.example.applenotes.proto.NoteBodyEditor.AttrSpan
import com.example.applenotes.proto.NoteBodyEditor.ParagraphStyle
import com.example.applenotes.proto.NoteCreator
import com.example.applenotes.proto.TopotextInvariants
import com.example.applenotes.proto.testUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Proto-level checks of the conflict rebase (issue #4): the merged body is
 * spliced onto the FRESH record so the other device's char IDs survive,
 * and every result passes the topotext invariant checker.
 */
@OptIn(ExperimentalEncodingApi::class)
class NoteRebaseTest {
    private val mac = testUuid(1) // created the note
    private val iphone = testUuid(2) // the concurrent remote editor
    private val us = testUuid(3) // this device

    private fun bytes(b64: String) = Gzip.decompress(Base64.decode(b64))
    private fun visible(b64: String) = NoteBodyEditor.readVisibleTextFromBase64(b64)!!

    private class Scenario(val baseB64: String, val remoteB64: String, val local: String)

    private fun scenario(base: String, remote: String, local: String): Scenario {
        val baseB64 = NoteCreator.buildEmptyNoteB64(mac, base)
        val remoteB64 = NoteAppender.setBodyBase64(baseB64, iphone, remote, 1_000)
        assertEquals(remote, visible(remoteB64))
        return Scenario(baseB64, remoteB64, local)
    }

    /** Merge + rebase, then run every check we have on the result. */
    private fun rebase(s: Scenario, localSpans: List<AttrSpan>? = null): Pair<String, TextMerge.Clean> {
        val base = visible(s.baseB64)
        val remote = visible(s.remoteB64)
        val m = TextMerge.merge(base, s.local, remote) as TextMerge.Clean
        val spans = localSpans?.let {
            NoteRebase.mergeSpans(
                m, base, NoteBodyEditor.parseAttributeRunsFromBase64(s.baseB64),
                s.local, it, remote, NoteBodyEditor.parseAttributeRunsFromBase64(s.remoteB64),
            )
        }
        val out = NoteRebase.rebaseBody(s.remoteB64, us, m, spans, 2_000)
        val outBytes = bytes(out)
        TopotextInvariants.checkInvariants(outBytes, m.text, us)
        TopotextInvariants.checkMonotonic(bytes(s.remoteB64), outBytes, us)
        assertEquals(m.text, visible(out))

        // The point of splicing onto the fresh proto: every char that came
        // from the remote version keeps the remote's char ID.
        val remoteIds = TopotextInvariants.parse(bytes(s.remoteB64)).visibleCharIds()
        val outIds = TopotextInvariants.parse(outBytes).visibleCharIds()
        assertEquals(m.text.length, outIds.size)
        val usHex = TopotextInvariants.hex(us)
        for (k in m.text.indices) {
            val r = m.remoteIndex[k]
            if (r >= 0) {
                assertEquals("char $k ('${m.text[k]}') lost its remote char id", remoteIds[r].second, outIds[k].second)
            }
            // Chars of a local (line-level) hunk may still keep an existing
            // id where the char-level splice found them unchanged; anything
            // genuinely new must be ours (checkMonotonic above).
        }
        for ((k, id) in outIds.withIndex()) {
            if (id.second !in remoteIds.map { it.second }.toSet()) {
                assertEquals("char $k is new, so ours", usHex, id.second.first)
            }
        }
        return out to m
    }

    @Test
    fun separateEditsKeepRemoteCharIds() {
        rebase(
            scenario(
                base = "Groceries\nmilk\neggs\nbread",
                remote = "Groceries\nmilk\n6 eggs\nbread",
                local = "Groceries\noat milk\neggs\nbread\njam",
            ),
        )
    }

    @Test
    fun remoteTextBetweenTwoLocalEditsIsNotReinserted() {
        // A single prefix/suffix splice would tombstone "b..d" and re-add it
        // as ours; the step-by-step rebase must not.
        val (_, m) = rebase(
            scenario(
                base = "t\na\nb\nc\nd\ne",
                remote = "t\na\nb\nC from iPhone\nd\ne",
                local = "t\nA\nb\nc\nd\nE",
            ),
        )
        assertEquals("t\nA\nb\nC from iPhone\nd\nE", m.text)
        assertEquals(2, m.steps.size)
    }

    @Test
    fun postCreateTagBumpRebasesOntoTheSameText() {
        // Remote == base (only the tag moved): the merge is the local edit.
        val baseB64 = NoteCreator.buildEmptyNoteB64(mac, "New note\nfirst line")
        val s = Scenario(baseB64, baseB64, "New note\nfirst line\nsecond line")
        val (out, _) = rebase(s)
        assertEquals("New note\nfirst line\nsecond line", visible(out))
    }

    @Test
    fun emojiCjkAndAttachmentNeighbours() {
        rebase(
            scenario(
                base = "旅行\n😀 写真\n￼\nメモ",
                remote = "旅行\n😀 写真\n￼\nメモ 🏯",
                local = "旅行 2025\n😀 写真\n￼\nメモ",
            ),
        )
    }

    @Test
    fun formattingFromBothSidesSurvives() {
        // Local bolds "milk" (no text change there) and adds a line; remote
        // edits another line. Both formatting and text land.
        val s = scenario(
            base = "Groceries\nmilk\neggs",
            remote = "Groceries\nmilk\n6 eggs",
            local = "Groceries\njam\nmilk\neggs",
        )
        val localSpans = listOf(
            AttrSpan(length = "Groceries\n".length, style = ParagraphStyle.TITLE),
            AttrSpan(length = "jam\n".length, style = ParagraphStyle.BODY),
            AttrSpan(length = 4, style = ParagraphStyle.BODY, bold = true),
            AttrSpan(length = "\neggs".length, style = ParagraphStyle.BODY),
        )
        val (out, m) = rebase(s, localSpans)
        val spans = NoteBodyEditor.expandSpansToChars(m.text, NoteBodyEditor.parseAttributeRunsFromBase64(out))
        val milk = m.text.indexOf("milk")
        assertTrue("user's bold kept", (milk until milk + 4).all { spans[it].bold })
        assertFalse("remote's line not bolded", spans[m.text.indexOf("6 eggs")].bold)
        assertEquals(ParagraphStyle.TITLE, spans[0].style)
    }

    @Test
    fun keepBothAppendsAMarkedCopyAndNeverDuplicatesAttachments() {
        val remote = "Trip\nphoto\n￼\nfrom iPhone"
        val local = "Trip\nphoto\n￼\nfrom Android"
        val remoteSpans = listOf(
            AttrSpan(length = 5, style = ParagraphStyle.TITLE),
            AttrSpan(length = 6, style = ParagraphStyle.BODY),
            AttrSpan(length = 1, style = ParagraphStyle.BODY, isAttachment = true, attachmentRecordName = "ATT-1"),
            AttrSpan(length = "\nfrom iPhone".length, style = ParagraphStyle.BODY),
        )
        val localSpans = remoteSpans.dropLast(1) + AttrSpan(length = "\nfrom Android".length, style = ParagraphStyle.BODY)
        val (text, spans) = NoteRebase.keepBoth(remote, remoteSpans, local, localSpans)
        assertEquals(
            "$remote\n\n${NoteRebase.KEEP_BOTH_SEPARATOR}\nTrip\nphoto\n${NoteRebase.ATTACHMENT_PLACEHOLDER}\nfrom Android",
            text,
        )
        assertEquals(1, text.count { it == '￼' })
        assertEquals(text.length, spans.sumOf { it.length })
        val perChar = NoteBodyEditor.expandSpansToChars(text, spans)
        assertEquals(1, perChar.count { it.isAttachment })
        assertEquals("only the original title line is a title", 5, perChar.count { it.style == ParagraphStyle.TITLE })

        // And it splices cleanly onto the remote proto as a plain append.
        val remoteB64 = NoteCreator.buildEmptyNoteB64(mac, "Trip\nphoto\nfrom iPhone")
        val remoteText = visible(remoteB64)
        val (t2, s2) = NoteRebase.keepBoth(remoteText, emptyList(), "Trip\nphoto\nmine", emptyList())
        val out = NoteAppender.setBodyBase64(remoteB64, us, t2, 2_000, s2)
        TopotextInvariants.checkInvariants(bytes(out), t2, us)
        TopotextInvariants.checkMonotonic(bytes(remoteB64), bytes(out), us)
    }

    @Test
    fun keepBothOntoAnEmptyRemoteIsJustTheCopy() {
        val (text, spans) = NoteRebase.keepBoth("", emptyList(), "mine", emptyList())
        assertEquals("mine", text)
        assertEquals(4, spans.sumOf { it.length })
    }
}
