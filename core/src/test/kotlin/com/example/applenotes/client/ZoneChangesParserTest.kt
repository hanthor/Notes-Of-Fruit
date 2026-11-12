package com.example.applenotes.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #12: parsing `changes/zone` responses.
 *
 * SYNTHETIC fixtures: no real account was available, so these follow the
 * shapes documented in Apple's CloudKit Web Services reference (record
 * dictionaries with recordName / recordType / recordChangeTag / fields,
 * `deleted: true` for deletions, top-level `syncToken` / `moreComing`)
 * and the field encodings this app already sees from records/query.
 */
class ZoneChangesParserTest {
    // "Shopping" / "milk" in base64, as the server sends *Encrypted fields to web sessions.
    private val noteRecord = """
        {
          "recordName": "NOTE-1",
          "recordType": "Note",
          "recordChangeTag": "k1",
          "fields": {
            "TitleEncrypted": {"type": "ENCRYPTED_STRING", "value": "U2hvcHBpbmc="},
            "SnippetEncrypted": {"type": "ENCRYPTED_STRING", "value": "bWlsaw=="},
            "ModificationDate": {"type": "TIMESTAMP", "value": 1700000000000},
            "Folder": {"type": "REFERENCE", "value": {"recordName": "FOLDER-F", "action": "NONE"}}
          }
        }
    """.trimIndent()

    @Test
    fun parsesNotesFoldersAndDeletions() {
        val raw = """
            {
              "zoneID": {"zoneName": "Notes"},
              "records": [
                $noteRecord,
                {"recordName": "FOLDER-E", "recordType": "Folder", "recordChangeTag": "f1",
                 "fields": {"TitleEncrypted": {"type": "ENCRYPTED_STRING", "value": "RW1wdHk="}}},
                {"recordName": "GONE-1", "deleted": true},
                {"recordName": "TRASHED-HARD", "recordType": "Note",
                 "fields": {"Deleted": {"type": "INT64", "value": 1}}},
                {"recordName": "ATT-1", "recordType": "Attachment", "fields": {}},
                {"recordName": "ERR-1", "serverErrorCode": "NOT_FOUND"}
              ],
              "syncToken": "token-2",
              "moreComing": true
            }
        """.trimIndent()
        val c = ZoneChangesParser.parse(raw)
        val n = c.notes.single()
        assertEquals("NOTE-1", n.recordName)
        assertEquals("Shopping", n.title)
        assertEquals("milk", n.snippet)
        assertEquals(1_700_000_000_000L, n.modificationTimestampMs)
        assertEquals("FOLDER-F", n.folderRecordName)
        assertFalse(n.deleted)
        assertEquals(FolderSummary("FOLDER-E", "Empty", isTrash = false, isDefault = false), c.folders.single())
        assertEquals(listOf("GONE-1", "TRASHED-HARD"), c.removedRecordNames)
        assertEquals(2, c.ignored)
        assertEquals("token-2", c.syncToken)
        assertTrue(c.moreComing)
    }

    @Test
    fun noteWithoutFieldsIsTreatedAsRemoved() {
        val c = ZoneChangesParser.parse(
            """{"records":[{"recordName":"N","recordType":"Note"}],"syncToken":"t","moreComing":false}""",
        )
        assertEquals(listOf("N"), c.removedRecordNames)
        assertTrue(c.notes.isEmpty())
        assertFalse(c.moreComing)
    }

    @Test
    fun acceptsTheBatchedZonesShape() {
        val c = ZoneChangesParser.parse(
            """{"zones":[{"zoneID":{"zoneName":"Notes"},"records":[$noteRecord],"syncToken":"t3","moreComing":false}]}""",
        )
        assertEquals("NOTE-1", c.notes.single().recordName)
        assertEquals("t3", c.syncToken)
    }

    @Test
    fun serverErrorsAreTyped() {
        for (code in listOf("ZONE_NOT_FOUND", "BAD_REQUEST", "CHANGE_TOKEN_EXPIRED")) {
            try {
                ZoneChangesParser.parse("""{"serverErrorCode":"$code","reason":"x"}""")
                fail("expected an error for $code")
            } catch (e: NotesError.Server) {
                assertEquals(code, e.code)
            }
        }
        try {
            ZoneChangesParser.parse("""{"zones":[{"serverErrorCode":"ZONE_NOT_FOUND"}]}""")
            fail("expected an error")
        } catch (e: NotesError.Server) {
            assertEquals("ZONE_NOT_FOUND", e.code)
        }
    }

    @Test
    fun unexpectedShapesAreDecodeErrors() {
        for (raw in listOf("not json", "[]", """{"records":[]}""", """{"records":{},"syncToken":"t"}""", """{"zones":[]}""")) {
            try {
                ZoneChangesParser.parse(raw)
                fail("expected Decode for $raw")
            } catch (e: NotesError.Decode) {
                // expected
            }
        }
    }

    @Test
    fun missingRecordsIsAnEmptyPage() {
        val c = ZoneChangesParser.parse("""{"syncToken":"t"}""")
        assertTrue(c.notes.isEmpty() && c.folders.isEmpty() && c.removedRecordNames.isEmpty())
        assertNull(c.notes.firstOrNull())
    }
}
