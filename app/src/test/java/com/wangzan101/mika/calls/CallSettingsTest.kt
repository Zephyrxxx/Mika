package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSettingsTest {
    @Test fun fallbackIsSessionOnlyAndCannotBeEnabledThroughSavedPreferences() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("native_phone_compatibility", true).commit()
        val settings = CallSettings(prefs)
        assertFalse(settings.state.value.nativePhoneCompatibility)
        settings.save(settings.state.value.copy(nativePhoneCompatibility = true))
        assertFalse(settings.state.value.nativePhoneCompatibility)
        settings.setManualFallbackSession(true)
        assertTrue(settings.state.value.nativePhoneCompatibility)
        assertFalse(CallSettings(prefs).state.value.nativePhoneCompatibility)
        settings.setManualFallbackSession(false)
        assertFalse(settings.state.value.nativePhoneCompatibility)
    }

    @Test fun modeSwitchRevokesAutomaticAnswerAndStandbyAndDoesNotRestoreThem() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        val automatic = settings.save(settings.state.value.copy(
            enabled = true, audioVerified = true, autoAnswer = true, credentialsConfigured = true,
        ))
        assertTrue(automatic.autoAnswer)
        assertTrue(settings.authorizeBackgroundStandby())

        settings.setManualFallbackSession(true)
        val native = settings.save(settings.state.value)
        assertTrue(native.enabled)
        assertFalse(native.autoAnswer)
        assertFalse(native.backgroundStandbyAuthorized)
        assertFalse(native.audioVerified)
        assertFalse(settings.authorizeBackgroundStandby())
        assertFalse(prefs.getBoolean("auto_answer", true))
        assertFalse(prefs.getBoolean(CallSettings.BACKGROUND_AUTHORIZED, true))

        settings.setManualFallbackSession(false)
        val ordinary = settings.state.value
        assertFalse(ordinary.autoAnswer)
        assertFalse(ordinary.backgroundStandbyAuthorized)
        assertFalse(ordinary.audioVerified)
    }

    @Test fun verifiedCompatibilityCannotEnableAutomaticAnswerOrStandby() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(enabled = true))
        settings.setManualFallbackSession(true)
        val verified = settings.save(settings.state.value.copy(
            audioVerified = true, credentialsConfigured = true, autoAnswer = true,
            backgroundStandbyAuthorized = true,
        ))
        assertTrue(verified.audioVerified)
        assertFalse(verified.autoAnswer)
        assertFalse(verified.backgroundStandbyAuthorized)
        assertFalse(settings.authorizeBackgroundStandby())

        assertFalse(CallSettings(prefs).state.value.nativePhoneCompatibility)
    }

    @Test fun policyRejectsAutomaticAnswerEvenForAnUnnormalisedCompatibilityConfig() {
        val config = CallConfig(enabled = true, autoAnswer = true, audioVerified = true,
            credentialsConfigured = true, allowAllIncoming = true, nativePhoneCompatibility = true)
        assertFalse(CallPolicy.callerAllowed(config, "+8613812345678"))
        assertFalse(CallPolicy.callerAllowed(config, null))
        assertTrue(CallPolicy.callerAllowed(config.copy(nativePhoneCompatibility = false), "+8613812345678"))
    }
    @Test fun officialVerifiedAllowlistedIncomingStillStartsAutomaticAiWithoutManualConsent() {
        val number = "+8613812345678"
        val ready = CallConfig(enabled = true, autoAnswer = true, audioVerified = true,
            credentialsConfigured = true, allowedIncomingNumbers = setOf(number))
        assertTrue(CallPolicy.canStartAutoAnswer(ready, ready, number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(nativePhoneCompatibility = true), number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready.copy(audioVerified = false), number, 100L))
        assertFalse(CallPolicy.canStartAutoAnswer(ready, ready, "+8613912345678", 100L))
    }

    @Test fun enteringManualSessionDurablyRevokesAutomaticConsentBeforeAnyOtherSave() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(enabled = true, autoAnswer = true,
            audioVerified = true, credentialsConfigured = true))
        assertTrue(settings.authorizeBackgroundStandby())
        assertTrue(settings.setManualFallbackSession(true))
        val restarted = CallSettings(prefs).state.value
        assertFalse(restarted.nativePhoneCompatibility)
        assertFalse(restarted.autoAnswer)
        assertFalse(restarted.backgroundStandbyAuthorized)
        assertFalse(restarted.audioVerified)
    }

    @Test fun failedConsentRevocationNeverActivatesManualSession() {
        val backing = MemoryPrefs()
        val prefs = object : android.content.SharedPreferences by backing {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = backing.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun commit(): Boolean = false
                }
            }
        }
        val settings = CallSettings(prefs)
        assertFalse(settings.setManualFallbackSession(true))
        assertFalse(settings.state.value.nativePhoneCompatibility)
        assertFalse(settings.state.value.autoAnswer)
        assertFalse(settings.state.value.backgroundStandbyAuthorized)
    }

}
