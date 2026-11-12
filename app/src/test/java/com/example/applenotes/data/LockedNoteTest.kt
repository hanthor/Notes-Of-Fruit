package com.example.applenotes.data

import androidx.lifecycle.SavedStateHandle
import com.example.applenotes.client.NoteRecord
import com.example.applenotes.testing.FakeAuthGateway
import com.example.applenotes.testing.FakeNotesApi
import com.example.applenotes.testing.MainDispatcherRule
import com.example.applenotes.testing.REPLICA_UUID
import com.example.applenotes.testing.noteRecord
import com.example.applenotes.ui.detail.NoteDetailViewModel
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Locked (password-protected) notes are detected and never decoded (issue #16). */
class LockedNoteTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun bytesField(b64: String): JsonElement = buildJsonObject {
        put("type", "ENCRYPTED_BYTES")
        put("value", b64)
    }

    private fun locked(base: NoteRecord, vararg extra: Pair<String, JsonElement>) =
        base.copy(rawFields = base.rawFields + extra)

    private val cryptoFields = arrayOf(
        "CryptoInitializationVector" to bytesField("AAECAwQFBgcICQoLDA0ODw=="),
        "CryptoTag" to bytesField("EBESExQVFhcYGRobHB0eHw=="),
        "CryptoSalt" to bytesField("ICEiIyQlJicoKSorLC0uLw=="),
        "CryptoIterationCount" to buildJsonObject { put("type", "INT64"); put("value", 20000) },
        "CryptoWrappedKey" to bytesField("MDEyMzQ1Njc4OTo7PD0+Pw=="),
    )

    @Test
    fun noteWithCryptoFieldsIsLockedReadOnlyWithoutText() {
        // Even with a perfectly decodable body, a locked note is never decoded.
        val rec = locked(noteRecord("L", "Secret\nstuff"), *cryptoFields)
        assertTrue(isLockedNote(rec))
        assertEquals(NoteBody.ReadOnly(ReadOnlyReason.LOCKED, text = null), decodeNoteBody(rec))
    }

    @Test
    fun anySingleCryptoFieldMarksTheNoteLocked() {
        for (field in cryptoFields) {
            val rec = locked(noteRecord("L", null), field)
            assertTrue(field.first, isLockedNote(rec))
            assertEquals(ReadOnlyReason.LOCKED, (decodeNoteBody(rec) as NoteBody.ReadOnly).reason)
        }
    }

    @Test
    fun emptyOrNullCryptoFieldsDontCount() {
        val rec = locked(
            noteRecord("N", "Plain\nnote"),
            "CryptoTag" to bytesField(""),
            "CryptoSalt" to buildJsonObject { put("type", "BYTES"); put("value", JsonNull) },
        )
        assertFalse(isLockedNote(rec))
        assertTrue(decodeNoteBody(rec) is NoteBody.Editable)
    }

    @Test
    fun ordinaryNotesAreNotLocked() {
        assertFalse(isLockedNote(noteRecord("N", "hello")))
        assertNull(lockedNoteHint(noteRecord("N", "hello")))
    }

    @Test
    fun passwordHintDecodesEncryptedAndPlainFields() {
        val enc = locked(noteRecord("L", null), *cryptoFields, "PasswordHintEncrypted" to buildJsonObject {
            put("type", "ENCRYPTED_STRING")
            put("value", Base64.encode("first pet".encodeToByteArray()))
        })
        assertEquals("first pet", lockedNoteHint(enc))
        val plain = locked(noteRecord("L", null), *cryptoFields, "PasswordHint" to buildJsonObject {
            put("type", "STRING")
            put("value", "the usual")
        })
        assertEquals("the usual", lockedNoteHint(plain))
        val blank = locked(noteRecord("L", null), "PasswordHint" to buildJsonObject {
            put("type", "STRING")
            put("value", "  ")
        })
        assertNull(lockedNoteHint(blank))
    }

    @Test
    fun lockedNoteOpensReadOnlyAndNeverSaves() = runTest(main.dispatcher) {
        val api = FakeNotesApi()
        api.put(locked(noteRecord("L", "Secret\nstuff"), *cryptoFields))
        val repo = NotesRepository(
            auth = FakeAuthGateway(api),
            replicaUuidLoader = { REPLICA_UUID },
            externalScope = CoroutineScope(SupervisorJob() + main.dispatcher),
            clock = { 1L },
        )
        repo.signIn()
        val vm = NoteDetailViewModel(repo, SavedStateHandle(), clock = { 2L })
        vm.open("open-1", api.records.getValue("L"), pendingFolderRef = null)
        assertEquals(ReadOnlyReason.LOCKED, vm.readOnlyReason)
        assertEquals("", vm.fullBodyForSave)
        vm.onTitleChange("typed")
        vm.save(alsoExit = false)
        vm.autoSave()
        assertTrue(api.modifies.isEmpty())
    }
}
