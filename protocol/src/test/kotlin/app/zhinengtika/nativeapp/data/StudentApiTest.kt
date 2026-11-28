package app.zhinengtika.nativeapp.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StudentApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun loginUsesOneTimestampAndParsesAccountFields() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"data":{"accessToken":"access-test","token":"token-test","info":{"realName":"Student","school":{"studentId":"student-test","schoolId":"school-test"}}}}""",
            ),
        )
        val api = StudentApi(
            baseUrl = server.url("/").toString(),
            clockMillis = { 1_700_000_000_000L },
            sessionId = { "test-session" },
        )

        val account = api.login("user-test", "pass-test")
        val request = server.takeRequest()
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        assertEquals("/api/v3/server_system/auth/login", request.path)
        assertEquals("1700000000000", request.getHeader("t"))
        assertEquals(
            ProtocolCrypto.requestDigest("1700000000000", "test-session"),
            request.getHeader("encrypt"),
        )
        assertEquals("1700000000000", body.getValue("t").jsonPrimitive.content)
        assertEquals("user-test", ProtocolCrypto.decryptFromBase64(body.getValue("username").jsonPrimitive.content))
        assertEquals("pass-test", ProtocolCrypto.decryptFromBase64(body.getValue("password").jsonPrimitive.content))
        assertEquals("JBY token-test", account.authorization)
        assertEquals("student-test", account.studentId)
        assertEquals("school-test", account.schoolId)
    }

    @Test
    fun submitParsesBusinessCodeInsteadOfComparingWholeResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("{ \"msg\" : \"ok\", \"data\" : null, \"code\" : 200 }"))
        val api = StudentApi(baseUrl = server.url("/").toString())
        val account = StudentAccount("u", "a", "JBY t", "s", "student", "school")

        api.submitAnswers(account, "template-test", listOf(AnswerSubmission("q1", "AC", "")))
        val request = server.takeRequest()
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val answer = body.getValue("answers").let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject

        assertEquals("/api/v3/server_homework/student/homework/answer", request.path)
        assertEquals("template-test", body.getValue("templateCode").jsonPrimitive.content)
        assertEquals("q1", answer.getValue("questionId").jsonPrimitive.content)
        assertEquals("AC", answer.getValue("answer").jsonPrimitive.content)
        assertTrue(request.getHeader("encrypt")!!.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun gzipBusinessResponseIsTransparentlyDecoded() = runBlocking {
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.GZIPOutputStream(output).use { gzip ->
                gzip.write("{\"code\":200,\"data\":null}".toByteArray())
            }
        }.toByteArray()
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(okio.Buffer().write(bytes)))
        val api = StudentApi(baseUrl = server.url("/").toString())
        api.submitAnswers(testAccount(), "template-test", listOf(AnswerSubmission("q1", "B")))
        assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun serviceUnavailableDoesNotReplaySubmission() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("{\"code\":200}"))
        val api = StudentApi(baseUrl = server.url("/").toString())
        try {
            api.submitAnswers(testAccount(), "template-test", listOf(AnswerSubmission("q1", "B")))
            org.junit.Assert.fail("Expected HTTP error")
        } catch (error: HttpStatusException) {
            assertEquals(503, error.statusCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectDoesNotForwardStudentCredentials() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/capture")))
            other.enqueue(MockResponse().setBody("{\"code\":200}"))
            val api = StudentApi(baseUrl = server.url("/").toString())
            try {
                api.submitAnswers(testAccount(), "template-test", listOf(AnswerSubmission("q1", "B")))
                org.junit.Assert.fail("Expected redirect error")
            } catch (error: HttpStatusException) {
                assertEquals(307, error.statusCode)
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun uploadReplacesLastPathSegmentAndEncodesFilename() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"accessUrl":"https://cdn.test/files/old.jpg","filename":"新 图片.jpg"}}"""))
        val api = StudentApi(baseUrl = server.url("/").toString())
        val url = api.uploadImage(byteArrayOf(1, 2, 3), "answer.jpg", testAccount())
        assertEquals("https://cdn.test/files/%E6%96%B0%20%E5%9B%BE%E7%89%87.jpg", url)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"answer.jpg\""))
        assertTrue(body.contains("Content-Type: multipart/form-data"))
    }

    @Test
    fun rejectsNullStudentIdentityInsteadOfCreatingCollidingAccount() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"accessToken":"a","token":"t","info":{"school":{"studentId":null,"schoolId":"school"}}}}"""))
        val api = StudentApi(baseUrl = server.url("/").toString())
        try {
            api.login("synthetic-user", "synthetic-password")
            org.junit.Assert.fail("Expected incomplete identity error")
        } catch (error: ApiException) {
            assertTrue(error.message!!.contains("studentId"))
        }
    }

    private fun testAccount() = StudentAccount("synthetic-user", "access-test", "JBY token-test", "session-test", "student-test", "school-test")
}
