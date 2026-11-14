package com.wangzan101.mika.voice

/** A call invalidates the entire wake/ASR attempt, even if it ends before ASR returns. */
internal class WakeCallGate {
    private var blocked = true
    private var generation = 0L

    @Synchronized fun update(value: Boolean): Boolean {
        if (blocked == value) return false
        blocked = value
        if (value) generation++
        return true
    }

    @Synchronized fun token(): Long? = if (blocked) null else generation
    @Synchronized fun allows(token: Long): Boolean = !blocked && token == generation
}

/** Telecom can be unavailable without phone permission; audio mode and Mika remain guards. */
internal fun wakeCallBlocked(mikaCall: Boolean, audioBusy: Boolean, telecomCall: Boolean?): Boolean =
    mikaCall || audioBusy || telecomCall == true
