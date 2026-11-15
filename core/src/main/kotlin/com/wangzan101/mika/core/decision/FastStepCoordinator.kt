package com.wangzan101.mika.core.decision

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.*

enum class FastMode { Off, Shadow, Enabled }
sealed interface StepProposal {
    val reply: ModelReply
    data class Chat(override val reply: ModelReply, val model: ChatModel) : StepProposal
    data class Decision(val call: Part.ToolCall, val picked: ChoiceAttempt.Pick,
                        val stateToken: String? = null, val planned: Boolean = false) : StepProposal {
        override val reply get() = ModelReply(listOf(call), StopReason.ToolUse, null)
    }
}
data class FastAttempt(val attempted: Boolean, val proposal: StepProposal.Decision? = null, val reason: String = "unsupported")

/** Cross-app and web navigation from current UI elements. Code supplies bounded actions and refs.
 * Free-text input, scrolling past unread content and arbitrary buttons remain with the chat model.
 */
class FastStepCoordinator(private val decision: DecisionPort, val mode: FastMode) {
    private val seen = mutableSetOf<String>()
    suspend fun propose(goal: String, screen: JsonObject, offered: Set<String>, step: Int): FastAttempt {
        if (mode == FastMode.Off || !supportsGoal(goal)) return FastAttempt(false)
        val candidates = candidates(screen, offered)
        if (candidates.isEmpty()) return FastAttempt(false)
        val binding = screen.getValue("snapshotId").jsonPrimitive.content
        val surface = if ("currentSite" in screen) "web" else "phone"
        val key = goal + ":" + (screen["currentApp"] ?: screen["currentSite"]) + ":" + candidates.keys.joinToString()
        if (key in seen) return FastAttempt(false, reason = "already_tried")
        if (seen.size >= 8) return FastAttempt(false, reason = "task_budget")
        seen += key
        val picked = decision.attempt(ChoiceInput("next_action", Redactor.text(buildJsonObject {
            put("goal", goal)
            put("page", JsonObject(screen.filterKeys { it in setOf("currentApp", "currentAppName", "currentSite", "title") }))
            put("candidates", JsonArray(candidates.keys.map(::JsonPrimitive)))
        }.toString()), DecisionQuestions.NAVIGATION,
            candidates.mapValues { "Visible navigation destination: ${it.key}" } +
                ("abstain" to "None of these destinations"), "navigation-v3:$binding", DecisionQuestions.NAVIGATION_APPLIES))
        if (picked !is ChoiceAttempt.Pick || picked.choiceId == "abstain") return FastAttempt(true, reason = "abstained")
        val args = candidates[picked.choiceId] ?: return FastAttempt(true, reason = "invalid_candidate")
        val proposal = StepProposal.Decision(Part.ToolCall("decision-$step", "${surface}_tap", args.toString()), picked)
        return FastAttempt(true, proposal, if (mode == FastMode.Shadow) "shadow" else "accepted")
    }

    companion object {
        fun supportsGoal(goal: String) = goal.isNotBlank()
        fun candidates(screen: JsonObject, offered: Set<String>): Map<String, JsonObject> {
            val surface = if ("currentSite" in screen) "web" else "phone"
            if ("${surface}_tap" !in offered || (screen["currentApp"] == null && screen["currentSite"] == null) ||
                screen["ok"] != JsonPrimitive(true) || screen["protected"] == JsonPrimitive(true) ||
                screen["screenChangedDuringRead"] == JsonPrimitive(true) || screen["readable"] == JsonPrimitive(false) ||
                screen["truncated"] == JsonPrimitive(true) || screen["full"] == JsonPrimitive(false)) return emptyMap()
            val snapshot = (screen["snapshotId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return emptyMap()
            val elements = (screen["elements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyMap()
            if (elements.any { it["role"]?.jsonPrimitive?.content?.lowercase() in setOf("password", "edittext", "textfield", "textbox", "input") }) return emptyMap()
            val labels = elements.mapNotNull { (it["name"] as? JsonPrimitive)?.content }
                .filter { it in DecisionQuestions.navigationLabels }.distinct().take(24)
            return labels.mapNotNull { label ->
                val element = elements.singleOrNull { it["name"] == JsonPrimitive(label) } ?: return@mapNotNull null
                if (element["hint"] == JsonPrimitive(true)) return@mapNotNull null
                val role = element["role"]?.jsonPrimitive?.content?.substringAfterLast('.')?.substringAfterLast('$')?.lowercase()
                // Android ScreenReader emits native class names, whereas web snapshots emit ARIA roles.
                // Interfaces has already required a clickable, enabled, non-editable target.
                val roles = if (surface == "phone") setOf("button", "tab", "link", "menuitem", "imagebutton",
                    "textview", "imageview", "view", "viewgroup", "linearlayout", "framelayout", "relativelayout",
                    "constraintlayout", "tabview", "bottomnavigationitemview", "navigationbaritemview",
                    "materialbutton", "appcompatbutton", "appcompattextview") else setOf("button", "tab", "link", "menuitem")
                if (role !in roles) return@mapNotNull null
                val ref = (element["ref"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("@") } ?: return@mapNotNull null
                label to buildJsonObject { put("ref", ref); put("snapshotId", snapshot) }
            }.toMap()
        }
    }
}
