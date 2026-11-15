package com.wangzan101.mika.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFallbackPolicyTest {
    @Test fun onlyVerifiedRefusalWithSuccessfulRestorationOffersFallback() {
        assertTrue(CallFallbackPolicy.confirmedRefusal(true, "default", false, true))
        assertTrue(CallFallbackPolicy.confirmedRefusal(true, "allow", false, true))
        assertFalse(CallFallbackPolicy.confirmedRefusal(false, "default", false, true))
        assertFalse(CallFallbackPolicy.confirmedRefusal(true, null, false, true))
        assertFalse(CallFallbackPolicy.confirmedRefusal(true, "default", null, true))
        assertFalse(CallFallbackPolicy.confirmedRefusal(true, "allow", true, true))
        assertFalse(CallFallbackPolicy.confirmedRefusal(true, "default", false, false))
        assertFalse(CallFallbackPolicy.confirmedRefusal(true, "unexpected", false, true))
    }

    @Test fun restoredOfficialPathAndExpiredHelperConsentAlwaysWin() {
        assertTrue(CallFallbackPolicy.canOffer(true, true, false, true))
        assertFalse(CallFallbackPolicy.canOffer(false, true, false, true))
        assertFalse(CallFallbackPolicy.canOffer(true, false, false, true))
        assertFalse(CallFallbackPolicy.canOffer(true, true, true, true))
        assertFalse(CallFallbackPolicy.canOffer(true, true, false, false))
    }
}
