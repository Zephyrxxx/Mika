package com.wangzan101.mika.calls

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Synthetic records using the exact 1.0.624 wire schema, never real call data. */
class CallHistory624CompatibilityTest {
    private val legacy = """[{"id":"legacy","number":null,"direction":null,"startedAtMs":100,"endedAtMs":900,"result":"ended","transcript":[{"role":"caller","text":"synthetic"}],"handled":false,"intervals":[{"ai":false,"startMs":100,"endMs":250},{"ai":true,"startMs":250,"endMs":900}]}]"""

    private class JsonStorage(var raw: String) : CallHistory.Storage {
        override fun read(): List<CallRecord> = Json.decodeFromString(raw)
        override fun write(records: List<CallRecord>): Boolean {
            raw = Json.encodeToString(records)
            return true
        }
    }

    @Test fun legacyUnknownDirectionAndIntervalsSurviveRealHistoryEdits() = runBlocking {
        // Requires the pure vivo fix's nullable direction, kept separately from packaging.
        val storage = JsonStorage(legacy)
        val history = CallHistory(storage)
        history.load()
        assertFalse(history.writeFailed.value)
        val old = history.state.value.single()
        assertNull(old.direction)
        assertEquals(listOf(CallInterval(false, 100, 250), CallInterval(true, 250, 900)), old.intervals)
        assertEquals(CallHistory.Result.SAVED, history.setHandled(old.id, true))
        assertEquals(CallHistory.Result.SAVED, history.setSummary(old.id, CallSummary(conclusion = "synthetic summary"), SummaryStatus.READY))
        val recent = old.copy(id = "new", intervals = emptyList(), transcript = emptyList())
        assertEquals(CallHistory.Result.SAVED, history.append(recent))
        val reopened = CallHistory(JsonStorage(storage.raw))
        reopened.load()
        val restored = reopened.state.value.single { it.id == old.id }
        assertEquals(old.intervals, restored.intervals)
        assertEquals(old.transcript, restored.transcript)
        assertNull(restored.direction)
        assertTrue(restored.handled)
        assertEquals(SummaryStatus.READY, restored.summaryStatus)
    }

    @Test fun recordsBeforeIntervalsStillDecodeWithoutInventedHistory() {
        val before = """[{"id":"old","number":null,"direction":"INCOMING","startedAtMs":1,"endedAtMs":2,"result":"ended","transcript":[]}]"""
        assertTrue(Json.decodeFromString<List<CallRecord>>(before).single().intervals.isEmpty())
    }

    @Test fun unknownOrMalformedFieldsDoNotGetSilentlyDiscardedByRewrite() = runBlocking {
        for (raw in listOf(legacy.replace("\"intervals\":", "\"unrecognizedIntervals\":"), legacy.replace("\"ai\":false", "\"ai\":\"not-a-boolean\""))) {
            val storage = JsonStorage(raw)
            val history = CallHistory(storage)
            history.load()
            assertTrue(history.writeFailed.value)
            assertEquals(CallHistory.Result.WRITE_FAILED, history.setHandled("legacy", true))
            assertEquals(raw, storage.raw)
        }
    }
}
