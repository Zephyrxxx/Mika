package com.wangzan101.mika.core.decision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class ChoiceInput(val purpose: String, val state: String, val instructions: String,
                       val choices: Map<String, String>, val contextVersion: String, val applicability: String? = null,
                       val stage: String? = null)
sealed interface ChoiceAttempt {
    data class Pick(val choiceId: String, val model: String, val elapsedMs: Long = 0) : ChoiceAttempt
    data class Abstain(val reason: String) : ChoiceAttempt
}
data class DecisionQuestion(val instructions: String, val choices: Map<String, String>)
/** Independent speculative questions share one observation. Only the selected branch is consumed. */
data class DecisionBatchInput(val purpose: String, val state: String,
    val questions: Map<String, DecisionQuestion>, val routeQuestion: String,
    val requiredByChoice: Map<String, Set<String>>, val contextVersion: String,
    val minProbability: Double = 0.65, val minMargin: Double = 0.10, val stage: String = "combined")
data class DecisionFailure(val question: String, val probability: Double, val runnerUp: Double,
                           val minProbability: Double, val minMargin: Double)
sealed interface DecisionBatchAttempt {
    data class Pick(val values: Map<String, String>, val model: String, val elapsedMs: Long = 0) : DecisionBatchAttempt
    data class Abstain(val reason: String, val detail: DecisionFailure? = null) : DecisionBatchAttempt
}
fun interface DecisionPort {
    suspend fun choose(input: ChoiceInput): ChoiceAttempt
    suspend fun chooseBatch(input: DecisionBatchInput): DecisionBatchAttempt = DecisionBatchAttempt.Abstain("batch_unsupported")
}

suspend fun DecisionPort.attemptBatch(input: DecisionBatchInput): DecisionBatchAttempt = try {
    withTimeoutOrNull(1500) { chooseBatch(input) }?.let { result ->
        if (result is DecisionBatchAttempt.Pick) {
            val route = result.values[input.routeQuestion]
            val required = input.requiredByChoice[route]?.plus(input.routeQuestion)
            if (required == null || required.any { result.values[it] !in input.questions[it]?.choices.orEmpty() })
                DecisionBatchAttempt.Abstain("invalid_choice") else result
        } else result
    } ?: DecisionBatchAttempt.Abstain("timeout")
} catch (e: CancellationException) { throw e }
catch (_: Exception) { DecisionBatchAttempt.Abstain("unavailable") }

/** The host bounds even a broken or replaced adapter; user cancellation always propagates. */
suspend fun DecisionPort.attempt(input: ChoiceInput): ChoiceAttempt = try {
    withTimeoutOrNull(1500) { choose(input) }?.let {
        if (it is ChoiceAttempt.Pick && it.choiceId !in input.choices) ChoiceAttempt.Abstain("invalid_choice") else it
    } ?: ChoiceAttempt.Abstain("timeout")
} catch (e: CancellationException) { throw e }
catch (_: Exception) { ChoiceAttempt.Abstain("unavailable") }
