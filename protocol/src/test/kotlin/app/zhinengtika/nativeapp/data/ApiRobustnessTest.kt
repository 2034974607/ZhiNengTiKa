package app.zhinengtika.nativeapp.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiRobustnessTest {
    private lateinit var server: MockWebServer
    private val account = StudentAccount("synthetic", "a", "JBY t", "s", "student", "school")

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    @Test fun terminalCallbackRunsOnceOnSuccessAndHttpFailure() = runBlocking {
        val count = AtomicInteger()
        val api = StudentApi(baseUrl = server.url("/").toString())
        server.enqueue(MockResponse().setBody("{\"code\":200}"))
        api.submitAnswers(account, "test", emptyList(), onCallFinished = { count.incrementAndGet() })
        assertEquals(1, count.get())
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        try {
            api.submitAnswers(account, "test", emptyList(), onCallFinished = { count.incrementAndGet() })
            fail("Expected HTTP failure")
        } catch (error: HttpStatusException) { assertEquals(503, error.statusCode) }
        assertEquals(2, count.get())
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationFinishesRealCallAndNeverReplaysSubmission() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val completed = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val api = StudentApi(baseUrl = server.url("/").toString())
        val work = launch {
            api.submitAnswers(account, "test", emptyList(), onCallFinished = {
                count.incrementAndGet()
                completed.complete(Unit)
            })
        }
        val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertNotNull("Submission must have reached mock server before cancellation", request)
        work.cancel()
        work.join()
        withTimeout(5_000) { completed.await() }
        assertEquals(1, count.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun localRequestConstructionFailureReleasesGuardWithoutSending() = runBlocking {
        val count = AtomicInteger()
        val api = StudentApi(baseUrl = server.url("/").toString())
        try {
            api.submitAnswers(account.copy(accessToken = "synthetic\ninvalid"), "test", emptyList()) { count.incrementAndGet() }
            fail("Expected invalid header failure")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, count.get())
        assertEquals(0, server.requestCount)
    }

    @Test fun synchronousCallCreationFailureCompletesExactlyOnce() = runBlocking {
        val count = AtomicInteger()
        val client = okhttp3.OkHttpClient.Builder().eventListenerFactory {
            throw IllegalStateException("Synthetic call setup failure")
        }.build()
        val api = StudentApi(baseUrl = server.url("/").toString(), client = client)
        try {
            api.submitAnswers(account, "test", emptyList()) { count.incrementAndGet() }
            fail("Expected call setup failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, count.get())
        assertEquals(0, server.requestCount)
    }

    @Test fun submissionRejectsDeepIgnoredMetadataBeforeJsonTreeAllocation() = runBlocking {
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        server.enqueue(MockResponse().setBody("{\"code\":200,\"ignored\":$deep}"))
        val count = AtomicInteger()
        try {
            StudentApi(baseUrl = server.url("/").toString()).submitAnswers(account, "test", emptyList()) { count.incrementAndGet() }
            fail("Expected response nesting limit")
        } catch (_: ApiException) { }
        assertEquals(1, count.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun gzipDecodedResponseIsBoundedBeforeJsonParsing() = runBlocking {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            val block = ByteArray(1024) { 32 }
            repeat(8193) { gzip.write(block) }
        }
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(output.toByteArray())))
        val api = StudentApi(baseUrl = server.url("/").toString())
        try {
            api.submitAnswers(account, "test", emptyList())
            fail("Expected response size limit")
        } catch (error: ApiException) {
            assertTrue(error.message!!.contains("8 MiB"))
        }
        assertEquals(1, server.requestCount)
    }
}
