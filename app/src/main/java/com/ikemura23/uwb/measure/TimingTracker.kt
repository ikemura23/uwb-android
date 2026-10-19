package com.ikemura23.uwb.measure

import android.os.SystemClock

data class Timings(
    val linkMs: Long? = null,
    val exchangeMs: Long? = null,
    val firstRangingMs: Long? = null,
) {
    /** Time from the OOB exchange completing to the first ranging result. Null unless both are known. */
    val exchangeToRangingMs: Long?
        get() = if (exchangeMs != null && firstRangingMs != null) firstRangingMs - exchangeMs else null
}

/**
 * Records elapsed time, in milliseconds, of each connection phase relative to [start].
 * Each mark is recorded only once; marks taken before [start] are ignored.
 */
class TimingTracker(private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos) {
    private var startNanos: Long? = null
    private var linkNanos: Long? = null
    private var exchangeNanos: Long? = null
    private var firstRangingNanos: Long? = null

    fun start() {
        startNanos = nowNanos()
        linkNanos = null
        exchangeNanos = null
        firstRangingNanos = null
    }

    fun markLinkEstablished() {
        if (startNanos != null && linkNanos == null) linkNanos = nowNanos()
    }

    fun markExchangeCompleted() {
        if (startNanos != null && exchangeNanos == null) exchangeNanos = nowNanos()
    }

    fun markFirstRanging() {
        if (startNanos != null && firstRangingNanos == null) firstRangingNanos = nowNanos()
    }

    val timings: Timings
        get() = Timings(
            linkMs = linkNanos?.let(::elapsedMs),
            exchangeMs = exchangeNanos?.let(::elapsedMs),
            firstRangingMs = firstRangingNanos?.let(::elapsedMs),
        )

    private fun elapsedMs(atNanos: Long): Long = (atNanos - checkNotNull(startNanos)) / 1_000_000
}
