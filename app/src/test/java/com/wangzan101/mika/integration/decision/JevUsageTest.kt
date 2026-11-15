package com.wangzan101.mika.integration.decision

import ai.typesafe.integration.*
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.usage.*
import com.wangzan101.mika.record.RunStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class JevUsageTest {
    @Test fun uncertaintyDetailsReachBothThePlannerAndUsageExport() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("diagnostic-usage.json"), now = { at })
            val store = RunStore(tmp.root.resolve("diagnostic-runs"), { null }, 30, { at }, { it })
            val handle = store.open("private task")
            val port = JevDecisionPort(engine(server), ledger, now = { at })
            server.enqueue(MockResponse().setBody("""{"model":"jev-test","answers":{"target":{"type":"choice","choice":"a","probabilities":{"a":0.55,"b":0.45}}},"usage":{"input_tokens":40,"output_tokens":9}}"""))
            withContext(JevUsageContext({ handle.event(it) }, FastMode.Enabled)) {
                val input = DecisionBatchInput("operation", "private screen", mapOf("target" to DecisionQuestion("select private target", mapOf("a" to "private first", "b" to "private second"))),
                    "target", mapOf("a" to emptySet(), "b" to emptySet()), "operations-v2", stage = "parameters")
                val result = port.chooseBatch(input) as DecisionBatchAttempt.Abstain
                assertEquals("target", result.detail!!.question)
                assertEquals(0.55, result.detail!!.probability, 0.0001)
            }
            handle.close()
            val exported = store.usageLog(handle.runId)
            val row = rows(exported).single { it["type"] == JsonPrimitive("decision_usage") }
            assertEquals("parameters", row.getValue("stage").jsonPrimitive.content)
            assertEquals("target", row.getValue("failedQuestion").jsonPrimitive.content)
            assertEquals("low_probability", row.getValue("failureKind").jsonPrimitive.content)
            assertEquals(0.65, row.getValue("requiredProbability").jsonPrimitive.double, 0.0001)
            assertEquals(2, row.getValue("failedCandidateCount").jsonPrimitive.int)
            assertEquals(server.takeRequest().body.readUtf8().length, row.getValue("requestChars").jsonPrimitive.int)
            assertEquals(40L, ledger.snapshot().rows.single().counts.input)
            assertFalse(exported.contains("private"))
        }
    }

    @Test fun complexOperationMetersBothStagesOnceWithoutInventingExtraCalls() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("staged-usage.json"), now = { at })
            val store = RunStore(tmp.root.resolve("staged-runs"), { null }, 30, { at }, { it })
            val handle = store.open("private goal"); val tally = RunUsageTally()
            val port = JevDecisionPort(engine(server), ledger, now = { at })
            val raw = """{"objective":"Scroll down to continue","doneWhen":"The end of the list is visible","surface":"phone"}"""
            val offered = setOf("phone_scroll", "phone_tap")
            val screen = buildJsonObject {
                put("ok", true); put("surface", "phone"); put("stateToken", "local-only"); put("snapshotId", "s1")
                putJsonArray("elements") { repeat(120) { n -> add(buildJsonObject {
                    put("ref", "@e$n"); put("name", "Entry $n"); put("role", "button"); put("clickable", true)
                    putJsonObject("center") { put("x", 500); put("y", n * 8) }
                }) } }
            }
            val table = OperationCoordinator.questions(OperationPlanning.parse(raw, offered), screen, offered, emptyMap())
            fun response(question: String, pick: String) = buildJsonObject {
                put("model", "jev-staged")
                putJsonObject("answers") { putJsonObject(question) {
                    put("type", "choice"); put("choice", pick)
                    val choices = table.questions.getValue(question).choices
                    put("probabilities", JsonObject(choices.mapValues { JsonPrimitive(if (it.key == pick) 0.99 else 0.01 / (choices.size - 1)) }))
                } }
                putJsonObject("usage") { put("input_tokens", 40); put("output_tokens", 9) }
            }.toString()
            server.enqueue(MockResponse().setBody(response("operation", "scroll")))
            server.enqueue(MockResponse().setBody(response("direction", "down")))
            val actor = OperationCoordinator(port, FastMode.Enabled)
            actor.install(raw, offered, "task")
            withContext(tally + JevUsageContext({ handle.event(it) }, FastMode.Enabled)) {
                assertEquals("phone_scroll", actor.propose("task", screen, offered, emptyMap(), 1).proposal!!.call.name)
            }
            handle.close()
            assertEquals(2, server.requestCount); assertEquals(2, tally.event(at).calls)
            assertEquals(2L, ledger.snapshot().rows.single().counts.calls)
            assertEquals(80L, ledger.snapshot().rows.single().counts.input)
            val records = rows(store.usageLog(handle.runId)).filter { it["type"] == JsonPrimitive("decision_usage") }
            assertEquals(listOf("route", "parameters"), records.map { it.getValue("stage").jsonPrimitive.content })
            assertEquals(18, records.sumOf { it.getValue("outputTokens").jsonPrimitive.int })
        }
    }

    @Test fun batchedOperationsCountAsOneRequestInLedgerTallyAndExport() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("batch-usage.json"), now = { at })
            val store = RunStore(tmp.root.resolve("batch-runs"), { null }, 30, { at }, { it })
            val handle = store.open("private goal")
            val tally = RunUsageTally()
            val port = JevDecisionPort(engine(server), ledger, now = { at })
            server.enqueue(MockResponse().setBody("""{"model":"jev-batch","answers":{"operation":{"type":"choice","choice":"scroll","probabilities":{"scroll":0.8,"tap":0.2}},"direction":{"type":"choice","choice":"down","probabilities":{"down":0.8,"up":0.2}},"target":{"type":"choice","choice":"a","probabilities":{"a":0.5,"b":0.5}}},"usage":{"input_tokens":50,"output_tokens":9}}"""))
            val input = DecisionBatchInput("operation", "private observation", mapOf(
                "operation" to DecisionQuestion("operation?", mapOf("scroll" to "scroll", "tap" to "tap")),
                "direction" to DecisionQuestion("direction?", mapOf("down" to "down", "up" to "up")),
                "target" to DecisionQuestion("target?", mapOf("a" to "a", "b" to "b"))),
                "operation", mapOf("scroll" to setOf("direction"), "tap" to setOf("target")), "operations-v1")
            withContext(tally + JevUsageContext({ handle.event(it) }, FastMode.Enabled)) {
                val result = port.chooseBatch(input) as DecisionBatchAttempt.Pick
                assertEquals(setOf("operation", "direction"), result.values.keys)
            }
            handle.close()
            assertEquals(1, server.requestCount)
            assertEquals(1L, ledger.snapshot().rows.single().counts.calls)
            assertEquals(50L, ledger.snapshot().rows.single().counts.input)
            assertEquals(1, tally.event(at).calls)
            val exported = store.usageLog(handle.runId)
            val call = rows(exported).single { it["type"] == JsonPrimitive("decision_usage") }
            assertEquals("operation", call.getValue("purpose").jsonPrimitive.content)
            assertEquals("enabled", call.getValue("mode").jsonPrimitive.content)
            assertEquals(9, call.getValue("outputTokens").jsonPrimitive.int)
            assertFalse(exported.contains("private"))
        }
    }
    @get:Rule val tmp = TemporaryFolder()
    private val at = 1_800_000_000_000L
    private fun input(purpose: String = "intent", version: String = "test-v1") = ChoiceInput(purpose,
        "private task text", "Which?", mapOf("read" to "Read", "act" to "Act"), version)
    private fun reply(purpose: String = "intent", uncertain: Boolean = false, usage: Boolean = true) =
        """{"model":"jev-resolved","answers":{"$purpose":{"type":"choice","choice":"read","probabilities":{"read":${if (uncertain) "0.51" else "0.98"},"act":${if (uncertain) "0.49" else "0.02"}}}}${if (usage) ",\"usage\":{\"input_tokens\":12,\"output_tokens\":2}" else ""}}"""
    private fun engine(s: MockWebServer) = JevEngine(OkHttpClient(),
        JevConfig(s.url("/v1/").toString(), budgetMs = 1000), "private-api-key")
    private fun rows(log: String) = log.lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()

    @Test fun ledgerAndTaskExportIncludeIntentNavigationAndPostRunOutcome() = runBlocking {
        for (mode in listOf(FastMode.Enabled, FastMode.Shadow)) MockWebServer().use { server ->
            val dir = tmp.newFolder(mode.name)
            val ledgerFile = dir.resolve("usage.json")
            val ledger = UsageLedger(ledgerFile, now = { at }, flushDelayMs = 60_000)
            val store = RunStore(dir, { null }, 30, { at }, { it })
            val handle = store.open("private task text")
            handle.event(RunEvent.TaskStarted(at, "private task text", "main"))
            val tally = RunUsageTally()
            val port = JevDecisionPort(engine(server), ledger, now = { at })
            withContext(tally + JevUsageContext({ handle.event(it) }, mode)) {
                server.enqueue(MockResponse().setBody(reply()))
                assertTrue(port.choose(input()) is ChoiceAttempt.Pick)
                server.enqueue(MockResponse().setBody(reply("next_action", uncertain = mode == FastMode.Shadow)))
                val navigation = port.choose(input("next_action"))
                assertEquals(mode == FastMode.Enabled, navigation is ChoiceAttempt.Pick)
            }
            handle.event(tally.event(at))
            handle.event(RunEvent.TaskEnded(at, "succeeded", "done"))
            handle.close()
            // Outcome is judged after the run writer closes, and must still be exportable.
            server.enqueue(MockResponse().setBody(reply("outcome")))
            withContext(JevUsageContext({ store.append(handle.runId, it) })) { port.choose(input("outcome")) }
            ledger.flush()
            val report = UsageQuery.report(UsageLedger(ledgerFile, now = { at }).snapshot(), UsageRange.Last24Hours,
                Instant.ofEpochMilli(at), ZoneOffset.UTC, JevDecisionPort.providerNames)
            assertEquals(3L, report.totals.calls)
            assertEquals(36L, report.totals.input)
            assertEquals(6L, report.totals.output)
            assertEquals("jev-resolved", report.providers.single().models.single().model)
            assertFalse(report.providers.single().deleted)
            assertEquals(2, tally.event(at).calls)
            assertEquals(24L, tally.event(at).inputTokens)
            val exported = store.usageLog(handle.runId)
            val events = rows(exported)
            val calls = events.filter { it["type"] == JsonPrimitive("decision_usage") }
            assertEquals(listOf("intent", "next_action", "outcome"), calls.map { it.getValue("purpose").jsonPrimitive.content })
            assertEquals(mode.name.lowercase(), calls[1].getValue("mode").jsonPrimitive.content)
            assertEquals(if (mode == FastMode.Shadow) "uncertain" else "completed", calls[1].getValue("status").jsonPrimitive.content)
            assertEquals("outcome", events.last().getValue("purpose").jsonPrimitive.content)
            assertEquals(36, calls.sumOf { it.getValue("inputTokens").jsonPrimitive.int })
            assertTrue(calls.all { it["cachedTokens"] == JsonNull })
            assertFalse(exported.contains("private"))
        }
    }

    @Test fun connectionProbeAndUnknownUsageAreCountedButModelListingIsNot() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("probe.json"), now = { at })
            val engine = engine(server)
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"jev-latest"}]}"""))
            engine.models()
            assertTrue(ledger.snapshot().rows.isEmpty())
            server.enqueue(MockResponse().setBody(reply("connection_test", usage = false)))
            JevDecisionPort(engine, ledger).choose(input("connection_test"))
            val counts = ledger.snapshot().rows.single().counts
            assertEquals(1L, counts.calls)
            assertEquals(1L, counts.callsWithoutUsage)
            assertEquals(0L, counts.input)
        }
    }

    @Test fun authenticationCooldownDoesNotInventMoreNetworkCalls() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("auth.json"), now = { at })
            val events = mutableListOf<RunEvent.State>()
            val port = JevDecisionPort(engine(server), ledger)
            server.enqueue(MockResponse().setResponseCode(401))
            withContext(JevUsageContext({ events += it })) { repeat(2) { port.choose(input()) } }
            assertEquals(1L, ledger.snapshot().rows.single().counts.calls)
            assertEquals(1, events.size)
            assertEquals(JsonNull, Json.parseToJsonElement(events.single().detail).jsonObject["inputTokens"])
        }
    }

    @Test fun cancellationStillRecordsOneUnknownUsageRequest() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("cancel.json"), now = { at })
            val events = mutableListOf<RunEvent.State>()
            server.enqueue(MockResponse().setBody(reply()).setBodyDelay(700, TimeUnit.MILLISECONDS))
            val port = JevDecisionPort(engine(server), ledger)
            val task = async(JevUsageContext({ events += it })) { port.choose(input()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            task.cancelAndJoin()
            assertEquals(1L, ledger.snapshot().rows.single().counts.callsWithoutUsage)
            assertEquals("cancelled", Json.parseToJsonElement(events.single().detail).jsonObject.getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun sharedEngineKeepsConcurrentRunRecordsSeparate() = runBlocking {
        MockWebServer().use { server ->
            val ledger = UsageLedger(tmp.root.resolve("concurrent.json"), now = { at })
            val port = JevDecisionPort(engine(server), ledger)
            val events = List(2) { mutableListOf<RunEvent.State>() }
            repeat(2) { server.enqueue(MockResponse().setBody(reply())) }
            (0..1).map { index -> async(JevUsageContext({ events[index] += it })) {
                port.choose(input(version = "run-$index"))
            } }.awaitAll()
            for (index in 0..1) assertEquals("run-$index",
                Json.parseToJsonElement(events[index].single().detail).jsonObject.getValue("contextVersion").jsonPrimitive.content)
            assertEquals(2L, ledger.snapshot().rows.single().counts.calls)
        }
    }
}
