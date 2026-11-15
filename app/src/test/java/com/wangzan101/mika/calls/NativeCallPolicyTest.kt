package com.wangzan101.mika.calls

import org.junit.Assert.*
import org.junit.Test

class NativeCallPolicyTest {
    @Test fun manualAnswerOnlyMatchesAFreshConnectedCall() {
        assertTrue(matchesAnsweredRing(10_000, 12_000, 11_000, true))
        assertTrue(matchesAnsweredRing(10_000, 10_500, 9_000, true))
        assertFalse(matchesAnsweredRing(10_000, 12_000, 11_000, false))
        assertFalse(matchesAnsweredRing(10_000, 20_000, 11_000, true))
        assertFalse(matchesAnsweredRing(10_000, 9_000, 11_000, true))
        assertFalse(matchesAnsweredRing(10_000, 12_000, 7_000, true))
        assertFalse(matchesAnsweredRing(10_000, 12_000, 13_000, true))
    }

    @Test fun callIdentityFailsClosedOnSwitchOrLossOfNumber() {
        fun same(window: Int = 1, pkg: String = "com.vivo.incallui", start: Long = 10_500,
                 number: String? = "+8613800000000") =
            sameNativeCall(1, "com.vivo.incallui", 10_000, "+8613800000000", window, pkg, start, number)
        assertTrue(same())
        assertFalse(same(window = 2))
        assertFalse(same(pkg = "other"))
        assertFalse(same(start = 12_200))
        assertFalse(same(number = "+8613900000000"))
        assertFalse(same(number = null))
        assertTrue(sameNativeCall(1, "phone", 10_000, null, 1, "phone", 10_500, null))
        assertFalse(sameNativeCall(1, "phone", 10_000, null, 1, "phone", 10_500, "1234567"))
    }
}
