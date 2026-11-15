package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSettingsTest {
    @Test fun nativeCompatibilityIsOptInAndPersistsWithoutChangingEnabledState() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        assertFalse(settings.state.value.nativePhoneCompatibility)
        assertFalse(settings.state.value.enabled)

        settings.save(settings.state.value.copy(nativePhoneCompatibility = true))
        val reopened = CallSettings(prefs)
        assertTrue(reopened.state.value.nativePhoneCompatibility)
        assertFalse(reopened.state.value.enabled)
        reopened.save(reopened.state.value.copy(nativePhoneCompatibility = false))
        assertFalse(CallSettings(prefs).state.value.nativePhoneCompatibility)
    }

    @Test fun modeSwitchRevokesAutomaticAnswerAndStandbyAndDoesNotRestoreThem() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        val automatic = settings.save(settings.state.value.copy(
            enabled = true, audioVerified = true, autoAnswer = true, credentialsConfigured = true,
        ))
        assertTrue(automatic.autoAnswer)
        assertTrue(settings.authorizeBackgroundStandby())

        val native = settings.save(settings.state.value.copy(nativePhoneCompatibility = true))
        assertTrue(native.enabled)
        assertFalse(native.autoAnswer)
        assertFalse(native.backgroundStandbyAuthorized)
        assertFalse(native.audioVerified)
        assertFalse(settings.authorizeBackgroundStandby())
        assertFalse(prefs.getBoolean("auto_answer", true))
        assertFalse(prefs.getBoolean(CallSettings.BACKGROUND_AUTHORIZED, true))

        val ordinary = settings.save(native.copy(nativePhoneCompatibility = false))
        assertFalse(ordinary.autoAnswer)
        assertFalse(ordinary.backgroundStandbyAuthorized)
        assertFalse(ordinary.audioVerified)
    }

    @Test fun verifiedCompatibilityCannotEnableAutomaticAnswerOrStandby() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(enabled = true, nativePhoneCompatibility = true))
        val verified = settings.save(settings.state.value.copy(
            audioVerified = true, credentialsConfigured = true, autoAnswer = true,
            backgroundStandbyAuthorized = true,
        ))
        assertTrue(verified.audioVerified)
        assertFalse(verified.autoAnswer)
        assertFalse(verified.backgroundStandbyAuthorized)
        assertFalse(settings.authorizeBackgroundStandby())

        // A legacy or externally inconsistent preference must not revive standby on restart.
        prefs.edit().putBoolean("auto_answer", true)
            .putBoolean(CallSettings.BACKGROUND_AUTHORIZED, true).commit()
        val reopened = CallSettings(prefs).state.value
        assertTrue(reopened.nativePhoneCompatibility)
        assertFalse(reopened.autoAnswer)
        assertFalse(reopened.backgroundStandbyAuthorized)
    }

    @Test fun policyRejectsAutomaticAnswerEvenForAnUnnormalisedCompatibilityConfig() {
        val config = CallConfig(enabled = true, autoAnswer = true, audioVerified = true,
            credentialsConfigured = true, allowAllIncoming = true, nativePhoneCompatibility = true)
        assertFalse(CallPolicy.callerAllowed(config, "+8613812345678"))
        assertFalse(CallPolicy.callerAllowed(config, null))
        assertTrue(CallPolicy.callerAllowed(config.copy(nativePhoneCompatibility = false), "+8613812345678"))
    }
}
