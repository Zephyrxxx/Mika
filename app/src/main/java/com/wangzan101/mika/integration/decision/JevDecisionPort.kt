package com.wangzan101.mika.integration.decision

import ai.typesafe.integration.*
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.usage.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-run routing, never retained by the shared engine. Outcome judging can attach a post-run sink. */
class JevUsageContext(val record: (RunEvent.State) -> Unit, val mode: FastMode = FastMode.Off) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<JevUsageContext>
}

/** Uses Mika's existing ledger and run tally without pretending to be a chat model. */
class JevDecisionPort(
    private val engine: JevEngine,
    private val usageSink: UsageSink,
    private val observed: (ChoiceInput, JevResult) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) : DecisionPort {
    override suspend fun choose(input: ChoiceInput): ChoiceAttempt {
        val response = infer(input, mapOf(input.purpose to ChoiceQuestion(input.instructions, input.choices)))
        val result = if (response is JevResult.Accepted && response.answers.getValue(input.purpose).choice == "abstain")
            JevResult.Abstained("no_candidate", response.elapsedMs) else response
        runCatching { observed(input, result) }
        return when (result) {
            is JevResult.Accepted -> ChoiceAttempt.Pick(result.answers.getValue(input.purpose).choice, result.model, result.elapsedMs)
            is JevResult.Abstained -> ChoiceAttempt.Abstain(result.reason)
        }
    }

    override suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt {
        val root = input.questions.getValue(input.routeQuestion)
        val metadata = ChoiceInput(input.purpose, input.state, root.instructions, root.choices, input.contextVersion, stage = input.stage)
        val result = infer(metadata, input.questions.mapValues { ChoiceQuestion(it.value.instructions, it.value.choices) },
            ChoiceRouting(input.routeQuestion, input.requiredByChoice), ChoicePolicy(input.minProbability, input.minMargin))
        runCatching { observed(metadata, result) }
        return when (result) {
            is JevResult.Accepted -> DecisionBatchAttempt.Pick(result.answers.mapValues { it.value.choice }, result.model, result.elapsedMs)
            is JevResult.Abstained -> DecisionBatchAttempt.Abstain(result.reason, result.diagnostic?.let {
                DecisionFailure(it.question, it.probability, it.runnerUp, it.minProbability, it.minMargin)
            })
        }
    }

    private suspend fun infer(input: ChoiceInput, questions: Map<String, ChoiceQuestion>,
                              routing: ChoiceRouting? = null, policy: ChoicePolicy? = null): JevResult {
        val context = currentCoroutineContext()
        val tally = context[RunUsageTally]
        val recording = context[JevUsageContext]
        return engine.choose(Redactor.text(input.state), questions, input.applicability, routing, policy) { call ->
            val inputTokens = call.inputTokens
            val outputTokens = call.outputTokens
            val usage = if (inputTokens != null && outputTokens != null) Usage(inputTokens, outputTokens) else null
            runCatching { usageSink.record(UsageSource(PROVIDER_ID, PROVIDER_NAME, call.model, UsagePurpose.Other), usage, 1) }
            tally?.add(usage, 1)
            recording?.let { runCatching {
                it.record(RunEvent.State(now(), "decision_usage", buildJsonObject {
                    put("provider", PROVIDER_ID); put("providerName", PROVIDER_NAME)
                    put("model", call.model); put("requestedModel", call.requestedModel); put("role", "decision")
                    put("purpose", input.purpose); put("contextVersion", input.contextVersion)
                    put("mode", if (input.purpose in setOf("next_action", "operation")) it.mode.name.lowercase() else "auxiliary")
                    put("inputTokens", call.inputTokens?.let(::JsonPrimitive) ?: JsonNull)
                    put("outputTokens", call.outputTokens?.let(::JsonPrimitive) ?: JsonNull)
                    put("cachedTokens", JsonNull)
                    put("httpCalls", 1); put("callsWithoutUsage", if (usage == null) 1 else 0)
                    put("latencyMs", call.elapsedMs); put("status", call.status)
                    input.stage?.let { stage -> put("stage", stage) }
                    put("questionCount", call.questionCount); put("totalCandidates", call.totalCandidates); put("requestChars", call.requestChars)
                    call.diagnostic?.takeIf { diagnostic -> diagnostic.question in DIAGNOSTIC_QUESTIONS }?.let { d ->
                        put("failedQuestion", d.question); put("failureKind", d.issue)
                        put("selectedProbability", d.probability); put("runnerUpProbability", d.runnerUp)
                        put("requiredProbability", d.minProbability); put("requiredMargin", d.minMargin)
                        put("failedCandidateCount", d.candidates)
                    }
                }.toString()))
            } }
        }
    }

    companion object {
        private val DIAGNOSTIC_QUESTIONS = setOf("operation", "target", "to_target", "field", "text", "direction", "amount", "key", "app", "prepared",
            "intent", "outcome", "next_action", "connection_test", "__applicable")
        const val PROVIDER_ID = "integration:jev"
        const val PROVIDER_NAME = "JEV 判断加速"
        val providerNames: Map<String, String> = mapOf(PROVIDER_ID to PROVIDER_NAME)
    }
}
