package com.wangzan101.mika.calls

import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.*
import org.junit.Test

class CallVerificationCompatibilityTest {
    private val scope = CallVerificationCompatibility.scopeForPackage("com.wangzan101.mika.phonecompat")!!
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(encrypted: ByteArray) = encrypted.copyOf()
    }
    private fun settings(prefs: SharedPreferences) = CallSettings(prefs, cipher, scope, "device")
    private fun legacy() = MemoryPrefs().apply {
        edit().putBoolean("enabled", true).putBoolean("auto_answer", true)
            .putBoolean(CallSettings.BACKGROUND_AUTHORIZED, true)
            .putString("audio_verified_build", "device").putString("greeting", "keep greeting")
            .putString("opaque_other_pref", "keep untouched").commit()
    }

    @Test fun legacyAuthorizationIsRetiredOnceWithoutErasingPreferences() {
        val prefs = legacy()
        val first = settings(prefs)
        assertTrue(first.state.value.enabled)
        assertFalse(first.state.value.audioVerified)
        assertFalse(first.state.value.autoAnswer)
        assertFalse(first.state.value.backgroundStandbyAuthorized)
        assertEquals("keep greeting", first.state.value.greeting)
        assertEquals("keep untouched", prefs.getString("opaque_other_pref", null))
        assertEquals(scope, prefs.getString(CallVerificationCompatibility.MIGRATED_PATH, null))
        // Explicit re-verification alone must not silently re-enable automatic answering.
        first.save(first.state.value.copy(audioVerified = true))
        assertFalse(first.state.value.autoAnswer)
        first.save(first.state.value.copy(autoAnswer = true))
        val reopened = settings(prefs)
        assertTrue(reopened.state.value.audioVerified)
        assertTrue(reopened.state.value.autoAnswer)
        assertFalse(reopened.state.value.backgroundStandbyAuthorized)
    }

    @Test fun freshAndUpstreamInstallationsAreNotMigrated() {
        val fresh = MemoryPrefs()
        settings(fresh)
        assertTrue(fresh.all.isEmpty())
        val upstream = legacy()
        val before = upstream.all
        assertNull(CallVerificationCompatibility.scopeForPackage("com.wangzan101.mika"))
        assertNull(CallVerificationCompatibility.scopeForPackage("com.wangzan101.mika.phonecompat.dev"))
        CallSettings(upstream, cipher, null, "device")
        assertEquals(before, upstream.all)
    }

    @Test fun failedDurableCommitStaysClosedEvenIfAndroidChangedItsMemoryMap() {
        val memory = legacy()
        var fail = true
        var attempts = 0
        val prefs = object : SharedPreferences by memory {
            override fun edit(): SharedPreferences.Editor {
                val editor = memory.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun commit(): Boolean { attempts++; editor.commit(); return !fail }
                }
            }
        }
        val migration = CallVerificationMigration(prefs, scope)
        assertFalse(migration.ensure())
        assertFalse(migration.ensure())
        assertEquals(2, attempts)
        fail = false
        assertTrue(migration.ensure())
        val savedAttempts = attempts
        assertTrue(migration.ensure())
        assertEquals(savedAttempts, attempts)
        assertFalse(settings(prefs).state.value.autoAnswer)
    }

    @Test fun settingsCannotAuthorizeAfterFailedMigrationAndDiskRestartRetries() {
        val memory = legacy()
        val prefs = object : SharedPreferences by memory {
            override fun edit(): SharedPreferences.Editor {
                val editor = memory.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val failed = settings(prefs)
        assertFalse(failed.state.value.audioVerified)
        assertFalse(failed.state.value.autoAnswer)
        assertFalse(failed.authorizeBackgroundStandby())
        assertThrows(IllegalStateException::class.java) {
            failed.save(failed.state.value.copy(audioVerified = true, autoAnswer = true))
        }
        assertFalse(failed.state.value.autoAnswer)
        // Reopening the failed in-memory map cannot restore authorization.
        assertFalse(settings(prefs).state.value.autoAnswer)
        // Simulate a process restart from the unchanged legacy disk image.
        val disk = legacy()
        val restarted = settings(disk)
        assertFalse(restarted.state.value.audioVerified)
        assertFalse(restarted.state.value.autoAnswer)
        assertEquals(scope, disk.getString(CallVerificationCompatibility.MIGRATED_PATH, null))
    }

    @Test fun staleProfileCannotRestoreVerificationAndCredentialsRemainIntact() {
        val backend = object : CallVoiceProfileBackend {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String): Boolean { raw = value; return true }
        }
        val old = CallVoiceProfileStore(backend)
        val profile = old.save(CallVoiceProfile(id = "test", name = "preserve profile"), VolcRealtimeCredentials(apiKey = "synthetic-key"))
        old.markAudioVerified(profile.id, CallAudioMode.REDIRECT, true, "device")
        val original = backend.raw
        val clean = CallVoiceProfileStore(backend, verificationScope = scope)
        assertFalse(clean.isAudioVerified(profile.id, CallAudioMode.REDIRECT, "device"))
        assertEquals(original, backend.raw) // No decrypt/rewrite migration of the profile store.
        assertEquals("synthetic-key", clean.credentials(profile.id)?.apiKey)
        clean.save(old.get(profile.id)!!)
        assertFalse(clean.isAudioVerified(profile.id, CallAudioMode.REDIRECT, "device"))
        val settings = settings(legacy())
        settings.save(settings.state.value.copy(audioVerified = clean.isAudioVerified(profile.id, CallAudioMode.REDIRECT, "device")))
        assertFalse(settings.state.value.autoAnswer)
        clean.markAudioVerified(profile.id, CallAudioMode.REDIRECT, true, "device")
        val reopened = CallVoiceProfileStore(backend, verificationScope = scope)
        assertTrue(reopened.isAudioVerified(profile.id, CallAudioMode.REDIRECT, "device"))
        assertEquals("preserve profile", reopened.get(profile.id)?.name)
        assertEquals("synthetic-key", reopened.credentials(profile.id)?.apiKey)
    }
}
