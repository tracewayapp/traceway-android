package com.tracewayapp.traceway.metrics

/**
 * The one scale every histogram uses, the server's included
 * (`backend/app/repositories/telemetry/shared/histogram.go`): bucket `i`
 * holds the values in (2^(i/8), 2^((i+1)/8)], about 9% wide, so a
 * percentile read from it is within about 4.5% of the true value. One scale
 * everywhere means histograms from different devices add up bucket by
 * bucket.
 */
internal const val HISTOGRAM_SCALE = 3

private const val BUCKETS_PER_DOUBLING = 1 shl HISTOGRAM_SCALE
private const val ZERO_THRESHOLD = 1e-6
private const val MAX_VALUE = 1e12
private const val INV_LN2 = 1.4426950408889634 // 1 / ln(2)

internal fun bucketIndex(value: Double): Int =
    Math.ceil(goLog2(value) * BUCKETS_PER_DOUBLING).toInt() - 1

/**
 * log2 the way Go computes it: exact for powers of two, where
 * `log(x) / ln2` is not, so a value on a bucket boundary lands in the
 * bucket the server would put it in. [StrictMath.log] is fdlibm's, the
 * algorithm Go's `math.Log` is ported from.
 */
internal fun goLog2(x: Double): Double {
    val raw = java.lang.Double.doubleToRawLongBits(x)
    val exp = ((raw ushr 52) and 0x7ff).toInt() - 1022
    val frac = java.lang.Double.longBitsToDouble((raw and (0x7ffL shl 52).inv()) or (1022L shl 52))
    if (frac == 0.5) return (exp - 1).toDouble()
    return StrictMath.log(frac) * INV_LN2 + exp
}

/**
 * A base-2 exponential histogram at [HISTOGRAM_SCALE], the shape OTLP calls
 * `ExponentialHistogram`, as the browser and Flutter SDKs build it.
 */
internal class Histogram {
    var count = 0L
        private set
    var sum = 0.0
        private set
    var min = Double.POSITIVE_INFINITY
        private set
    var max = Double.NEGATIVE_INFINITY
        private set
    var zeroCount = 0L
        private set
    val buckets = HashMap<Int, Long>()

    /** Returns false for a value a histogram cannot hold (negative, NaN, infinite). */
    fun record(value: Double): Boolean {
        if (value.isNaN() || value.isInfinite() || value < 0) return false
        val v = if (value > MAX_VALUE) MAX_VALUE else value
        count++
        sum += v
        if (v < min) min = v
        if (v > max) max = v
        if (v < ZERO_THRESHOLD) {
            zeroCount++
        } else {
            val index = bucketIndex(v)
            buckets[index] = (buckets[index] ?: 0L) + 1
        }
        return true
    }
}
