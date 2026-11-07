package dev.junepark.oniontalkie.metrics

/**
 * One per-second snapshot of call quality. This is both the unit written as a CSV row
 * and the value published on [MetricsLogger.stats] for the live metrics screen.
 *
 * Counters ([late], [plc]) and rates ([lossPct], [txKbps], [rxKbps]) cover the interval
 * since the previous snapshot; [jitterMs], [rttMs] and [bufferMs] are the latest values.
 */
data class CallStats(
    /** Whole seconds since the call started. */
    val tSeconds: Long = 0,
    /** Latest PING/PONG round-trip time in ms, or `null` before the first PONG. */
    val rttMs: Double? = null,
    /** Interarrival jitter (RFC 3550 §6.4.1) in ms. */
    val jitterMs: Double = 0.0,
    /** Packets lost in the interval, as a percentage of those expected (0..100). */
    val lossPct: Double = 0.0,
    /** Frames that arrived too late to be played in the interval. */
    val late: Int = 0,
    /** Frames concealed by packet-loss concealment in the interval. */
    val plc: Int = 0,
    /** Current jitter-buffer delay in ms. */
    val bufferMs: Double = 0.0,
    /** Send bitrate over the interval, kbit/s. */
    val txKbps: Double = 0.0,
    /** Receive bitrate over the interval, kbit/s. */
    val rxKbps: Double = 0.0,
    /** Tor circuit id carrying the call, or `null` when unknown. */
    val circuitId: String? = null,
    /** Number of relays in the circuit (from `GETINFO circuit-status`), or `null` when unavailable. */
    val hops: Int? = null,
)
