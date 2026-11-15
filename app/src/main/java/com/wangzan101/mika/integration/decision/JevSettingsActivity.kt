package com.wangzan101.mika.integration.decision

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*
import ai.typesafe.integration.*
import kotlinx.coroutines.*
import com.wangzan101.mika.core.decision.*

/** Separate screen: no additions to Mika's chat model roles/provider protocol. */
class JevSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = runCatching { JevSettingsStore.read(this) }
        val settings = loaded.getOrDefault(JevSettings())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(content) })
        fun label(text: String) = TextView(this).also { it.text = text; content.addView(it) }
        fun field(title: String, value: String, secret: Boolean = false): EditText {
            label(title)
            return EditText(this).also {
                it.setSingleLine(true)
                it.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                it.setText(value)
                it.isSaveEnabled = false
                if (secret) it.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                content.addView(it)
            }
        }
        label("判断加速 · JEV").textSize = 24f
        label("判断任务意图、结果及受支持的候选选择。不能确定时自动交回原模型。开启后，相关任务文字会发送给这里配置的服务。")
        val enabled = Switch(this).apply { text = "启用 JEV（总开关）"; isChecked = settings.enabled }
        content.addView(enabled)
        label("App / 网页操作加速：主模型规划，JEV 优先选择点击、双击、长按、滚动、滑动、拖动、输入目标及打开 App。主模型准备文字和复杂参数；遇到新情况再重新规划。沿用 Mika 原有权限设置。")
        label("临时判断失败时保留当前计划，主模型处理一步后自动继续尝试。复杂页面会先选动作，再选择所需参数。导出的用量记录可查看具体回退原因和接管状态。")
        label("影子模式只记录 JEV 的建议，实际仍由原模型操作，会增加判断请求。启用操作加速后才会由 JEV 接手执行选择。调用和 Token 会计入模型用量及任务导出。")
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@JevSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("关闭操作加速", "影子模式（只观察）", "启用操作加速（JEV 优先）"))
            setSelection(settings.fastMode.ordinal)
        }
        content.addView(mode)
        val url = field("服务地址（/v1/ 基址或完整 /systemone 地址）", settings.config.baseUrl)
        val key = field("API Key（加密保存在本机）", settings.key, true)
        val model = field("模型名称（可固定版本）", settings.config.model)
        val budget = field("单次等待上限（毫秒，100–1400）", settings.config.budgetMs.toString())
        val status = label(if (loaded.isFailure) "旧配置无法读取或服务地址不受支持，请检查并重新保存；非本机地址须使用 HTTPS。原记录未删除。" else DecisionBindings.diagnostic)
        fun current(): JevSettings {
            val ms = budget.text.toString().toLong()
            require(ms in 100..1400)
            return JevSettings(enabled.isChecked, JevConfig(url.text.toString().trim(), model.text.toString().trim(), ms), key.text.toString().trim(),
                FastMode.entries[mode.selectedItemPosition])
        }
        fun button(text: String, action: suspend () -> Unit) {
            content.addView(Button(this).apply {
                this.text = text
                setOnClickListener {
                    isEnabled = false
                    scope.launch {
                        try { action() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status.text = "未成功：请检查地址、Key、模型名称和网络；等待上限应为 100–1400。" }
                        finally { isEnabled = true }
                    }
                }
            })
        }
        button("保存") {
            val value = current()
            withContext(Dispatchers.IO) { JevSettingsStore.save(this@JevSettingsActivity, value) }
            DecisionBindings.settingsChanged()
            status.text = "已保存，下一个任务生效。"
        }
        button("测试连接并读取模型列表") {
            status.text = "测试中……"
            val engine = DecisionBindings.engine(current())
            val models = engine.models()
            val result = DecisionBindings.metered(this@JevSettingsActivity, engine).choose(ChoiceInput(
                "connection_test", "A red apple.", "What color is the apple?",
                mapOf("red" to "Red", "blue" to "Blue"), "connection-test-v1"))
            status.text = when (result) {
                is ChoiceAttempt.Pick -> "连接成功，${result.model}，${result.elapsedMs} 毫秒。\n可用模型：${models.joinToString()}"
                is ChoiceAttempt.Abstain -> "模型列表读取成功，判断请求回退：${result.reason}\n${models.joinToString()}"
            }
        }
        button("刷新运行统计") { status.text = DecisionBindings.diagnostic }
        button("返回") { finish() }
    }
}
