package com.wangzan101.mika.calls

/** Unknown state, setup failures and incomplete restoration must never advertise fallback. */
internal object CallFallbackPolicy {
    fun confirmedRefusal(commandSucceeded: Boolean, readBack: String?, manageGranted: Boolean?, restored: Boolean): Boolean =
        commandSucceeded && restored && readBack in setOf("allow", "default", "ignore", "deny", "errored", "foreground") &&
            manageGranted == false

    fun canOffer(refused: Boolean, enabled: Boolean, companionReady: Boolean, sameHelper: Boolean): Boolean =
        refused && enabled && !companionReady && sameHelper
}
