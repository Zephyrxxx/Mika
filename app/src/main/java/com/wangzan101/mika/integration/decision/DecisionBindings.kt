package com.wangzan101.mika.integration.decision

import android.content.Context
import ai.typesafe.integration.*
import com.wangzan101.mika.core.decision.*
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.MikaApp

object DecisionBindings {
    private val http by lazy { NetProxy.shared.client() }
    @Volatile var diagnostic: String = "尚未调用"
        private set
    private var count = 0L
    private var accepted = 0L
    private var elapsed = 0L
    private var cachedSettings: JevSettings? = null
    private var cachedEngine: JevEngine? = null

    fun engine(settings: JevSettings) = JevEngine(http, settings.config, settings.key)
    @Synchronized private fun taskEngine(settings: JevSettings): JevEngine {
        if (cachedSettings != settings) { cachedEngine = engine(settings); cachedSettings = settings }
        return cachedEngine!!
    }
    @Synchronized fun settingsChanged() {
        // Explicitly saving settings also recovers from a previous authentication failure.
        cachedSettings = null
        cachedEngine = null
    }

    fun create(context: Context): DecisionPort? {
        val settings = try { JevSettingsStore.read(context) }
        catch (_: Exception) { diagnostic = "配置无法读取或地址不受支持，请检查并重新保存（非本机地址须使用 HTTPS）"; return null }
        if (!settings.enabled || settings.key.isBlank()) return null
        return metered(context, taskEngine(settings))
    }

    /** The settings connection probe also consumes inference tokens and must use the same ledger. */
    fun metered(context: Context, engine: JevEngine): JevDecisionPort =
        JevDecisionPort(engine, (context.applicationContext as MikaApp).usage, ::note)

    @Synchronized private fun note(input: ChoiceInput, result: JevResult) {
        count++
        if (result is JevResult.Accepted) { accepted++; elapsed += result.elapsedMs }
        if (result is JevResult.Abstained) elapsed += result.elapsedMs
        diagnostic = "判断尝试 $count 次，接受 $accepted 次，回退 ${count - accepted} 次，平均 ${elapsed / count} 毫秒\n" +
            when (result) {
                is JevResult.Accepted -> "最近：${input.purpose} / ${result.model} / ${result.elapsedMs} 毫秒\n用量：输入 ${result.inputTokens ?: "未报告"}，输出 ${result.outputTokens ?: "未报告"}"
                is JevResult.Abstained -> "最近回退：${result.reason}"
            }
    }
}
