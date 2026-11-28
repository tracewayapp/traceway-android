package com.tracewayapp.fieldmart.core

/**
 * The seeded 32-bit generator every Fieldmart build implements (02-APP-SPEC.md §3.4), so the chart, the watchlist
 * and the order book show the same numbers on every platform. Kotlin's [Int] arithmetic wraps at 32 bits, which is
 * the `imul` and `mod 2^32` of the spec.
 */
class Prng(seed: Int) {
    private var state: Int = seed

    /** The next value in [0, 1). */
    fun next(): Double {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    companion object {
        const val BASE_SEED: Int = 20261128

        /** The generator of symbol [k] (0-based): seeded `20261128 XOR (k × 2654435761 mod 2^32)`. */
        fun forSymbol(k: Int): Prng = Prng(BASE_SEED xor (k.toLong() * 2654435761L).toInt())
    }
}
