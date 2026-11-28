package com.tracewayapp.fieldmart.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tracewayapp.fieldmart.ui.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tracewayapp.fieldmart.core.MarketClock
import com.tracewayapp.fieldmart.widget.SparklineView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val Up = Color(0xFF1B873F)
private val Down = Color(0xFFC62828)
private val Muted = Color(0xFF6B7280)
private const val VISIBLE = 80
private val TIMEFRAMES = listOf("1H", "1D", "1W", "1M", "1Y")

/**
 * Markets (02-APP-SPEC.md §2.3), all Compose: a header whose price changes on every tick, a candle chart drawn on a
 * `Canvas` with real `Text` axis labels, a crosshair on drag, a 300-row watchlist with custom-drawn sparklines, an
 * order book sheet, and a quotes poll every 2 s. Everything keeps changing while the screen is resumed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MarketsScreen() {
    val app = LocalApp.current
    val model = MarketsModel.instance
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var version by remember { mutableIntStateOf(0) }
    var tf by remember { mutableStateOf(model.timeframe) }
    var bookOpen by remember { mutableStateOf(false) }
    var crosshair by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(tf) {
        val loaded = withContext(Dispatchers.IO) { runCatching { model.loadBlocking(app.api, tf) }.getOrNull() }
        if (loaded != null) model.accept(tf, loaded.first, loaded.second)
        model.timeframe = tf
        version++
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.appeared(System.currentTimeMillis())
            while (true) {
                val now = System.currentTimeMillis()
                if (model.advance(now)) version++
                val next = model.startedAt + ((model.clock?.n ?: 0).toLong()) * MarketClock.TICK_MS
                delay((next - System.currentTimeMillis()).coerceIn(1, MarketClock.TICK_MS))
            }
        }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val n = withContext(Dispatchers.IO) { runCatching { app.api.quotes(model.lastQuote) }.getOrNull() }
                if (n != null) {
                    model.lastQuote = n
                    model.quotes++
                    version++
                }
                delay(2000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(model, version, onBook = { bookOpen = true })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in TIMEFRAMES) {
                FilterChip(
                    selected = tf == t,
                    onClick = { tf = t },
                    label = { Text(t) },
                    modifier = Modifier.testTag("fm-markets-tf-$t"),
                )
            }
        }
        Chart(model, version, crosshair) { crosshair = it }
        Text(
            stringResource(R.string.fm_l_markets_watchlist),
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("fm-markets-watchlist")) {
            val symbols = model.symbols
            items(symbols.size, key = { symbols[it].symbol }) { k -> WatchRow(model, k, version) }
        }
    }

    if (bookOpen) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { bookOpen = false }, sheetState = state) {
            Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("fm-markets-book").padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fm_l_markets_orderBook), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { bookOpen = false }, Modifier.testTag("fm-markets-book-close")) { Text(stringResource(R.string.fm_l_markets_close)) }
                }
                Book(model, version)
            }
        }
    }
}

@Composable
private fun Header(model: MarketsModel, version: Int, onBook: () -> Unit) {
    val k = model.selected
    val sym = model.symbols.getOrNull(k)
    val price = model.price(k)
    val colour = when {
        version == 0 -> Color.Unspecified
        model.lastDirection > 0 -> Up
        model.lastDirection < 0 -> Down
        else -> Color.Unspecified
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(sym?.symbol ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(sym?.name?.let { "  $it" } ?: "", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    MarketClock.price(price),
                    Modifier.testTag("fm-markets-price"),
                    color = colour,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                val base = sym?.startCents ?: price
                Text(MarketClock.changePercent(price, base), color = if (price >= base) Up else Down)
            }
            Text(stringResource(R.string.fm_l_markets_polls).replace("{n}", model.quotes.toString()), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Button(onClick = onBook, Modifier.testTag("fm-markets-book-open")) { Text(stringResource(R.string.fm_l_markets_openBook)) }
    }
}

private val timeFormat = SimpleDateFormat("MMM d HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun timeLabel(t: Long): String = timeFormat.format(Date(if (t > 100_000_000_000L) t else t * 1000))

@Composable
private fun Chart(model: MarketsModel, version: Int, crosshair: Float?, onCrosshair: (Float?) -> Unit) {
    val all = model.candles()
    val window = if (all.size > VISIBLE) all.copyOfRange(all.size - VISIBLE, all.size) else all
    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    for (c in window) {
        if (c.l < lo) lo = c.l
        if (c.h > hi) hi = c.h
    }
    if (window.isEmpty()) {
        lo = 0
        hi = 1
    }
    val span = (hi - lo).coerceAtLeast(1)
    val price = model.price(model.selected)
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 8.dp).testTag("fm-markets-chart")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onCrosshair(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        onCrosshair(change.position.x)
                        change.consume()
                    }
                    onCrosshair(null)
                }
            },
    ) {
        val labelW = 56.dp
        val plotW = with(density) { (maxWidth - labelW).toPx() }
        val plotH = with(density) { (maxHeight - 20.dp).toPx() }
        fun y(v: Long): Float = plotH - (v - lo).toFloat() / span * plotH
        Canvas(Modifier.fillMaxSize()) {
            if (window.isEmpty()) return@Canvas
            val cw = plotW / VISIBLE
            for ((i, c) in window.withIndex()) {
                val x = i * cw + cw / 2
                val col = if (c.c >= c.o) Up else Down
                drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), strokeWidth = 1.2f)
                val top = y(maxOf(c.o, c.c))
                val bottom = y(minOf(c.o, c.c))
                drawRect(col, Offset(x - cw * 0.35f, top), androidx.compose.ui.geometry.Size(cw * 0.7f, (bottom - top).coerceAtLeast(1f)))
            }
            drawLine(
                Color(0xFF0B6E4F), Offset(0f, y(price)), Offset(plotW, y(price)), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
            crosshair?.let { cx ->
                val x = cx.coerceIn(0f, plotW)
                drawLine(Color(0xFF374151), Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.5f)
            }
        }
        // Axis and price labels are real text, not pixels.
        for (i in 0..4) {
            val v = hi - span * i / 4
            Text(
                MarketClock.price(v),
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (y(v) - 8 * density.density).roundToInt().coerceAtLeast(0)) },
                fontSize = 10.sp,
                color = Muted,
            )
        }
        Text(
            MarketClock.price(price),
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(0, (y(price) - 9 * density.density).roundToInt().coerceIn(0, plotH.roundToInt())) }
                .background(Color(0xFF0B6E4F), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            fontSize = 10.sp,
            color = Color.White,
        )
        if (window.isNotEmpty()) {
            Row(Modifier.align(Alignment.BottomStart).width(maxWidth - labelW), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in listOf(0, VISIBLE / 3, 2 * VISIBLE / 3, window.size - 1)) {
                    Text(timeLabel(window[i.coerceIn(0, window.size - 1)].t), fontSize = 10.sp, color = Muted)
                }
            }
        }
        crosshair?.let { cx ->
            if (window.isNotEmpty()) {
                val i = (cx / (plotW / VISIBLE)).toInt().coerceIn(0, window.size - 1)
                val c = window[i]
                Text(
                    "${timeLabel(c.t)}  O ${MarketClock.price(c.o)}  H ${MarketClock.price(c.h)}  " +
                        "L ${MarketClock.price(c.l)}  C ${MarketClock.price(c.c)}",
                    Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color(0xEEFFFFFF), RoundedCornerShape(4.dp)).padding(6.dp),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun WatchRow(model: MarketsModel, k: Int, version: Int) {
    val sym = model.symbols[k]
    val price = model.price(k)
    val up = price >= sym.startCents
    Row(
        Modifier.fillMaxWidth().clickable { }.testTag("fm-markets-row-${sym.symbol}").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sym.symbol, fontWeight = FontWeight.SemiBold)
            Text(sym.name, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
        }
        val history = model.history(k)
        AndroidView(
            factory = { SparklineView(it) },
            update = { it.setValues(history, up) },
            modifier = Modifier.width(72.dp).height(28.dp),
        )
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
            Text(MarketClock.price(price), fontWeight = FontWeight.Medium)
            Text(MarketClock.changePercent(price, sym.startCents), color = if (up) Up else Down, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = Color(0xFFE5E7EB))
}

@Composable
private fun Book(model: MarketsModel, version: Int) {
    val price = model.price(model.selected)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.fm_l_markets_bids), color = Up, fontWeight = FontWeight.SemiBold)
            for (i in 0 until 20) Row {
                Text(MarketClock.price(price - (i + 1) * 5), Modifier.weight(1f), fontSize = 12.sp)
                Text(model.bookSizes[i].toString(), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.fm_l_markets_asks), color = Down, fontWeight = FontWeight.SemiBold)
            for (i in 0 until 20) Row {
                Text(MarketClock.price(price + (i + 1) * 5), Modifier.weight(1f), fontSize = 12.sp)
                Text(model.bookSizes[20 + i].toString(), fontSize = 12.sp)
            }
        }
    }
}
