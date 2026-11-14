package com.wangzan101.mika.calls

import android.media.AudioManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.*

/** Audio for a user-established call. The system Phone app owns all Telecom actions. */
internal class NativeCallAudio(private val app: MikaApp) {
    suspend fun run(
        log: NativeCallLog,
        stillCurrent: () -> Boolean,
        acquireMute: suspend () -> Unit,
        started: () -> Unit,
    ) {
        val runtime = app.calls
        val config = runtime.settings.state.value
        check(config.enabled) { "请先启用 AI 电话" }
        val helper = checkNotNull(app.phone.hub.shell.value) { "请先连接 Mika 内置助手" }
        val profile = checkNotNull(runtime.voiceProfiles.get(config.activeVoiceProfileId)) { "请选择电话语音配置" }
        check(profile.connectionTested) { "请先测试实时语音连接" }
        val credentials = withContext(Dispatchers.IO) { runtime.voiceProfiles.credentials(profile.id) }
        checkNotNull(credentials) { "请配置实时语音凭据" }
        val rules = withContext(Dispatchers.IO) { runtime.instructions.promptSnapshot() }
        val prompt = CallPolicy.prompt(config.systemPrompt, log.direction ?: CallDirection.INCOMING, rules, task = log.dial?.task) +
            " 当前是机主在已接通电话中手动邀请你加入；通话可能由任一方拨出。"
        val mode = if (config.audioMode == CallAudioMode.DIRECT) 1 else 2
        val owner = Binder()
        var session: CallRealtimeSession? = null
        var capture: ParcelFileDescriptor.AutoCloseInputStream? = null
        var playback: ParcelFileDescriptor.AutoCloseOutputStream? = null
        var opened = false
        val audio = app.getSystemService(AudioManager::class.java)
        fun audioTargetCurrent(): Boolean = stillCurrent() &&
            app.phone.hub.shell.value === helper && runtime.settings.state.value.enabled &&
            runtime.settings.state.value.audioMode == config.audioMode && audio.isMicrophoneMute
        try {
            val voice = withTimeout(8_000) {
                VolcRealtimeProvider().connect(profile, credentials, prompt).also { session = it }
            }
            check(voice.setMuted(true)) { "语音服务未就绪" }
            check(stillCurrent()) { "通话界面已变化" }
            acquireMute()
            check(stillCurrent()) { "通话界面已变化" }
            val channel = awaitCallAudioOpen(
                isCurrent = ::audioTargetCurrent,
                nowMs = SystemClock::elapsedRealtime,
            ) {
                // Adopt descriptors before cancellation; only resource-free readiness failures retry.
                withContext(NonCancellable + Dispatchers.IO) {
                    opened = true
                    val b = helper.openCallAudio(mode, owner)
                    if (b?.getBoolean("ready") != true) {
                        if (b?.getString("errorCode") == "cellular_audio_not_ready") return@withContext null
                        error(b?.getString("reason") ?: "双向音频不可用")
                    }
                    @Suppress("DEPRECATION")
                    val down = b.getParcelable<ParcelFileDescriptor>("downlink")
                    @Suppress("DEPRECATION")
                    val up = b.getParcelable<ParcelFileDescriptor>("uplink")
                    capture = down?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                    playback = up?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
                    check(capture != null && playback != null && b.getInt("captureRate") == voice.inputSampleRate &&
                        b.getInt("playRate") == voice.outputSampleRate) { "电话音频格式不兼容" }
                    CallPcmBridge(checkNotNull(capture), checkNotNull(playback)) {
                        withContext(Dispatchers.IO) { helper.flushCallAudio(owner) }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            check(stillCurrent()) { "通话界面已变化" }
            val greeting = CallPolicy.spokenGreeting(config.greeting, log.dial?.task != null)
            withTimeout(config.maxCallSeconds * 1000L) {
                coroutineScope {
                    launch {
                        var previous = -1L
                        var progressAt = SystemClock.elapsedRealtime()
                        val began = progressAt
                        var ready = false
                        while (true) {
                            delay(400)
                            check(audioTargetCurrent()) { "通话或助手状态已变化" }
                            val stats = withContext(Dispatchers.IO) { helper.callAudioStats(owner) }
                            check(stats?.getBoolean("active") == true) { "音频助手已断开" }
                            val bytes = stats.getLong("downBytes")
                            val now = SystemClock.elapsedRealtime()
                            if (bytes != previous) progressAt = now
                            previous = bytes
                            if (!ready && callAudioHasStarted(greeting, bytes, stats.getLong("upBytes"))) {
                                ready = true
                                started()
                            }
                            check(now - progressAt < 4000 && (ready || now - began < 7000)) { "双向音频没有数据" }
                        }
                    }
                    channel.run(voice, greeting, counters = log.media, onTranscript = { role, text ->
                        if (runtime.appendTranscript(log.lines, role, text) && runtime.stockCall === log) runtime.stockState(log)
                    })
                }
            }
        } finally {
            runCatching { session?.close() }
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { capture?.close() }
                runCatching { playback?.close() }
                if (opened) {
                    helper.stopCallAudio(owner)
                    val stats = helper.callAudioStats(owner)
                    if (stats != null) log.media.outputShellAccepted(stats.getLong("upBytes", -1),
                        captureSilencedMs = stats.getLong("clientSilencedMaxMs", -1))
                    check(stats != null && !stats.getBoolean("active")) { "请检查系统电话的音频状态" }
                }
            }
        }
    }
}
