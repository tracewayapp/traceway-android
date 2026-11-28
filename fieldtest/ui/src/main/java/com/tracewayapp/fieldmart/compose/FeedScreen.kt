package com.tracewayapp.fieldmart.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tracewayapp.fieldmart.data.FeedRow
import com.tracewayapp.fieldmart.data.i
import com.tracewayapp.fieldmart.data.s
import com.tracewayapp.fieldmart.feed.FeedModel
import com.tracewayapp.fieldmart.money
import com.tracewayapp.fieldmart.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Feed (02-APP-SPEC.md §2.4), "Activity", as a `LazyColumn` of the 10,000 rows. Every 50th row is a "Week N"
 * header from the data, kept on top with `stickyHeader`, so a list position is a row index. Jump and go-to
 * controls, pull to refresh. Pictures are Coil `AsyncImage`s, which the replay shows as placeholders (H-A6).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val app = LocalApp.current
    val model = remember { FeedModel.of(app.api) }
    // Loaded pages as Compose state: each row reads its own page, so it updates the moment that page arrives.
    val pages = remember { SnapshotStateMap<Int, List<FeedRow>>() }
    val expanded = remember { SnapshotStateMap<Int, Boolean>() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var goto by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        model.listener = { page -> scope.launch { model.page(page)?.let { pages[page] = it } } }
        // Pages that arrived before the listener was there (the first rows ask for page 0 while composing).
        for (page in model.loadedPages()) model.page(page)?.let { pages[page] = it }
        onDispose { model.listener = null }
    }
    fun row(index: Int): FeedRow? {
        val page = index / FeedModel.PAGE
        // A page loaded before this screen subscribed is read from the model; later ones arrive through `pages`.
        val rows = pages[page] ?: model.page(page)
        if (rows == null) model.load(page)
        return rows?.getOrNull(index - page * FeedModel.PAGE)
    }
    fun refresh() {
        refreshing = true
        pages.remove(0)
        model.refresh()
        scope.launch {
            delay(600)
            refreshing = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fm_l_feed_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = ::refresh, Modifier.testTag("fm-feed-refresh")) {
                Icon(painterResource(R.drawable.fm_ic_refresh), contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.fm_l_feed_refresh), Modifier.padding(start = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { list.scrollToItem(0) } }, Modifier.testTag("fm-feed-jump-start")) {
                Text(stringResource(R.string.fm_l_feed_jumpStart))
            }
            TextButton(onClick = { scope.launch { list.scrollToItem(model.total - 1) } }, Modifier.testTag("fm-feed-jump-end")) {
                Text(stringResource(R.string.fm_l_feed_jumpEnd))
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = goto,
                onValueChange = { goto = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.width(120.dp).testTag("fm-feed-goto-field"),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.fm_l_feed_goTo), fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedButton(
                onClick = { goto.toIntOrNull()?.let { r -> scope.launch { list.scrollToItem(r.coerceIn(0, model.total - 1)) } } },
                Modifier.padding(start = 4.dp).testTag("fm-feed-goto-go"),
            ) { Text(stringResource(R.string.fm_l_feed_go)) }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("fm-feed-list"), state = list) {
                val sections = (model.total + FeedModel.SECTION - 1) / FeedModel.SECTION
                for (sec in 0 until sections) {
                    val first = sec * FeedModel.SECTION
                    stickyHeader(key = first) { Header(first, row(first)) }
                    val count = minOf(FeedModel.SECTION, model.total - first) - 1
                    items(count, key = { first + 1 + it }) { i ->
                        val index = first + 1 + i
                        FeedItem(index, row(index), expanded[index] == true) {
                            expanded[index] = expanded[index] != true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(index: Int, row: FeedRow?) {
    val text = row?.text?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.fm_l_feed_header).replace("{n}", (index / FeedModel.SECTION + 1).toString())
    Text(
        text,
        Modifier.fillMaxWidth().testTag("fm-feed-row-$index").background(Color(0xFFF3F4F6)).padding(horizontal = 16.dp, vertical = 6.dp),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FeedItem(index: Int, row: FeedRow?, open: Boolean, toggle: () -> Unit) {
    val app = LocalApp.current
    val modifier = Modifier.fillMaxWidth().testTag("fm-feed-row-$index")
    if (row == null) {
        Box(modifier.height(56.dp).padding(horizontal = 16.dp, vertical = 8.dp).background(Color(0xFFF1F3F5), RoundedCornerShape(6.dp)))
        return
    }
    when (row.type) {
        "shipment" -> Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = app.api.productImage(row.o.i("productId") ?: 1, 0, 160),
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8ECEF)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(row.text, maxLines = 1, fontWeight = FontWeight.Medium)
                Text(row.o.s("detail") ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { ((row.o.i("progress") ?: 0) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        "alert" -> Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.text, maxLines = 1)
                Text(money(row.o.l2("priceCents")), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Sparkline(row.spark())
        }
        "message" -> Row(modifier.clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
            AsyncImage(
                model = app.api.avatarImage(row.o.i("avatarId") ?: 1),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE8ECEF)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(row.text, fontWeight = FontWeight.Medium)
                Text(messageText(row), maxLines = if (open) Int.MAX_VALUE else 1, style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> Row(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.fm_ic_order), contentDescription = null, tint = Color(0xFF0B6E4F))
            Text(row.text, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
            Text(row.o.s("time") ?: "", fontSize = 12.sp, color = Color(0xFF6B7280))
        }
    }
    HorizontalDivider(color = Color(0xFFE5E7EB))
}

internal fun messageText(row: FeedRow): String = row.lines.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: ""

internal fun FeedRow.spark(): LongArray =
    (o["spark"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.toLongArray() ?: LongArray(0)

private fun kotlinx.serialization.json.JsonObject.l2(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0

@Composable
private fun Sparkline(values: LongArray) {
    if (values.size < 2) return
    val lo = values.min()
    val span = (values.max() - lo).coerceAtLeast(1).toFloat()
    val up = values.last() >= values.first()
    Canvas(Modifier.width(72.dp).height(24.dp)) {
        val step = size.width / (values.size - 1)
        for (i in 1 until values.size) {
            drawLine(
                if (up) Color(0xFF1B873F) else Color(0xFFC62828),
                Offset((i - 1) * step, size.height * (1 - (values[i - 1] - lo) / span)),
                Offset(i * step, size.height * (1 - (values[i] - lo) / span)),
                strokeWidth = 2f,
            )
        }
    }
}
