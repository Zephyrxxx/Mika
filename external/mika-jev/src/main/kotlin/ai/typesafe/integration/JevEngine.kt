package ai.typesafe.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class JevConfig(
    val baseUrl: String = "https://api.typesafe.ai/v1/",
    val model: String = "jev-latest",
    val budgetMs: Long = 1200,
    val minProbability: Double = 0.90,
    val minMargin: Double = 0.25,
) {
    init {
        require(budgetMs in 100..10_000)
        require(minProbability in 0.5..1.0 && minMargin in 0.0..1.0)
        require(model.isNotBlank())
        val url = baseUrl.toHttpUrl()
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1"))
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    }
}

data class ChoiceQuestion(val instructions: String, val criteria: Map<String, String>)
data class ChoiceRouting(val question: String, val requiredByChoice: Map<String, Set<String>>)
data class ChoicePolicy(val minProbability: Double, val minMargin: Double) {
    init { require(minProbability in 0.5..1.0 && minMargin in 0.0..1.0) }
}
data class ChoiceValue(val choice: String, val probabilities: Map<String, Double>)
/** Numeric diagnostics only: never include candidate labels, page text or returned free text. */
data class ChoiceDiagnostic(val question: String, val probability: Double, val runnerUp: Double,
                            val minProbability: Double, val minMargin: Double, val candidates: Int,
                            val issue: String)
sealed interface JevResult {
    data class Accepted(val answers: Map<String, ChoiceValue>, val model: String, val elapsedMs: Long,
                        val inputTokens: Int?, val outputTokens: Int?) : JevResult
    data class Abstained(val reason: String, val elapsedMs: Long = 0, val diagnostic: ChoiceDiagnostic? = null) : JevResult
}

/** One submitted inference request, including rejected judgments and failed/cancelled requests.
 * No prompt, response text or credentials are exposed to the accounting observer. */
data class JevCall(val requestedModel: String, val model: String, val elapsedMs: Long,
                   val inputTokens: Int?, val outputTokens: Int?, val status: String,
                   val diagnostic: ChoiceDiagnostic? = null, val questionCount: Int = 0,
                   val totalCandidates: Int = 0, val requestChars: Int = 0)

