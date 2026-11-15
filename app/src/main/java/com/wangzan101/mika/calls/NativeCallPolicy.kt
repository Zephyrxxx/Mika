package com.wangzan101.mika.calls

import kotlin.math.abs

/** The stock dialer timer rounds to seconds. A stale tap must never arm a later call. */
internal fun matchesAnsweredRing(requestedAt: Long, now: Long, callStartedAt: Long, offHook: Boolean): Boolean =
    offHook && now - requestedAt in 0L until 10_000L && callStartedAt in (requestedAt - 2200)..now

/** Fail closed if the observable call changes while audio is being controlled. */
internal fun sameNativeCall(
    oldWindow: Int, oldPackage: String, oldStart: Long, oldNumber: String?,
    window: Int, pkg: String, start: Long, number: String?,
): Boolean = oldWindow == window && oldPackage == pkg && abs(oldStart - start) < 2200 &&
    (oldNumber == null && number == null || oldNumber != null && number != null &&
        oldNumber == number)
