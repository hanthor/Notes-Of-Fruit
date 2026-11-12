package com.example.applenotes.client

import com.example.applenotes.auth.ICloudSession
import com.example.applenotes.proto.NoteBodyEditor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The create request (recordName, exact text) and how a refused create is reported. */
class CreateNoteTest {
    private val uuid = ByteArray(16) { (it + 1).toByte() }.also {
        it[6] = ((it[6].toInt() and 0x0F) or 0x40).toByte()
        it[8] = ((it[8].toInt() and 0x3F) or 0x80).toByte()
    }
    private val folder = buildJsonObject { put("type", "REFERENCE") }
    private val session = ICloudSession("dsid", "https://example.invalid", "c=1", "client")
    private val sent = mutableListOf<JsonObject>()

    private fun client(answer: (String) -> Pair<Int, String>) = AppleNotesClient(
        HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            sent += body
            val name = body["operations"]!!.jsonArray[0].jsonObject["record"]!!.jsonObject["recordName"]!!.jsonPrimitive.content
            val (status, json) = answer(name)
            respond(json, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        session,
    )

    private fun ok(name: String) = 200 to """{"records":[{"recordName":"$name","recordType":"Note","recordChangeTag":"t1","fields":{}}]}"""

    private fun sentRecord() = sent.last()["operations"]!!.jsonArray[0].jsonObject["record"]!!.jsonObject

    @Test
    fun usesTheGivenRecordNameAndTheExactText() = runTest {
        val rec = client(::ok).createNote("", "foo", folder, uuid, noteText = "\nfoo", recordName = "AAAA-1")
        assertEquals("AAAA-1", rec.recordName)
        assertEquals("AAAA-1", sentRecord()["recordName"]!!.jsonPrimitive.content)
        val b64 = sentRecord()["fields"]!!.jsonObject["TextDataEncrypted"]!!.jsonObject["value"]!!.jsonPrimitive.content
        assertEquals("\nfoo", NoteBodyEditor.readVisibleTextFromBase64(b64))
    }

    @Test
    fun aFreshRecordNameIsAnUppercaseV4Uuid() = runTest {
        client(::ok).createNote("t", "b", folder, uuid)
        val name = sentRecord()["recordName"]!!.jsonPrimitive.content
        assertTrue(name, Regex("[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}").matches(name))
    }

    @Test
    fun anExistingRecordIsReportedAsAlreadyExists() = runTest {
        val answers = listOf(
            200 to """{"records":[{"recordName":"N","serverErrorCode":"EXISTS","reason":"record exists"}]}""",
            200 to """{"records":[{"recordName":"N","serverErrorCode":"RECORD_EXISTS"}]}""",
            200 to """{"records":[{"recordName":"N","serverErrorCode":"CONFLICT","reason":"oplock error"}]}""",
            409 to """{"serverErrorCode":"EXISTS"}""",
            409 to """{"serverErrorCode":"CONFLICT"}""",
        )
        for (a in answers) {
            try {
                client { a }.createNote("t", "b", folder, uuid, recordName = "N")
                fail("expected AlreadyExists for $a")
            } catch (e: NotesError.AlreadyExists) {
                // expected
            }
        }
    }

    @Test
    fun existsMapsEverywhere() {
        assertTrue(CloudKitErrors.fromRecord("EXISTS", null) is NotesError.AlreadyExists)
        assertTrue(CloudKitErrors.fromHttp(409, """{"serverErrorCode":"EXISTS"}""") is NotesError.AlreadyExists)
    }
}
