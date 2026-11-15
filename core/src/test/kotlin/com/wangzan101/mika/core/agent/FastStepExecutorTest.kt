package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FastStepExecutorTest {
    @Test fun autonomousModeDoesNotBypassDecisionProtection() = runTest {
        var taps = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "example", listOf(
                UiElement("@e1", "button", "确认支付", 900, 2300, listOf(800, 2000, 999, 2399), null, true, false, false)
            ), 1080, 2400)
            override suspend fun peek() = snapshot()
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                taps++; return ActionResult.Ok(changed = true)
            }
        }
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("observe", "phone_observe", "{}"))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {},
            recorder = ActionRecorder(com.wangzan101.mika.core.record.RunSink.NONE), autonomous = true)
        val outcome = executor.run(Part.ToolCall("pick", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), Origin.Decision(1))
        assertTrue(outcome is ActionOutcome.Refused)
        assertEquals(0, taps)
    }
}
