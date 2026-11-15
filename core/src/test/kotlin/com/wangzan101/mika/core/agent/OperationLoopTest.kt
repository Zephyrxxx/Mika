package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.*
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationLoopTest {
    private class Screen(private val fail: Boolean = false) : ScreenPort by FakeScreen() {
        var taps = 0; var swipes = 0
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.example", listOf(
            UiElement("@e1", "TextView", "自定义项目 $taps $swipes", 200, 200, listOf(100, 100, 300, 300), null, true, false, false)
        ), 1000, 1000)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps++; return if (fail) ActionResult.Failed("failed") else ActionResult.Ok(changed = true) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { swipes++; return ActionResult.Ok(changed = true) }
    }
    private fun planReply() = ModelReply(listOf(Part.ToolCall("plan", OperationPlanning.TOOL,
        """{"objective":"先点击自定义项目，再向上滑动，然后返回汇报","doneWhen":"滑动已经完成","surface":"phone"}""")), StopReason.ToolUse, null)
    private var sequence = 0
    private fun toolReply(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("main-${++sequence}", name, args)), StopReason.ToolUse, null)
    private class Decisions : DecisionPort {
        var batches = 0
        override suspend fun choose(input: ChoiceInput) = ChoiceAttempt.Pick("ACT", "jev")
        override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
            batches++
            val choices = when (batches) {
                1 -> mapOf("operation" to "tap", "target" to "@e1")
                2 -> mapOf("operation" to "swipe", "direction" to "up", "amount" to "normal")
                else -> mapOf("operation" to "return_to_planner")
            }
            return DecisionBatchAttempt.Pick(choices, "jev", 250)
        }
    }

    @Test fun onePlannerHandoffAllowsMultipleOperationsThenReturnsForFinalAnswer() = runTest {
        val screen = Screen(); val port = Decisions(); val events = mutableListOf<RunEvent>()
        val main = FakeModel(planReply(), answerReply("操作已完成。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 8, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run("点击项目再滑动")
        assertEquals(1, screen.taps); assertEquals(1, screen.swipes); assertEquals(3, port.batches)
        assertTrue(OperationPlanning.TOOL in main.offeredTools.first())
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().count { it.source == "decision" } >= 2)
        val history = main.requests[1]
        val calls = history.flatMap { it.parts }.filterIsInstance<Part.ToolCall>()
        val results = history.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        assertTrue(calls.any { it.name == OperationPlanning.TOOL })
        assertTrue(calls.all { c -> results.any { it.callId == c.id } })
        assertTrue(calls.count { it.id.startsWith("decision-") } >= 2)
    }

    @Test fun mainFallbackCanPlanAndResumeAfterDecisionHandoff() = runTest {
        val screen = Screen()
        val port = Decisions()
        val events = mutableListOf<RunEvent>()
        val primary = FakeModel(ModelError.Auth())
        val backup = FakeModel(planReply(), answerReply("操作已完成。"), repeatLast = true)
        AgentLoop(primary, toolDispatcher(screen), "BASE", maxSteps = 8,
            modelFallbacks = listOf(backup), modelChainTags = listOf("primary", "backup"),
            intentJudge = primary, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run("点击项目再滑动")
        assertEquals(1, screen.taps)
        assertEquals(1, screen.swipes)
        assertEquals(3, port.batches)
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "model_switch" })
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "operation_plan" && it.detail == "accepted" })
        assertTrue(events.filterIsInstance<RunEvent.State>().none { it.name == "operation_plan" && it.detail == "invalid" })
    }

    @Test fun shadowJudgesButDoesNotExecuteActorOperations() = runTest {
        val screen = Screen(); val port = Decisions()
        val main = FakeModel(planReply(), answerReply("这是观察模式。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 4, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Shadow), reviewFinalAnswer = false).run("观察操作")
        assertTrue(port.batches > 0); assertEquals(0, screen.taps); assertEquals(0, screen.swipes)
        assertTrue(main.system.orEmpty().contains("SHADOW mode"))
        assertTrue(main.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().any { it.content.contains("no action was executed") })
    }

    @Test fun failedActionReturnsToMainRatherThanRepeatingTheActor() = runTest {
        val screen = Screen(fail = true); val port = Decisions()
        val main = FakeModel(planReply(), answerReply("操作失败。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 4, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false).run("点击项目")
        assertEquals(1, screen.taps); assertEquals(1, port.batches); assertEquals(0, screen.swipes)
    }

    @Test fun uncertainHandoffResumesAfterOneMainObservationWithoutAnotherPlanCall() = runTest {
        val screen = Screen(); var attempts = 0; val events = mutableListOf<RunEvent>()
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput) = ChoiceAttempt.Pick("ACT", "jev")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
                attempts++
                return when (attempts) {
                    1 -> DecisionBatchAttempt.Abstain("uncertain", DecisionFailure("target", 0.55, 0.45, 0.65, 0.1))
                    2 -> DecisionBatchAttempt.Pick(mapOf("operation" to "tap", "target" to "@e1"), "jev")
                    else -> DecisionBatchAttempt.Pick(mapOf("operation" to "return_to_planner"), "jev")
                }
            }
        }
        val main = FakeModel(planReply(), toolReply("phone_observe"), toolReply("phone_observe"), answerReply("已核对。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 7, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run("点击项目")
        assertEquals(3, attempts); assertEquals(1, screen.taps)
        // Planning, one recovery read, final verification read, final answer. No second planning call.
        assertEquals(4, main.requests.size)
        assertTrue(main.requests[1].flatMap { it.parts }.filterIsInstance<Part.Text>().any { it.text.contains("current phase is retained") && it.text.contains("target") })
        assertEquals(1, events.filterIsInstance<RunEvent.State>().count { it.name == "operation_plan" && it.detail == "accepted" })
        val routes = events.filterIsInstance<RunEvent.State>().filter { it.name == "decision_route" }.map { Json.parseToJsonElement(it.detail).jsonObject }
        assertTrue(routes.any { it["reason"] == JsonPrimitive("uncertain") && it["planState"] == JsonPrimitive("recovering") })
        assertTrue(routes.any { it["source"] == JsonPrimitive("jev") })
    }

    @Test fun failedExecutionGetsAPlannerTurnThenCanResumeTheRetainedPhase() = runTest {
        val screen = Screen(fail = true); val port = Decisions()
        val main = FakeModel(planReply(), toolReply("phone_observe"), toolReply("phone_observe"), answerReply("已处理。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 7, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false).run("点击后滑动")
        assertEquals(1, screen.taps); assertEquals(1, screen.swipes); assertEquals(3, port.batches)
        assertTrue(main.requests[1].flatMap { it.parts }.filterIsInstance<Part.Text>().any { it.text.contains("action_failed") })
    }

    @Test fun mainCanReleaseTheRetainedPhaseForExtendedReasoning() = runTest {
        var calls = 0
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput) = ChoiceAttempt.Pick("ACT", "jev")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt { calls++; return DecisionBatchAttempt.Abstain("uncertain") }
        }
        val screen = Screen()
        val main = FakeModel(planReply(), toolReply(OperationPlanning.TOOL, """{"control":"release"}"""), answerReply("需要先分析内容。"), repeatLast = true)
        AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 5, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false).run("处理页面")
        assertEquals(1, calls); assertEquals(0, screen.taps)
        assertTrue(main.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().any { it.content.contains("phase released") })
    }

    @Test fun firstUiDiscoveryPromptsForDelegationAndDoesNotDisableNormalLightRouting() = runTest {
        val main = FakeModel(toolReply("phone_observe"), toolReply("phone_observe"), answerReply("已读取。"), repeatLast = true)
        val light = FakeModel(toolReply("phone_observe"), answerReply("已读取。"), repeatLast = true)
        val port = object : DecisionPort {
            override suspend fun choose(input: ChoiceInput) = ChoiceAttempt.Pick("READ", "jev")
            override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt = error("no plan was submitted")
        }
        AgentLoop(main, toolDispatcher(Screen()), "BASE", maxSteps = 7, intentJudge = main, decision = port,
            operationSteps = OperationCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false,
            flags = SpeedFlags(lightSteps = true), router = ModelRouter(main, light)).run("读取页面")
        assertTrue(main.requests[1].flatMap { it.parts }.filterIsInstance<Part.Text>().any { it.text.contains("submit operation_plan") })
        assertTrue(light.requests.isNotEmpty())
    }
}
