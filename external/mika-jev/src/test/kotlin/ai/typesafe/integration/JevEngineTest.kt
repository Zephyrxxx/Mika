package ai.typesafe.integration

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class JevEngineTest {
    @Test fun legacyFullEndpointDoesNotAppendSystemoneTwice() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(503))
            val engine = JevEngine(OkHttpClient(),
                JevConfig(baseUrl = s.url("/api/v1/systemone").toString()), "test-key")
            engine.choose("synthetic", mapOf("intent" to ChoiceQuestion("Intent",
                mapOf("read" to "Read", "act" to "Act"))))
            assertEquals("/api/v1/systemone", s.takeRequest().path)
        }
    }

    @Test fun uncertaintyReportsTheFailingRequiredQuestionWithoutCandidateText() = runBlocking {
        MockWebServer().use { s ->
            val qs = mapOf("operation" to ChoiceQuestion("private task", mapOf("scroll" to "private label", "tap" to "tap")),
                "direction" to ChoiceQuestion("direction", mapOf("down" to "down", "up" to "up")))
            val routing = ChoiceRouting("operation", mapOf("scroll" to setOf("direction"), "tap" to emptySet()))
            s.enqueue(MockResponse().setBody("""{"model":"jev-test","answers":{"operation":{"type":"choice","choice":"scroll","probabilities":{"scroll":0.9,"tap":0.1}},"direction":{"type":"choice","choice":"down","probabilities":{"down":0.55,"up":0.45}}},"usage":{"input_tokens":30,"output_tokens":8}}"""))
            val calls = mutableListOf<JevCall>()
            val result = engine(s).choose("private screen", qs, routing = routing, policy = ChoicePolicy(0.65, 0.1), onCall = calls::add) as JevResult.Abstained
            val detail = result.diagnostic!!
            assertEquals("direction", detail.question); assertEquals("low_probability", detail.issue)
            assertEquals(0.55, detail.probability, 0.0001); assertEquals(0.45, detail.runnerUp, 0.0001)
            assertEquals(0.65, detail.minProbability, 0.0001); assertEquals(2, detail.candidates)
            assertEquals(detail, calls.single().diagnostic)
            assertEquals(2, calls.single().questionCount); assertEquals(4, calls.single().totalCandidates)
            assertEquals(s.takeRequest().body.readUtf8().length, calls.single().requestChars)
            assertFalse(calls.toString().contains("private"))
            assertEquals(30, calls.single().inputTokens)
        }
    }

    @Test fun distinguishesCloseAlternativesFromLowProbability() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(reply(probabilities = "\"read\":0.65,\"act\":0.35")))
            val result = engine(s).choose("state", questions, policy = ChoicePolicy(0.6, 0.4)) as JevResult.Abstained
            assertEquals("small_margin", result.diagnostic!!.issue)
        }
    }

    @Test fun speculativeBranchesConsumeOnlyChosenArgumentsAndKeepOneUsageRecord() = runBlocking {
        MockWebServer().use { s ->
            val qs = mapOf("operation" to ChoiceQuestion("Which operation?", mapOf("scroll" to "Scroll", "tap" to "Tap")),
                "direction" to ChoiceQuestion("Which direction?", mapOf("down" to "Down", "up" to "Up")),
                "target" to ChoiceQuestion("Which target?", mapOf("first" to "First", "second" to "Second")))
            val routing = ChoiceRouting("operation", mapOf("scroll" to setOf("direction"), "tap" to setOf("target")))
            val body = """{"model":"jev-test","answers":{"operation":{"type":"choice","choice":"scroll","probabilities":{"scroll":0.8,"tap":0.2}},"direction":{"type":"choice","choice":"down","probabilities":{"down":0.8,"up":0.2}},"target":{"type":"choice","choice":"first","probabilities":{"first":0.5,"second":0.5}}},"usage":{"input_tokens":40,"output_tokens":8}}"""
            val calls = mutableListOf<JevCall>()
            s.enqueue(MockResponse().setBody(body))
            val result = engine(s).choose("state", qs, routing = routing, policy = ChoicePolicy(0.65, 0.1), onCall = calls::add) as JevResult.Accepted
            assertEquals(setOf("operation", "direction"), result.answers.keys)
            assertEquals(1, calls.size); assertEquals(40, calls.single().inputTokens)
            assertEquals(1, s.requestCount)
            s.enqueue(MockResponse().setBody(body.replace("\"down\":0.8,\"up\":0.2", "\"down\":0.5,\"up\":0.5")))
            assertEquals("uncertain", (engine(s).choose("state", qs, routing = routing, policy = ChoicePolicy(0.65, 0.1)) as JevResult.Abstained).reason)
            s.enqueue(MockResponse().setBody(body))
            // The relaxed operation policy must not leak to ordinary auxiliary judgments.
            assertEquals("uncertain", (engine(s).choose("state", qs, routing = routing) as JevResult.Abstained).reason)
        }
    }

    @Test fun invalidBatchRoutingIsRejectedBeforeNetwork() = runBlocking {
        MockWebServer().use { s ->
            val result = engine(s).choose("state", questions, routing = ChoiceRouting("intent", mapOf("read" to setOf("missing"))))
            assertEquals("invalid_input", (result as JevResult.Abstained).reason)
            assertEquals(0, s.requestCount)
        }
    }

    private val questions = mapOf("intent" to ChoiceQuestion("Which?", mapOf("read" to "Read", "act" to "Act")))
    private fun reply(choice: String = "read", probabilities: String = "\"read\":0.98,\"act\":0.02") =
        """{"model":"jev-pinned","answers":{"intent":{"type":"choice","choice":"$choice","probabilities":{$probabilities}}},"usage":{"input_tokens":12,"output_tokens":2}}"""
    private fun engine(s: MockWebServer, budget: Long = 1000) = JevEngine(OkHttpClient(), JevConfig(s.url("/v1/").toString(), budgetMs = budget), "test-key")

    @Test fun nativeProtocolAndResolvedModel() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(reply()))
            val result = engine(s).choose("hello", questions) as JevResult.Accepted
            assertEquals("jev-pinned", result.model)
            assertEquals("read", result.answers.getValue("intent").choice)
            val request = s.takeRequest()
            assertEquals("/v1/systemone", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val q = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("questions").jsonObject.getValue("intent").jsonObject
            assertEquals("choice", q.getValue("type").jsonPrimitive.content)
            assertTrue("criteria" in q)
        }
    }
    @Test fun nativeModelListUsesNames() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"models":[{"name":"jev-latest","description":"general","release_date":"2026-09-15"}]}"""))
            assertEquals(listOf("jev-latest"), engine(s).models())
            assertEquals("/v1/models", s.takeRequest().path)
        }
    }
    @Test fun companionNoulGatesChoiceInSameRequest() = runBlocking {
        MockWebServer().use { s ->
            for (probability in listOf(0.01, 0.5, 0.99)) {
                val body = reply().replace("},\"usage\"", ",\"__applicable\":{\"type\":\"noul\",\"noul\":$probability}},\"usage\"")
                s.enqueue(MockResponse().setBody(body))
                val result = engine(s).choose("{\"goal\":\"read\"}", questions, "Does a destination fit?")
                assertEquals(probability >= 0.9, result is JevResult.Accepted)
                val request = Json.parseToJsonElement(s.takeRequest().body.readUtf8()).jsonObject
                assertTrue(request.getValue("state") is JsonObject)
                assertEquals(2, request.getValue("questions").jsonObject.size)
            }
        }
    }
    @Test fun negativeJudgmentsAreNotServiceFailures() = runBlocking {
        MockWebServer().use { s ->
            val engine = engine(s)
            repeat(4) {
                s.enqueue(MockResponse().setBody(reply().replace("},\"usage\"", ",\"__applicable\":{\"type\":\"noul\",\"noul\":0.01}},\"usage\"")))
                assertEquals("not_applicable", (engine.choose("state", questions, "Fits?") as JevResult.Abstained).reason)
            }
            assertEquals(4, s.requestCount)
        }
    }
    @Test fun invalidAndUncertainResponsesNeverAccepted() = runBlocking {
        MockWebServer().use { s ->
            for (body in listOf(reply("unknown"), reply(probabilities = "\"read\":0.51,\"act\":0.49"),
                reply(probabilities = "\"read\":1.0"), reply(probabilities = "\"read\":1.9,\"act\":-0.9"), "{}", "not-json")) {
                s.enqueue(MockResponse().setBody(body))
                assertTrue(engine(s).choose("state", questions) is JevResult.Abstained)
            }
        }
    }
    @Test fun authAndRateLimitStopFurtherRequests() = runBlocking {
        for (status in listOf(401, 403, 429)) MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(status))
            val engine = engine(s)
            assertTrue(engine.choose("state", questions) is JevResult.Abstained)
            assertTrue(engine.choose("state", questions) is JevResult.Abstained)
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun timeoutAndCancellation() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(reply()).setBodyDelay(700, TimeUnit.MILLISECONDS))
            assertEquals("timeout", (engine(s, 100).choose("state", questions) as JevResult.Abstained).reason)
            s.enqueue(MockResponse().setBody(reply()).setBodyDelay(700, TimeUnit.MILLISECONDS))
            val job = async { engine(s).choose("state", questions) }
            delay(50)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }
    @Test fun circuitBreakerAndNoRedirects() = runBlocking {
        MockWebServer().use { s ->
            val engine = engine(s)
            repeat(3) { s.enqueue(MockResponse().setResponseCode(503)); engine.choose("s", questions) }
            assertEquals("cooldown", (engine.choose("s", questions) as JevResult.Abstained).reason)
            assertEquals(3, s.requestCount)
            s.enqueue(MockResponse().setResponseCode(302).addHeader("Location", s.url("/other")))
            assertEquals("http_302", (engine(s).choose("s", questions) as JevResult.Abstained).reason)
            assertEquals(4, s.requestCount)
        }
    }
    @Test fun batchesIndependentQuestions() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(reply().replace("},\"usage\"", ",\"second\":{\"type\":\"choice\",\"choice\":\"read\",\"probabilities\":{\"read\":1.0,\"act\":0.0}}},\"usage\"")))
            val result = engine(s).choose("state", questions + ("second" to questions.getValue("intent")))
            assertTrue(result is JevResult.Accepted)
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun accountingKeepsUsageForUncertainAndInapplicableResponses() = runBlocking {
        MockWebServer().use { s ->
            val calls = mutableListOf<JevCall>()
            s.enqueue(MockResponse().setBody(reply(probabilities = "\"read\":0.51,\"act\":0.49")))
            engine(s).choose("state", questions, onCall = { calls += it })
            s.enqueue(MockResponse().setBody(reply().replace("},\"usage\"", ",\"__applicable\":{\"type\":\"noul\",\"noul\":0.01}},\"usage\"")))
            engine(s).choose("state", questions, "Fits?", onCall = { calls += it })
            assertEquals(listOf("uncertain", "not_applicable"), calls.map { it.status })
            assertTrue(calls.all { it.model == "jev-pinned" && it.inputTokens == 12 && it.outputTokens == 2 })
        }
    }
    @Test fun accountingCountsSubmittedRequestsButNotLocalSkips() = runBlocking {
        MockWebServer().use { s ->
            val calls = mutableListOf<JevCall>()
            val e = engine(s)
            e.choose("x".repeat(32001), questions, onCall = { calls += it })
            s.enqueue(MockResponse().setResponseCode(401))
            e.choose("state", questions, onCall = { calls += it })
            e.choose("state", questions, onCall = { calls += it })
            assertEquals(1, calls.size)
            assertEquals("authentication", calls.single().status)
            assertNull(calls.single().inputTokens)
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun accountingSurvivesTimeoutCancellationAndObserverFailure() = runBlocking {
        MockWebServer().use { s ->
            val calls = mutableListOf<JevCall>()
            s.enqueue(MockResponse().setBody(reply()).setBodyDelay(700, TimeUnit.MILLISECONDS))
            engine(s, 100).choose("state", questions, onCall = { calls += it })
            s.takeRequest()
            s.enqueue(MockResponse().setBody(reply()).setBodyDelay(700, TimeUnit.MILLISECONDS))
            val job = async { engine(s).choose("state", questions, onCall = { calls += it }) }
            assertNotNull(withContext(Dispatchers.IO) { s.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertEquals(listOf("timeout", "cancelled"), calls.map { it.status })
            assertTrue(calls.all { it.inputTokens == null && it.outputTokens == null })
            s.enqueue(MockResponse().setBody(reply()))
            assertTrue(engine(s).choose("state", questions, onCall = { error("accounting failed") }) is JevResult.Accepted)
        }
    }
}
