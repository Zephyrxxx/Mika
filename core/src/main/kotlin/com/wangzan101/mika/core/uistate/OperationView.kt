package com.wangzan101.mika.core.uistate

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Decision observation includes ordinary controls and states, not a list of preapproved labels. */
internal object OperationView {
    fun token(s: ScreenSnapshot): String {
        // Port refs/read IDs may change on each read. Geometry, visible content and state identify the page.
        val identity = buildString {
            append(s.packageName); append('|'); append(s.web?.url); append('|'); append(s.web?.title)
            append('|'); append(s.web?.dialog); append('|'); append(s.width); append('x'); append(s.height)
            append('|'); append(s.protected); append('|'); append(s.changedDuringRead)
            s.elements.forEach { e ->
                append('|'); append(e.role); append(':'); append(e.bounds); append(':')
                append(if (e.secret) "<secret>" else e.name)
                append(':'); append(e.editable); append(':'); append(e.clickable); append(':'); append(e.focused)
                append(':'); append(e.states.sorted())
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun json(s: ScreenSnapshot, surface: String, texts: Map<String, String> = emptyMap()): JsonObject? {
        if (s.protected || s.changedDuringRead) return null
        val relevant = s.elements.filter { it.meaningful && !it.secret }
        // Put controls first but preserve static text as evidence for completion and targets for long-press/drag.
        val shown = relevant.sortedByDescending { it.clickable || it.editable || it.scrollable }.take(120)
        return buildJsonObject {
            put("ok", s.packageName != null || s.web != null); put("surface", surface)
            put("snapshotId", s.snapshotId); put("stateToken", token(s))
            put("truncated", relevant.size > shown.size)
            if (s.web != null) {
                put("currentSite", runCatching { java.net.URI(s.web.url).host }.getOrNull() ?: "web")
                put("title", s.web.title.take(300))
                if (s.web.dialog != null) put("dialogOpen", true)
            } else s.packageName?.let { put("currentApp", it) }
            putJsonArray("elements") { shown.forEach { e -> add(buildJsonObject {
                put("ref", e.ref); put("role", e.role.take(100))
                // Do not transmit existing form contents; the port verifies typing locally.
                put("name", (if (e.editable) e.placeholder ?: e.resourceId ?: "input field" else e.name).take(160))
                put("clickable", e.clickable); put("editable", e.editable); put("scrollable", e.scrollable)
                put("focused", e.focused); put("disabled", UiStates.DISABLED in e.states)
                if (e.editable) put("matchesTextValues", JsonArray(texts.filterValues { !e.hint && it == e.name }.keys.map(::JsonPrimitive)))
                put("states", JsonArray(e.states.sorted().map(::JsonPrimitive)))
                put("bounds", JsonArray(e.bounds.map(::JsonPrimitive)))
                putJsonObject("center") { put("x", normalize(e.centerX, s.width)); put("y", normalize(e.centerY, s.height)) }
            }) } }
        }
    }
}
