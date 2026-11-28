package com.tracewayapp.fieldmart.core

/**
 * One symbol's price, tick by tick (02-APP-SPEC.md §3.4): its own generator, and on every tick
 * `cents = max(1, round(cents × (1 + (u − 0.5) × 0.004)))`. Prices stay integer cents.
 */
class SymbolTicker(val index: Int, startCents: Long) {
    private val prng = Prng.forSymbol(index)

    var cents: Long = startCents
        private set

    /** How many ticks this symbol has had. */
    var ticks: Int = 0
        private set

    fun tick(): Long {
        val u = prng.next()
        // Math.round: the nearest integer, halves up, as JavaScript's Math.round for positive values.
        cents = maxOf(1L, Math.round(cents.toDouble() * (1.0 + (u - 0.5) * 0.004)))
        ticks++
        return cents
    }
}

/**
 * The Markets screen's clock: tick `n` happens `n × 100 ms` after the screen first appears, the selected symbol
 * moves on every tick, and the watchlist's 300 symbols move 30 per tick in round-robin order, so visible rows keep
 * changing. Missed ticks (screen away, a slow frame) are caught up in order, so the numbers depend only on time.
 */
class MarketClock(startCents: LongArray, var selected: Int = 0) {
    val symbols: Array<SymbolTicker> = Array(startCents.size) { SymbolTicker(it, startCents[it]) }

    /** Ticks done so far. */
    var n: Int = 0
        private set

    /** Symbols that changed on the latest tick, for the flash and the redraw. */
    val changed: MutableSet<Int> = HashSet()

    /** Runs every tick up to and including tick [target]; returns how many ran. */
    fun advanceTo(target: Int): Int {
        var ran = 0
        changed.clear()
        while (n <= target) {
            step(n)
            n++
            ran++
        }
        return ran
    }

    private fun step(tick: Int) {
        val count = symbols.size
        if (count == 0) return
        symbols[selected].tick()
        changed.add(selected)
        val group = minOf(PER_TICK, count)
        for (i in 0 until group) {
            val k = ((tick.toLong() * PER_TICK + i) % count).toInt()
            if (k == selected) continue
            symbols[k].tick()
            changed.add(k)
        }
    }

    companion object {
        const val TICK_MS: Long = 100
        const val PER_TICK: Int = 30
        const val TICKS_PER_CANDLE: Int = 50

        /** "123.45" from 12345 cents, never through floating point. */
        fun price(cents: Long): String {
            val sign = if (cents < 0) "-" else ""
            val a = Math.abs(cents)
            val frac = (a % 100).toString().padStart(2, '0')
            return "$sign${a / 100}.$frac"
        }

        /** "+1.23%" (two decimals, from integer cents). */
        fun changePercent(now: Long, base: Long): String {
            if (base == 0L) return "+0.00%"
            val bp = Math.round((now - base) * 10000.0 / base)
            val sign = if (bp < 0) "-" else "+"
            val a = Math.abs(bp)
            return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}%"
        }
    }
}
