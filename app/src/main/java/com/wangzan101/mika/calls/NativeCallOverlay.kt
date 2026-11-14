package com.wangzan101.mika.calls

import android.app.KeyguardManager
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.SystemClock
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.*
import kotlin.math.abs

/** The user explicitly operates this overlay on the visible stock phone screen. */
internal class NativeCallOverlay(private val service: ScreenAccessibility) {
    private val app = service.application as MikaApp
    private val telecom = service.getSystemService(TelecomManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val phone = service.getSystemService(TelephonyManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var owner: Screen? = null
    private var ownsMute = false
    private var mutedAtStart = false
    private var closed = false
    private var seenRinging = false
    private var current: NativeCallLog? = null
    private var onScreen = false
    private var ringing = false
    private var answerRequestedAt: Long? = null
    private var wasOffHook = false
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private var lockedButton: OverlayWindow? = null
    private val lockedLabel = mutableStateOf("")
    private val toggleAction: () -> Unit = {
        if (worker != null) {
            status("正在交回…")
            worker?.cancel()
        } else if (ringing) answerRinging() else screen()?.let { begin(it) }
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
                val phoneState = callState()
                val isRinging = phoneState == TelephonyManager.CALL_STATE_RINGING
                val s = if (isRinging) null else runCatching { screen() }.getOrNull()
                if (current == null && isRinging && managed == true) seenRinging = true
                if (phoneState == TelephonyManager.CALL_STATE_IDLE || managed == false) wasOffHook = false
                if (phoneState == TelephonyManager.CALL_STATE_OFFHOOK) wasOffHook = true
                val requested = answerRequestedAt
                val expired = requested != null && now - requested >= 10_000
                if (expired || managed != true || !app.calls.settings.state.value.enabled ||
                    phoneState == null || phoneState == TelephonyManager.CALL_STATE_IDLE || (wasOffHook && isRinging)) {
                    answerRequestedAt = null
                    if (expired) notify("未确认 AI 接通，请查看系统电话界面")
                }
                ringing = isRinging && managed == true && current == null && !wasOffHook &&
                    audio.mode != AudioManager.MODE_IN_CALL && usesStockPhone()
                if (s != null && current == null) {
                    val pending = app.calls.stockDial
                    val dial = pending?.takeIf { s.start - it.sentAt in -2000L..120_000L && !seenRinging &&
                        s.number != null && android.telephony.PhoneNumberUtils.compare(s.number, it.number) }
                    val direction = if (dial != null) CallDirection.OUTGOING else if (seenRinging) CallDirection.INCOMING else null
                    current = NativeCallLog(s.start, System.currentTimeMillis() - (now - s.start),
                        dial?.number ?: s.number, direction, dial)
                    app.calls.clearStockDial()
                    app.calls.stockState(current)
                    val answeredHere = answerRequestedAt?.let { sent ->
                        matchesAnsweredRing(sent, now, s.start, phoneState == TelephonyManager.CALL_STATE_OFFHOOK)
                    } == true
                    answerRequestedAt = null
                    if (dial != null || answeredHere) begin(s)
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
                delay(if (ringing || answerRequestedAt != null || s != null || worker != null) 350 else 850)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun callState(): Int? = runCatching { phone?.callState }.getOrNull()

    private fun usesStockPhone(): Boolean {
        val pkg = telecom?.defaultDialerPackage ?: return false
        return app.calls.settings.state.value.enabled && pkg != app.packageName &&
            pkg == telecom.systemDialerPackage && app.appAccess.current().denial(pkg) == null
    }

    @Suppress("DEPRECATION")
    private fun answerRinging() {
        if (answerRequestedAt != null || current != null || worker != null || wasOffHook ||
            audio.mode == AudioManager.MODE_IN_CALL || !usesStockPhone() ||
            telecom?.isInManagedCall != true || callState() != TelephonyManager.CALL_STATE_RINGING) return
        if (service.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            notify("请在 Mika 的接听页点「来电悬浮接听」，允许接听权限")
            return
        }
        if (!app.calls.state.value.serviceReady || app.phone.hub.shell.value == null) {
            notify("请先在 Mika 设置中连接内置助手并测试电话语音服务")
            return
        }
        try {
            telecom.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)
            seenRinging = true
            answerRequestedAt = SystemClock.elapsedRealtime()
            syncCapsule()
            runCatching { telecom.showInCallScreen(false) }
        } catch (_: RuntimeException) {
            notify("系统未能接听，请用系统电话接听")
        }
    }

    private fun screen(): Screen? {
        if (!usesStockPhone() || telecom?.isInManagedCall != true) return null
        val default = telecom.defaultDialerPackage ?: return null
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
        val text = when {
            worker != null || onScreen -> label
            answerRequestedAt != null -> "正在接听…"
            ringing -> "AI 接听"
            else -> null
        }
        if (closed) return
        val locked = keyguard?.isKeyguardLocked == true
        app.capsule.phoneControl(text.takeUnless { locked }, toggleAction)
        if (text == null || !locked || power?.isInteractive != true || FeatureFlags.get(service).quitRequested() ||
            (!ringing && answerRequestedAt == null && worker == null) || !stockCallVisible()) {
            lockedButton?.close()
            lockedButton = null
        } else {
            lockedLabel.value = text
            if (lockedButton == null) {
                // Only this call button is exposed on the stock lock-screen call UI.
                val host = service.overlays()
                val area = host.area()
                val margin = (16 * service.resources.displayMetrics.density).toInt()
                lockedButton = host.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT,
                    area.insetRight + margin, area.insetTop + margin), secure = true) {
                    Button(onClick = { if (stockCallVisible()) toggleAction() }) { Text(lockedLabel.value) }
                }
            }
        }
    }

    private fun stockCallVisible(): Boolean {
        val pkg = service.rootInActiveWindow?.packageName?.toString() ?: return false
        return usesStockPhone() && app.appAccess.current().denial(pkg) == null &&
            (pkg == telecom?.defaultDialerPackage || pkg in stockUi && systemPackage(pkg))
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
        lockedButton?.close()
        lockedButton = null
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

/** The stock dialer timer rounds to seconds. A stale tap must never arm a later call. */
internal fun matchesAnsweredRing(requestedAt: Long, now: Long, callStartedAt: Long, offHook: Boolean): Boolean =
    offHook && now - requestedAt in 0L until 10_000L && callStartedAt in (requestedAt - 2200)..now