/** Plain data only. No Mika/Android classes and no action execution here. */
class JevEngine(
    client: OkHttpClient,
    private val config: JevConfig,
    private val apiKey: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // Retain the injected proxy, connection pool and dispatcher. Never follow a credential-bearing redirect.
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    @Volatile private var disabled = false
    @Volatile private var cooldownUntil = 0L
    private var failures = 0

    suspend fun choose(state: String, questions: Map<String, ChoiceQuestion>, applicability: String? = null,
                       routing: ChoiceRouting? = null, policy: ChoicePolicy? = null,
                       onCall: (JevCall) -> Unit = {}): JevResult {
        if (apiKey.isBlank()) return JevResult.Abstained("missing_key")
        if (disabled) return JevResult.Abstained("authentication")
        if (clock() < cooldownUntil) return JevResult.Abstained("cooldown")
        if (state.length > 32_000 || questions.isEmpty() || questions.size > 16 || "__applicable" in questions ||
            questions.values.any { it.criteria.size !in 2..255 } || routing != null &&
            (questions[routing.question]?.criteria?.keys != routing.requiredByChoice.keys ||
                routing.requiredByChoice.values.flatten().any { it !in questions })) return JevResult.Abstained("invalid_input")
        val start = clock()
        val body = buildJsonObject {
            put("model", config.model)
            val structured = runCatching { Json.parseToJsonElement(state) }.getOrNull()
            put("state", structured?.takeIf { it is JsonObject || it is JsonArray } ?: JsonPrimitive(state))
            putJsonObject("questions") {
                questions.forEach { (id, q) -> putJsonObject(id) {
                    put("type", "choice"); put("instructions", q.instructions)
                    put("criteria", JsonObject(q.criteria.mapValues { JsonPrimitive(it.value) }))
                } }
                applicability?.let { putJsonObject("__applicable") {
                    put("type", "noul"); put("instructions", it)
                } }
            }
        }
        val encoded = body.toString()
        val request = Request.Builder().url(endpoint("systemone"))
            .header("Authorization", "Bearer $apiKey")
            .post(encoded.toRequestBody("application/json".toMediaType())).build()
        var submitted = false
        var reportedModel = config.model
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var status = "network_or_protocol"
        var diagnostic: ChoiceDiagnostic? = null
        val result = try {
            withTimeoutOrNull(config.budgetMs) {
                submitted = true
                val (code, raw) = exchange(request)
                when {
                    code == 401 || code == 403 -> { disabled = true; JevResult.Abstained("authentication") }
                    code == 429 -> { cooldownUntil = clock() + 30_000; JevResult.Abstained("rate_limited") }
                    code !in 200..299 -> JevResult.Abstained("http_$code")
                    else -> {
                        val root = Json.parseToJsonElement(raw).jsonObject
                        reportedModel = (root["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: config.model
                        val usage = root["usage"] as? JsonObject
                        inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                        outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                        parse(root, questions, clock() - start, applicability != null, routing, policy)
                    }
                }
            }.let { it ?: JevResult.Abstained("timeout") }.also {
                status = if (it is JevResult.Abstained) it.reason else "completed"
                diagnostic = (it as? JevResult.Abstained)?.diagnostic
            }
        } catch (e: CancellationException) { status = "cancelled"; throw e }
        catch (_: Exception) { JevResult.Abstained("network_or_protocol") }
        finally {
            if (submitted) runCatching { onCall(JevCall(config.model, reportedModel, clock() - start,
                inputTokens, outputTokens, status, diagnostic, questions.size + if (applicability == null) 0 else 1,
                questions.values.sumOf { it.criteria.size }, encoded.length)) }
        }
        synchronized(this) {
            if (result is JevResult.Accepted || result is JevResult.Abstained && result.reason in setOf("uncertain", "not_applicable")) failures = 0
            else if (result is JevResult.Abstained) {
                failures++
                if (failures >= 3) { cooldownUntil = clock() + 30_000; failures = 0 }
            }
        }
        return if (result is JevResult.Abstained) result.copy(elapsedMs = clock() - start) else result
    }

    suspend fun models(): List<String> = withTimeoutOrNull(5000) {
        val (code, raw) = exchange(Request.Builder().url(endpoint("models"))
            .header("Authorization", "Bearer $apiKey").build())
        check(code in 200..299) { "http_$code" }
        val data = Json.parseToJsonElement(raw).jsonObject["models"]?.jsonArray ?: error("invalid_models")
        data.map { it.jsonObject.getValue("name").jsonPrimitive.content }
    } ?: error("timeout")

    private fun endpoint(path: String): HttpUrl {
        // Support the existing full endpoint and the new base URL setting.
        val base = config.baseUrl.trimEnd('/')
        return (if (base.endsWith("/systemone")) base.removeSuffix("systemone") + path
                else "$base/$path").toHttpUrl()
    }

    private fun parse(root: JsonObject, questions: Map<String, ChoiceQuestion>, elapsed: Long, gated: Boolean,
                      routing: ChoiceRouting?, policy: ChoicePolicy?): JevResult {
        val model = root.getValue("model").jsonPrimitive.content.also { require(it.isNotBlank()) }
        val answers = root.getValue("answers").jsonObject
        if (gated) {
            val gate = answers.getValue("__applicable").jsonObject
            require(gate.getValue("type").jsonPrimitive.content == "noul")
            val probability = gate.getValue("noul").jsonPrimitive.double
            require(probability.isFinite() && probability in 0.0..1.0)
            if (probability < config.minProbability) return JevResult.Abstained("not_applicable", elapsed,
                ChoiceDiagnostic("__applicable", probability, 1.0 - probability, config.minProbability, 0.0, 2, "low_probability"))
        }
        val required = if (routing == null) questions.keys else {
            val route = answers.getValue(routing.question).jsonObject.getValue("choice").jsonPrimitive.content
            routing.requiredByChoice.getValue(route) + routing.question
        }
        val accepted = questions.filterKeys { it in required }.mapValues { (id, q) ->
            val answer = answers.getValue(id).jsonObject
            require(answer.getValue("type").jsonPrimitive.content == "choice")
            val choice = answer.getValue("choice").jsonPrimitive.content
            val probabilities = answer.getValue("probabilities").jsonObject.mapValues { it.value.jsonPrimitive.double }
            require(probabilities.keys == q.criteria.keys && choice in q.criteria)
            require(probabilities.values.all { it.isFinite() && it in 0.0..1.0 })
            require(abs(probabilities.values.sum() - 1.0) <= 0.02)
            val p = probabilities.getValue(choice)
            val second = probabilities.filterKeys { it != choice }.values.maxOrNull() ?: 0.0
            val minP = policy?.minProbability ?: config.minProbability
            val minM = policy?.minMargin ?: config.minMargin
            if (p < minP || p - second < minM) return JevResult.Abstained("uncertain", elapsed,
                ChoiceDiagnostic(id, p, second, minP, minM, q.criteria.size,
                    when { p < second -> "not_highest"; p < minP -> "low_probability"; else -> "small_margin" }))
            ChoiceValue(choice, probabilities)
        }
        val usage = root["usage"] as? JsonObject
        return JevResult.Accepted(accepted, model, elapsed,
            usage?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            usage?.get("output_tokens")?.jsonPrimitive?.intOrNull)
    }

    private suspend fun exchange(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        call.timeout().timeout(config.budgetMs.coerceAtLeast(5000), java.util.concurrent.TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source() ?: error("empty_response")
                        // Bounded read even with chunked transfer or a dishonest Content-Length.
                        require(!source.request(256 * 1024L + 1)) { "response_too_large" }
                        it.code to source.readUtf8()
                    }
                    if (!continuation.isCancelled) continuation.resume(result)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }
}
