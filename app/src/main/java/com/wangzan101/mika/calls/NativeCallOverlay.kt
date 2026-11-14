package com.wangzan101.mika.calls

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.*
import kotlin.math.abs

/** The user explicitly operates this overlay on the visible stock phone screen. */
internal class NativeCallOverlay(private val service: AccessibilityService) {
    private val app = service.application as MikaApp
    private val telecom = service.getSystemService(TelecomManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var owner: Screen? = null
    private var ownsMute = false
    private var mutedAtStart = false
    private var closed = false
    private var seenRinging = false
    private var current: NativeCallLog? = null
    private var onScreen = false
    private val toggleAction: () -> Unit = {
        if (worker != null) {
            status("正在交回…")
            worker?.cancel()
        } else screen()?.let { begin(it) }
    }
    private var label = "AI 接管"
    private val timeText = Regex("""(?:\d{1,2}:)?\d{1,3}:\d{2}""")
    private val stockUi = setOf("com.android.incallui", "com.vivo.incallui", "com.bbk.incallui", "com.android.phone")
    private data class Screen(val window: Int, val pkg: String, val start: Long, val mute: AccessibilityNodeInfo, val number: String?)

    internal suspend fun handBack(id: String): Boolean {
        val active = worker ?: return false
        if (current?.id != id) return false
        active.cancelAndJoin()
        return true
    }

    fun start() {
        scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val managed = runCatching { telecom?.isInManagedCall }.getOrNull()
                val s = runCatching { screen() }.getOrNull()
                if (current == null && managed == true) {
                    @Suppress("DEPRECATION")
                    if (runCatching { service.getSystemService(android.telephony.TelephonyManager::class.java).callState }.getOrNull() ==
                        android.telephony.TelephonyManager.CALL_STATE_RINGING) seenRinging = true
                }
                if (s != null && current == null) {
                    val pending = app.calls.stockDial
                    val dial = pending?.takeIf { s.start - it.sentAt in -2000L..120_000L && !seenRinging &&
                        s.number != null && android.telephony.PhoneNumberUtils.compare(s.number, it.number) }
                    val direction = if (dial != null) CallDirection.OUTGOING else if (seenRinging) CallDirection.INCOMING else null
                    current = NativeCallLog(s.start, System.currentTimeMillis() - (now - s.start),
                        dial?.number ?: s.number, direction, dial)
                    app.calls.clearStockDial()
                    app.calls.stockState(current)
                    if (dial != null) begin(s)
                }
                val previous = current
                val changedCall = s != null && previous != null && (abs(previous.start - s.start) >= 2200 ||
                    (previous.number != null && s.number != null && !android.telephony.PhoneNumberUtils.compare(previous.number, s.number)))
                if (owner != null && !same(s)) worker?.cancel()
                if (managed == false || changedCall || !app.calls.settings.state.value.enabled) {
                    finishCall(now)
                    seenRinging = false
                    val pending = app.calls.stockDial
                    if (pending != null && now - pending.sentAt > 120_000L) app.calls.clearStockDial()
                }
                onScreen = s != null && !changedCall && current != null
                syncCapsule()
                delay(if (s != null || worker != null) 350 else 850)
            }
        }
    }

    private fun screen(): Screen? {
        if (!app.calls.settings.state.value.enabled || telecom?.isInManagedCall != true) return null
        val default = telecom.defaultDialerPackage ?: return null
        if (default == app.packageName || default != telecom.systemDialerPackage) return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg != default && (pkg !in stockUi || !systemPackage(pkg))) return null
        if (app.appAccess.current().denial(pkg) != null) return null
        var number: String? = null
        var duration: Int? = null
        var mute: AccessibilityNodeInfo? = null
        var ambiguous = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count++ < 500) {
            val n = queue.removeFirst()
            if (!n.isVisibleToUser) continue
            val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
            val labels = listOfNotNull(n.text?.toString()?.trim(), n.contentDescription?.toString()?.trim())
            if (id.contains("number") || id in setOf("contact_name", "primary", "name") || app.calls.stockDial != null) {
                labels.firstNotNullOfOrNull { CallPolicy.outgoingNumber(it) }?.let { candidate ->
                    val expected = app.calls.stockDial?.number
                    if (expected == null || android.telephony.PhoneNumberUtils.compare(expected, candidate)) number = candidate
                }
            }
            val controlLabels = labels.map { it.substringBefore('，').substringBefore(',').trim() }
            if (n.isEnabled && labels.any { it in blockingLabels || it.contains("紧急呼叫") || it.contains("紧急通话") }) ambiguous = true
            val stamp = labels.firstOrNull { timeText.matches(it) }
            if (stamp != null) {
                val sec = stamp.split(':').fold(0) { a, b -> a * 60 + b.toInt() }
                if (duration != null && duration != sec) ambiguous = true
                duration = sec
            }
            if (id in muteIds || controlLabels.any { it in muteLabels }) {
                var target: AccessibilityNodeInfo? = n
                repeat(3) { if (target?.isClickable == false) target = target?.parent }
                if (target?.isClickable == true && target?.isEnabled == true) mute = target
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (ambiguous || queue.isNotEmpty()) return null
        return Screen(root.windowId, pkg, SystemClock.elapsedRealtime() - (duration ?: return null) * 1000L,
            mute ?: return null, number)
    }

    private fun systemPackage(pkg: String) = runCatching {
        service.packageManager.getApplicationInfo(pkg, 0).flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    private fun same(s: Screen?): Boolean {
        val previous = owner ?: return false
        return s != null && previous.window == s.window && previous.pkg == s.pkg && abs(previous.start - s.start) < 2200
    }

    private fun syncCapsule() {
        if (!closed) app.capsule.phoneControl(if (onScreen || worker != null) label else null, toggleAction)
    }

    private fun finishCall(at: Long) {
        val log = current ?: return
        current = null
        app.calls.stockState(null)
        val active = worker
        active?.cancel()
        fun save() {
            log.finish(at)
            app.calls.saveStockCall(log)
        }
        if (active == null || active.isCompleted) save() else active.invokeOnCompletion {
            android.os.Handler(android.os.Looper.getMainLooper()).post { save() }
        }
    }

    private fun begin(s: Screen) {
        if (worker != null) return
        val log = current ?: return
        if (abs(log.start - s.start) >= 2200) return
        log.attempted = true
        owner = s
        mutedAtStart = audio.isMicrophoneMute
        ownsMute = false
        status("连接中 · 取消")
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                NativeCallAudio(app).run(
                    log = log,
                    stillCurrent = { same(screen()) },
                    acquireMute = {
                        val active = screen()
                        check(same(active)) { "通话已切换" }
                        if (!audio.isMicrophoneMute) {
                            check(!mutedAtStart) { "麦克风状态已变化" }
                            ownsMute = active!!.mute.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            check(ownsMute) { "系统静音键未响应" }
                            withContext(NonCancellable) { withTimeout(1800) { while (!audio.isMicrophoneMute) delay(70) } }
                        }
                    },
                    started = {
                        log.switch(true, SystemClock.elapsedRealtime())
                        app.calls.stockState(log)
                        status("交回本人")
                    }
                )
            } catch (e: TimeoutCancellationException) {
                notify("AI 连接或通话已到时限")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message?.take(90) ?: "AI 音频连接失败")
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (ownsMute && audio.isMicrophoneMute) {
                            val current = runCatching { screen() }.getOrNull()
                            if (same(current)) {
                                current!!.mute.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                withTimeoutOrNull(1500) { while (audio.isMicrophoneMute) delay(75) }
                            }
                            if (audio.isMicrophoneMute && telecom?.isInManagedCall == true)
                                notify("AI 已退出，请在系统电话中解除静音")
                        }
                    } finally {
                        if (current === log) {
                            log.switch(false, SystemClock.elapsedRealtime())
                            app.calls.stockState(log)
                        }
                        ownsMute = false
                        owner = null
                        worker = null
                        status("AI 接管")
                    }
                }
            }
        }
        worker = task
        task.start()
    }

    private fun status(text: String) { label = text; syncCapsule() }
    private fun notify(message: String) { Toast.makeText(service, message, Toast.LENGTH_LONG).show() }
    fun close() {
        closed = true
        app.capsule.phoneControl(null, toggleAction)
        finishCall(SystemClock.elapsedRealtime())
        scope.cancel()
    }

    private companion object {
        val muteLabels = setOf("静音", "麦克风", "解除静音", "取消静音", "关闭静音", "开启静音", "静音，已开启", "静音，未开启", "Mute", "Unmute")
        val muteIds = setOf("mute", "mute_button", "mute_btn", "incall_mute", "incall_mute_button", "button_mute")
        val blockingLabels = setOf("通话保持中", "已保持", "切换通话", "合并通话", "正在拨号", "正在呼叫", "来电等待", "紧急电话", "On hold", "Swap", "Merge calls", "Emergency call")
    }
}
