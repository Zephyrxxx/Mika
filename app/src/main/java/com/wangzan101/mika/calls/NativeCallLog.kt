package com.wangzan101.mika.calls

import java.util.UUID

/** Monotonic offsets measure the visible call timer and survive wall-clock adjustments. */
internal class NativeCallLog(
    val start: Long,
    val wallStart: Long,
    val number: String?,
    val direction: CallDirection?,
    val dial: CallRuntime.StockDial?,
) {
    val id = UUID.randomUUID().toString()
    val lines = mutableListOf<CallTranscriptLine>()
    val media = CallMediaCounters()
    private val intervals = mutableListOf<CallInterval>()
    private var boundary = start
    var ai = false
        private set
    var attempted = false
    var result = "通话已结束"
    fun switch(toAi: Boolean, now: Long) {
        if (ai == toAi) return
        interval(now)
        ai = toAi
    }
    fun finish(now: Long) { interval(now) }
    private fun interval(now: Long) {
        if (now <= boundary) return
        intervals += CallInterval(ai, boundary - start, now - start)
        boundary = now
    }
    fun record(summary: Boolean) = CallRecord(
        id, number, direction, wallStart, wallStart + boundary - start, result, lines.toList(),
        intervals = intervals.toList(), task = dial?.task, audioReport = media.recordReport(),
        summaryStatus = if (summary) SummaryStatus.PENDING else SummaryStatus.NOT_REQUESTED,
    )
}
