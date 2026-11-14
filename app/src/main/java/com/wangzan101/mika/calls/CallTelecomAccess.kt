package com.wangzan101.mika.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager

/** Only modifies Mika's own Telecom registration and single app-op; never changes the dialer. */
internal class CallTelecomAccess(private val context: Context) {
    private val telecom get() = context.getSystemService(TelecomManager::class.java)
    private val journal = context.getSharedPreferences("mika_call_access", Context.MODE_PRIVATE)
    private val pkg = context.packageName.also { check(it.matches(Regex("[A-Za-z0-9_.]+"))) }
    private val user = Process.myUid() / 100_000
    private val boot get() = Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT)
    @Volatile private var currentBootPrepared = false

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && user == 0 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun hasPhonePermissions(): Boolean = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun ready(): Boolean {
        if (!supported || !hasPhonePermissions()) return false
        val preparedHere = currentBootPrepared || boot?.let { it == journal.getString(PREPARED, null) } == true
        return preparedHere && holdsDialerRole()
    }

    private fun holdsDialerRole(): Boolean {
        val roles = context.getSystemService(android.app.role.RoleManager::class.java)
        return roles?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true &&
            telecom?.defaultDialerPackage == pkg
    }

    fun prepare(): String? {
        if (!supported || !hasPhonePermissions()) return "请允许电话权限，并使用主用户"
        if (!holdsDialerRole()) return "请在系统默认电话应用中选择 Mika 电话兼容版，再检查设置"
        if (!journal.edit().putString(PREPARED, boot).commit()) return "无法保存通话配置"
        currentBootPrepared = true
        return null
    }

    fun rollback(): Boolean {
        currentBootPrepared = false
        return journal.edit().remove(PREPARED).commit()
    }

    private companion object { const val PREPARED = "prepared_boot" }
}
/** Only a fixed diagnostic leaves this classifier; shell output is never retained or displayed. */
internal enum class CallTelecomCommandOutcome {
    OK,
    FAILED,
    SHELL_APP_OPS_DENIED;

    val appOpsFailureMessage: String
        get() = when (this) {
            SHELL_APP_OPS_DENIED -> "系统限制 ADB 修改通话管理权限（缺少 MANAGE_APP_OPS_MODES），无法启用电话伴侣"
            else -> "系统未授予管理通话权限"
        }

    companion object {
        private val shellAppOpsDenied = Regex(
            """(?im)^\h*(?:java\.lang\.)?SecurityException:\h*uid\h+2000\h+does not have\h+android\.permission\.MANAGE_APP_OPS_MODES\b""",
        )
        private val commandError = Regex(
            """(?im)^\s*(unknown command|error[: ]|command\[.*]\s*:\s*error|exception|permission denial|securityexception)""",
        )

        fun classify(exitCode: Int, output: String): CallTelecomCommandOutcome = when {
            shellAppOpsDenied.containsMatchIn(output) -> SHELL_APP_OPS_DENIED
            exitCode != 0 || commandError.containsMatchIn(output) -> FAILED
            else -> OK
        }
    }
}
