package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.testing.answerReply
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DecisionIntegrationTest {
    @Test fun intentSkipsChatWhenAccepted() = runTest {
        val chat = FakeModel(answerReply("ACT"))
        val port = DecisionPort { ChoiceAttempt.Pick("READ", "jev") }
        assertEquals(IntentGate.Intent.Read, IntentGate(chat, decisionPort = port).assess("读订单", emptyList()))
        assertTrue(chat.requests.isEmpty())
    }
    @Test fun invalidChoiceAndExceptionFallBack() = runTest {
        for (port in listOf(DecisionPort { ChoiceAttempt.Pick("bad", "jev") }, DecisionPort { error("offline") })) {
            val chat = FakeModel(answerReply("DISCUSS"))
            assertEquals(IntentGate.Intent.Discuss, IntentGate(chat, decisionPort = port).assess("解释", emptyList()))
            assertEquals(1, chat.requests.size)
        }
    }
    @Test fun timeoutFallsBackButUserCancellationDoesNot() = runTest {
        val chat = FakeModel(answerReply("READ"))
        assertEquals(IntentGate.Intent.Read, IntentGate(chat, decisionPort = DecisionPort { delay(2000); error("late") }).assess("读", emptyList()))
        val cancelledChat = FakeModel(answerReply("ACT"))
        val job = async { IntentGate(cancelledChat, decisionPort = DecisionPort { throw CancellationException() }).assess("读", emptyList()) }
        try { job.await(); fail("cancellation expected") } catch (_: CancellationException) { }
        assertTrue(cancelledChat.requests.isEmpty())
    }
    @Test fun newPortTakesPriorityAndAbstentionDoesNotCallLegacyDecision() = runTest {
        for (result in listOf(ChoiceAttempt.Pick("READ", "jev"), ChoiceAttempt.Abstain("uncertain"))) {
            var legacyCalls = 0
            val traces = mutableListOf<String>()
            val chat = FakeModel(answerReply("DISCUSS"))
            val intent = IntentGate(chat,
                decision = DecisionModel { _, _, _, _ ->
                    legacyCalls++
                    DecisionChoice("ACT", 1.0)
                },
                decisionPort = DecisionPort { result },
                onDecision = traces::add).assess("读订单", emptyList())
            assertEquals(0, legacyCalls)
            assertEquals(if (result is ChoiceAttempt.Pick) IntentGate.Intent.Read else IntentGate.Intent.Discuss, intent)
            assertEquals(if (result is ChoiceAttempt.Pick) 0 else 1, chat.requests.size)
            assertEquals(listOf(if (result is ChoiceAttempt.Pick) "selected:Read" else "fallback"), traces)
        }
    }

    @Test fun insufficientOutcomeEvidenceCannotBePromotedByFallback() = runTest {
        val chat = FakeModel(answerReply("""{"outcome":"done"}"""))
        val port = DecisionPort { input ->
            assertTrue(input.state.contains("工具失败"))
            ChoiceAttempt.Pick("unknown", "jev")
        }
        assertEquals(RunOutcome.Unknown, OutcomeJudge(chat, decision = port).judge("支付", "完成了", evidence = "工具失败").outcome)
        assertTrue(chat.requests.isEmpty())
    }
}
