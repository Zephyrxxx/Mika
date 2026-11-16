package com.wangzan101.mika.calls

import android.content.SharedPreferences

/** Fork upgrade boundary, not an upstream permission or a per-version reset. */
internal object CallVerificationCompatibility {
    private const val CLEAN_PATH = "phonecompat-clean-v1"
    const val CODE_PATH = "audio_verified_code_path"
    const val MIGRATED_PATH = "phonecompat_call_verification_migrated"

    fun scopeForPackage(packageName: String): String? =
        CLEAN_PATH.takeIf { packageName == "com.wangzan101.mika.phonecompat" }
}

/** Retire only legacy automatic-call authorization, without clearing settings or credentials. */
internal class CallVerificationMigration(
    private val prefs: SharedPreferences,
    private val scope: String?,
) {
    // Android may change the in-memory preference map even when the durable commit fails.
    private var writeFailed = false

    fun ensure(): Boolean {
        if (scope == null) return true
        return try {
            if (!writeFailed && prefs.getString(CallVerificationCompatibility.MIGRATED_PATH, null) == scope) return true
            val hasLegacy = listOf("audio_verified_build", "auto_answer", CallSettings.BACKGROUND_AUTHORIZED).any(prefs::contains)
            if (!writeFailed && !hasLegacy) return true // Fresh installations are untouched.
            val saved = prefs.edit()
                .remove("audio_verified_build")
                .remove(CallVerificationCompatibility.CODE_PATH)
                .putBoolean("auto_answer", false)
                .putBoolean(CallSettings.BACKGROUND_AUTHORIZED, false)
                .putString(CallVerificationCompatibility.MIGRATED_PATH, scope)
                .commit()
            writeFailed = !saved
            saved
        } catch (_: Exception) {
            writeFailed = true
            false
        }
    }
}
