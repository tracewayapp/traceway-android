package com.tracewayapp.fieldmart.compose

import com.tracewayapp.fieldmart.core.MarketClock
import com.tracewayapp.fieldmart.core.Prng
import com.tracewayapp.fieldmart.data.Api
import com.tracewayapp.fieldmart.data.Candle
import com.tracewayapp.fieldmart.data.Symbol

/**
 * The Markets screen's data, for the life of the process: the symbols, the tick clock (started the first time the
 * screen appears, 02-APP-SPEC.md §3.4), the candle series of each timeframe with its live candle, a short price
 * history per symbol for the sparklines, and the order book. Touched on the main thread only, except [load].
 */
class MarketsModel {
    var symbols: List<Symbol> = emptyList()
        private set
    var clock: MarketClock? = null
        private set
    var startedAt = 0L
        private set
    val selected = 0
    var timeframe = "1H"
    private val series = HashMap<String, Array<Candle>>()
    private var history: Array<LongArray> = emptyArray()
    private var historyCount = IntArray(0)
    private val bookPrng = Prng(Prng.BASE_SEED xor 0x0B00C)
    val bookSizes = IntArray(40) { 100 + it * 7 }
    var quotes = 0
    var lastQuote = 0
    var lastDirection = 0

    fun ready(): Boolean = clock != null && series[timeframe] != null

    /** Network: the symbols once, then the series of [tf] if not loaded. Off the main thread. */
    fun loadBlocking(api: Api, tf: String): Pair<List<Symbol>, Array<Candle>?> {
        val syms = if (symbols.isEmpty()) api.symbols() else symbols
        val candles = if (series[tf] == null && syms.isNotEmpty()) api.candles(syms[selected].symbol, tf).toTypedArray() else null
        return syms to candles
    }

    /** Main thread: takes what [loadBlocking] fetched. */
    fun accept(tf: String, syms: List<Symbol>, candles: Array<Candle>?) {
        if (symbols.isEmpty() && syms.isNotEmpty()) {
            symbols = syms
            clock = MarketClock(LongArray(syms.size) { syms[it].startCents }, selected)
            history = Array(syms.size) { LongArray(HISTORY) }
            historyCount = IntArray(syms.size)
            for (k in syms.indices) push(k, syms[k].startCents)
        }
        if (candles != null && candles.isNotEmpty()) series[tf] = candles
    }

    fun candles(): Array<Candle> = series[timeframe] ?: emptyArray()

    /** Starts the clock the first time the screen shows; tick 0 is that moment. */
    fun appeared(now: Long) {
        if (startedAt == 0L) startedAt = now
    }

    /** Runs every tick due at [now], one by one; true when something changed. */
    fun advance(now: Long): Boolean {
        val c = clock ?: return false
        if (startedAt == 0L) return false
        val target = ((now - startedAt) / MarketClock.TICK_MS).toInt()
        if (target < c.n) return false
        var any = false
        while (c.n <= target) {
            val before = c.symbols[selected].cents
            c.advanceTo(c.n)
            for (k in c.changed) push(k, c.symbols[k].cents)
            val price = c.symbols[selected].cents
            lastDirection = price.compareTo(before)
            liveCandles(c.n - 1, price)
            if ((c.n - 1) % 2 == 0) for (i in bookSizes.indices) bookSizes[i] = 1 + (bookPrng.next() * 2500).toInt()
            any = true
        }
        return any
    }

    private fun liveCandles(tick: Int, price: Long) {
        for ((_, s) in series) {
            if (s.isEmpty()) continue
            val last = s[s.size - 1]
            if (tick > 0 && tick % MarketClock.TICKS_PER_CANDLE == 0) {
                // A new candle every 5 s: the oldest leaves the window, the new one opens at the price.
                val step = if (s.size > 1) s[s.size - 1].t - s[s.size - 2].t else 1
                System.arraycopy(s, 1, s, 0, s.size - 1)
                s[s.size - 1] = Candle(last.t + step, price, price, price, price)
            } else {
                last.c = price
                if (price > last.h) last.h = price
                if (price < last.l) last.l = price
            }
        }
    }

    private fun push(k: Int, cents: Long) {
        val n = historyCount[k]
        if (n < HISTORY) {
            history[k][n] = cents
            historyCount[k] = n + 1
        } else {
            System.arraycopy(history[k], 1, history[k], 0, HISTORY - 1)
            history[k][HISTORY - 1] = cents
        }
    }

    fun history(k: Int): LongArray = history[k].copyOf(historyCount[k])

    fun price(k: Int): Long = clock?.symbols?.get(k)?.cents ?: symbols.getOrNull(k)?.startCents ?: 0

    companion object {
        const val HISTORY = 30
        val instance by lazy { MarketsModel() }
    }
}
