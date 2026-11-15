package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.action.Origin
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FastStepTest {
    private fun screen(extra: Pair<String, JsonElement>? = null): JsonObject = buildJsonObject {
        put("ok", true); put("currentApp", "com.tencent.mm"); put("snapshotId", "s1")
        putJsonArray("elements") {
            for ((index, name) in listOf("微信", "通讯录", "发现", "我").withIndex()) add(buildJsonObject {
                put("ref", "@e$index"); put("name", name); put("role", "button")
            })
        }
        extra?.let { put(it.first, it.second) }
    }
    @Test fun onlyLocalWhitelistedCandidatesCanBecomeCalls() = runTest {
        val coordinator = FastStepCoordinator(DecisionPort { input ->
            assertTrue("发现" in input.choices)
            assertNotNull(input.applicability)
            ChoiceAttempt.Pick("发现", "jev")
        }, FastMode.Enabled)
        val result = coordinator.propose("阅读朋友圈", screen(), setOf("phone_tap"), 1)
        assertEquals("phone_tap", result.proposal!!.call.name)
        assertEquals("s1", Json.parseToJsonElement(result.proposal.call.argsJson).jsonObject.getValue("snapshotId").jsonPrimitive.content)
        assertFalse(coordinator.propose("阅读朋友圈", screen(), setOf("phone_tap"), 2).attempted)
    }
    @Test fun protectedStaleAmbiguousOrOtherAppScreensNeverCallDecisionService() = runTest {
        var requests = 0
        val port = DecisionPort { requests++; ChoiceAttempt.Pick("发现", "jev") }
        val variants = listOf("protected" to JsonPrimitive(true), "screenChangedDuringRead" to JsonPrimitive(true),
            "full" to JsonPrimitive(false), "truncated" to JsonPrimitive(true), "readable" to JsonPrimitive(false))
        for (extra in variants) {
            assertFalse(FastStepCoordinator(port, FastMode.Enabled).propose("朋友圈", screen(extra), setOf("phone_tap"), 1).attempted)
        }
        assertFalse(FastStepCoordinator(port, FastMode.Off).propose("朋友圈", screen(), setOf("phone_tap"), 1).attempted)
        assertFalse(FastStepCoordinator(port, FastMode.Enabled).propose("", screen(), setOf("phone_tap"), 1).attempted)
        assertEquals(0, requests)
    }
    @Test fun supportsOtherAppsAndWebWithBoundRefs() = runTest {
        for (web in listOf(false, true)) {
            val view = if (web) JsonObject(screen().filterKeys { it != "currentApp" } + ("currentSite" to JsonPrimitive("example.com")))
                else screen("currentApp" to JsonPrimitive("com.example.other"))
            val tool = if (web) "web_tap" else "phone_tap"
            val c = FastStepCoordinator(DecisionPort { ChoiceAttempt.Pick("发现", "jev") }, FastMode.Enabled)
            assertEquals(tool, c.propose("打开发现", view, setOf(tool), 1).proposal!!.call.name)
        }
    }
    @Test fun unknownHighConfidenceSelectionCannotExecute() = runTest {
        val c = FastStepCoordinator(DecisionPort { ChoiceAttempt.Pick("pay", "jev") }, FastMode.Enabled)
        assertNull(c.propose("朋友圈", screen(), setOf("phone_tap"), 1).proposal)
    }
    @Test fun nativeAccessibilityRolesWorkWithoutAdmittingTogglesOrInputs() {
        fun withRole(role: String): JsonObject = screen("elements" to JsonArray(screen().getValue("elements").jsonArray.map {
            JsonObject(it.jsonObject + ("role" to JsonPrimitive(role)))
        }))
        for (role in listOf("TextView", "FrameLayout", "ImageButton", "TabLayout\$TabView")) {
            assertTrue(role, "发现" in FastStepCoordinator.candidates(withRole(role), setOf("phone_tap")))
        }
        for (role in listOf("Switch", "CheckBox", "EditText", "RadioButton")) {
            assertTrue(role, FastStepCoordinator.candidates(withRole(role), setOf("phone_tap")).isEmpty())
        }
    }
    @Test fun shadowProposalIsExplicitlyMarked() = runTest {
        val c = FastStepCoordinator(DecisionPort { ChoiceAttempt.Pick("发现", "jev") }, FastMode.Shadow)
        assertEquals("shadow", c.propose("朋友圈", screen(), setOf("phone_tap"), 1).reason)
    }
    @Test fun cancellationIsNotFallback() = runTest {
        val c = FastStepCoordinator(DecisionPort { throw CancellationException() }, FastMode.Enabled)
        val job = async { c.propose("朋友圈", screen(), setOf("phone_tap"), 1) }
        try { job.await(); fail("must cancel") } catch (_: CancellationException) { }
    }
    @Test fun decisionCannotConsumeGrantOrIgnoreGuard() {
        val p = Origin.Decision(1).policy
        assertFalse(p.usesGrant)
        assertTrue(p.judgeWithoutGate && p.refuseUnresolved && p.stopOnGuardWarning)
        assertFalse(p.gatedByCaller)
    }
}
