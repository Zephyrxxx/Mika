package com.wangzan101.mika.calls

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Provides the phone UI when the user chooses this build as the system dialer. */
@Suppress("DEPRECATION")
internal object RoleDialerUi {
    var service: MikaInCallService? = null
    val calls = linkedSetOf<Call>()
    var visible: DialerCallActivity? = null
    private const val id = 27439
    fun update(s: MikaInCallService, bringToFront: Boolean = false) {
        service = s
        visible?.render()
        val notifications = s.getSystemService(NotificationManager::class.java) ?: return
        val current = calls.filter { it.state != Call.STATE_DISCONNECTED }
        if (current.isEmpty()) {
            notifications.cancel(id)
            return
        }
        val ringing = current.any { it.state == Call.STATE_RINGING }
        val channel = if (ringing) "role_incoming" else "role_ongoing"
        notifications.createNotificationChannel(NotificationChannel(channel,
            if (ringing) "来电" else "通话中", if (ringing) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) })
        val intent = Intent(s, DialerCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val show = PendingIntent.getActivity(s, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(s, channel)
            .setSmallIcon(android.R.drawable.sym_action_call).setCategory(Notification.CATEGORY_CALL)
            .setContentTitle(if (ringing) "来电" else "通话进行中").setContentText("打开通话界面")
            .setContentIntent(show).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (ringing) notification.setFullScreenIntent(show, true)
        notifications.notify(id, notification.build())
        if (bringToFront && !ringing) runCatching { s.startActivity(intent) }
    }
    fun clear(s: MikaInCallService) {
        if (service !== s) return
        calls.clear()
        service = null
        visible?.render()
        s.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}

@Suppress("DEPRECATION")
class DialerCallActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onStart() { super.onStart(); RoleDialerUi.visible = this; render() }
    override fun onStop() { if (RoleDialerUi.visible === this) RoleDialerUi.visible = null; super.onStop() }

    fun render() {
        val calls = RoleDialerUi.calls.filter { it.state != Call.STATE_DISCONNECTED }
        if (calls.isEmpty()) { finish(); return }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 48) }
        setContentView(ScrollView(this).apply { addView(panel) })
        fun action(label: String, block: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { runCatching(block).onFailure {
                Toast.makeText(this@DialerCallActivity, "系统未接受该操作", Toast.LENGTH_SHORT).show()
            } } })
        }
        val service = RoleDialerUi.service ?: return
        for (call in calls) {
            val number = if (call.details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED)
                call.details.handle?.schemeSpecificPart else null
            val status = when (call.state) {
                Call.STATE_RINGING -> "来电"
                Call.STATE_ACTIVE -> "通话中"
                Call.STATE_HOLDING -> "已保持"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "选择 SIM 卡"
                else -> "正在连接"
            }
            panel.addView(TextView(this).apply { textSize = 24f; text = "$status  ${number ?: "未知号码"}" })
            when (call.state) {
                Call.STATE_RINGING -> {
                    action("接听") { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                    action("拒接") { call.reject(false, null) }
                }
                Call.STATE_SELECT_PHONE_ACCOUNT -> {
                    val handles = call.details.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
                    val telecom = getSystemService(TelecomManager::class.java) ?: continue
                    for (handle in handles) action(telecom.getPhoneAccount(handle)?.label?.toString() ?: "SIM") {
                        call.phoneAccountSelected(handle, false)
                    }
                    action("取消拨号") { call.disconnect() }
                }
                else -> {
                    if (call.details.can(Call.Details.CAPABILITY_HOLD)) {
                        if (call.state == Call.STATE_ACTIVE) action("保持") { call.hold() }
                        if (call.state == Call.STATE_HOLDING) action("恢复") { call.unhold() }
                    }
                    action("挂断") { call.disconnect() }
                    if (call.state == Call.STATE_ACTIVE) {
                        action("拨号键盘") {
                            android.app.AlertDialog.Builder(this).setTitle("拨号键盘")
                                .setItems("123456789*0#".map { it.toString() }.toTypedArray()) { _, index ->
                                    call.playDtmfTone("123456789*0#"[index])
                                    panel.postDelayed({ call.stopDtmfTone() }, 180)
                                }.show()
                        }
                    }
                }
            }
        }
        service.callAudioState?.let { audio ->
            action(if (audio.isMuted) "取消静音" else "静音") { service.setMuted(!audio.isMuted) }
            val routes = listOf(CallAudioState.ROUTE_EARPIECE to "听筒", CallAudioState.ROUTE_SPEAKER to "扬声器",
                CallAudioState.ROUTE_BLUETOOTH to "蓝牙", CallAudioState.ROUTE_WIRED_HEADSET to "有线耳机")
            for ((route, label) in routes) if (audio.supportedRouteMask and route != 0 && route != audio.route)
                action("切换至$label") { service.setAudioRoute(route) }
        }
        action("打开 Mika") { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) } }
    }
}

class DialerNumberActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        val field = EditText(this).apply {
            id = android.R.id.input
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "电话号码"
            setText(intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty())
        }
        panel.addView(field)
        panel.addView(Button(this).apply { text = "拨打电话"; setOnClickListener {
            val number = field.text.toString().trim()
            if (number.isNotEmpty()) runCatching {
                requireNotNull(getSystemService(TelecomManager::class.java)).placeCall(Uri.fromParts("tel", number, null), Bundle())
            }.onFailure { Toast.makeText(this@DialerNumberActivity, "无法拨号，请检查电话权限", Toast.LENGTH_SHORT).show() }
        } })
        setContentView(panel)
    }
}
