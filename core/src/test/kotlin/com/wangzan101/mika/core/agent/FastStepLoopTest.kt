package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.*
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FastStepLoopTest {
    private class NavigationScreen(private val searchField: Boolean? = null) : ScreenPort by FakeScreen() {
        var taps = 0
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.example", listOfNotNull(
            UiElement("@e1", "TextView", "Settings", 100, 100, listOf(50, 50, 150, 150), null, true, false, false),
            searchField?.let { UiElement("@e2", "EditText", "private draft", 200, 200, listOf(150, 150, 250, 250),
                null, true, true, false, searchField = it) }
        ), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            taps++
            return ActionResult.Ok(changed = true)
        }
    }
    @Test fun enabledUsesExecutorAndBalancedHistoryWithoutChatProposal() = runTest {
        val screen = NavigationScreen()
        val main = FakeModel(answerReply("已打开设置。"), repeatLast = true)
        val judge = FakeModel(answerReply("ACT"))
        val events = mutableListOf<RunEvent>()
        val port = DecisionPort { ChoiceAttempt.Pick(if (it.purpose == "intent") "ACT" else "Settings", "jev") }
        AgentLoop(main, toolDispatcher(screen).also { it.expand("phone") }, "BASE", maxSteps = 4, intentJudge = judge,
            decision = port, fastSteps = FastStepCoordinator(port, FastMode.Enabled), reviewFinalAnswer = false,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run("打开设置")
        assertEquals(events.filterIsInstance<RunEvent.State>().toString(), 1, screen.taps)
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().any { it.source == "decision" })
        val history = main.requests.first()
        val call = history.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().single { it.id.startsWith("decision-") }
        assertTrue(history.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().any { it.callId == call.id })
        assertTrue(judge.requests.isEmpty())
    }
    @Test fun shadowAndDiscussionNeverExecuteDecisionProposal() = runTest {
        for (mode in listOf(FastMode.Shadow, FastMode.Enabled)) {
            val screen = NavigationScreen()
            val main = FakeModel(answerReply("可以讨论设置。"), repeatLast = true)
            val port = DecisionPort { ChoiceAttempt.Pick(if (it.purpose == "intent") "DISCUSS" else "Settings", "jev") }
            AgentLoop(main, toolDispatcher(screen).also { it.expand("phone") }, "BASE", maxSteps = 3, intentJudge = main, decision = port,
                fastSteps = FastStepCoordinator(port, mode), reviewFinalAnswer = false).run("解释设置")
            assertEquals(0, screen.taps)
        }
    }
    @Test fun searchBoxDoesNotHideNavigationButAnEditingFormDoes() = runTest {
        for (search in listOf(true, false)) {
            val screen = NavigationScreen(search)
            val main = FakeModel(answerReply("已处理。"), repeatLast = true)
            val port = DecisionPort {
                assertFalse(it.state.contains("private draft"))
                ChoiceAttempt.Pick(if (it.purpose == "intent") "ACT" else "Settings", "jev")
            }
            AgentLoop(main, toolDispatcher(screen).also { it.expand("phone") }, "BASE", maxSteps = 3,
                intentJudge = main, decision = port, fastSteps = FastStepCoordinator(port, FastMode.Enabled),
                reviewFinalAnswer = false).run("打开设置")
            assertEquals(if (search) 1 else 0, screen.taps)
        }
    }
}
