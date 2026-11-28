package com.tracewayapp.fieldmart.compose

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tracewayapp.fieldmart.BuildInfo
import com.tracewayapp.fieldmart.MainActivity
import com.tracewayapp.fieldmart.money
import com.tracewayapp.fieldmart.data.Product
import com.tracewayapp.fieldmart.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun TopBar(title: String, back: Boolean) {
    val main = LocalMain.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) {
            IconButton(onClick = { main.back() }, Modifier.testTag("fm-nav-back")) {
                Icon(painterResource(R.drawable.fm_ic_back), contentDescription = stringResource(R.string.fm_l_nav_back))
            }
        }
        Text(title, Modifier.padding(start = if (back) 4.dp else 12.dp), style = MaterialTheme.typography.titleLarge)
    }
}

/** The searches made in this process, newest first. */
private val recent = mutableStateListOf<String>()

/** Search (02-APP-SPEC.md §2.6): type-ahead against `/fx/api/search`, 250 ms after the last keystroke. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val main = LocalMain.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val found = withContext(Dispatchers.IO) { runCatching { app.api.search(q) }.getOrNull() } ?: return@LaunchedEffect
        results = found
        searched = true
        recent.remove(q)
        recent.add(0, q)
        while (recent.size > 6) recent.removeAt(recent.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.fm_l_search_title), back = true)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("fm-search-field"),
            singleLine = true,
            label = { Text(stringResource(R.string.fm_l_search_field)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
        )
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.fm_l_search_recent), Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEachIndexed { i, r ->
                    AssistChip(onClick = { query = r }, label = { Text(r) }, modifier = Modifier.testTag("fm-search-recent-$i"))
                }
            }
        }
        if (searched && results.isEmpty()) Text(stringResource(R.string.fm_l_search_empty), Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize().testTag("fm-search-results")) {
            itemsIndexed(results, key = { _, p -> p.id }) { i, p ->
                Row(
                    Modifier.fillMaxWidth().clickable { main.push(MainActivity.Screen("product", p.id)) }
                        .testTag("fm-search-result-$i").padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = app.api.productImage(p.id, 0, 160),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8ECEF)),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(p.title, maxLines = 2)
                        Text(p.brand, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                    }
                    Text(money(p.priceCents), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = Color(0xFFE5E7EB))
            }
        }
    }
}

/** More: account links and the way to Diagnostics. */
@Composable
fun MoreScreen() {
    val main = LocalMain.current
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.fm_l_more_title), back = false)
        Text(
            stringResource(R.string.fm_l_more_diagnostics),
            Modifier.fillMaxWidth().clickable { main.push(MainActivity.Screen("diag")) }
                .testTag("fm-more-diag").padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        HorizontalDivider(color = Color(0xFFE5E7EB))
    }
}

/** Diagnostics (02-APP-SPEC.md §2.7): what is running, and buttons that make things go wrong. */
@Composable
fun DiagnosticsScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val s = app.settings
    var status by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(app.telemetry.sessionId) }
    LaunchedEffect(Unit) {
        while (session == null && app.telemetry.name != "none") {
            delay(500)
            session = app.telemetry.sessionId
        }
    }
    fun http(path: String) = scope.launch {
        val code = withContext(Dispatchers.IO) { runCatching { app.api.status(path) }.getOrElse { -1 } }
        status = "$path → $code"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(stringResource(R.string.fm_l_diag_title), back = true)
        SelectionContainer {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Field(stringResource(R.string.fm_l_diag_run), s.run, "fm-diag-run")
                Field(stringResource(R.string.fm_l_diag_variant), s.variant, null)
                Field(stringResource(R.string.fm_l_diag_scenario), s.scenario, null)
                Field(stringResource(R.string.fm_l_diag_build), "${BuildInfo.describe(app)} · ${app.telemetry.name}", null)
                Field(stringResource(R.string.fm_l_diag_session), session ?: "–", "fm-diag-session")
            }
        }
        Text(status, Modifier.padding(16.dp), color = Color(0xFF6B7280))
        val buttons = listOf<Pair<String, () -> Unit>>(
            "fm-diag-handled" to { app.telemetry.captureException(IllegalStateException("FM handled ${s.run}")); status = "handled sent" },
            "fm-diag-unhandled" to { throw IllegalStateException("FM unhandled ${s.run}") },
            "fm-diag-hang" to { blockMainThread(2500) },
            "fm-diag-logs" to { logBurst(); status = "1000 lines written" },
            "fm-diag-http500" to { http("api/fail/500") },
            "fm-diag-slow" to { http("api/slow?ms=1500") },
            "fm-diag-event" to {
                app.telemetry.track("fm.checkout.step", mapOf("step" to "payment", "items" to 3))
                app.telemetry.metric("fm.cart.value", 129.95, mapOf("currency" to "EUR"))
                status = "event and metric recorded"
            },
            "fm-diag-message" to { app.telemetry.captureMessage("FM message ${s.run}"); status = "message sent" },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((tag, action) in buttons) {
                val label = stringResource(diagLabel(tag))
                if (tag == "fm-diag-unhandled" || tag == "fm-diag-hang") {
                    Button(onClick = action, Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
                } else {
                    OutlinedButton(onClick = action, Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, tag: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
        Text(value, if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

private fun diagLabel(tag: String): Int = when (tag) {
    "fm-diag-handled" -> R.string.fm_l_diag_buttons_fm_diag_handled
    "fm-diag-unhandled" -> R.string.fm_l_diag_buttons_fm_diag_unhandled
    "fm-diag-hang" -> R.string.fm_l_diag_buttons_fm_diag_hang
    "fm-diag-logs" -> R.string.fm_l_diag_buttons_fm_diag_logs
    "fm-diag-http500" -> R.string.fm_l_diag_buttons_fm_diag_http500
    "fm-diag-slow" -> R.string.fm_l_diag_buttons_fm_diag_slow
    "fm-diag-event" -> R.string.fm_l_diag_buttons_fm_diag_event
    else -> R.string.fm_l_diag_buttons_fm_diag_message
}

/** `fm-diag-hang`: the main thread is busy for [ms] (an app hang the SDK should report). */
private fun blockMainThread(ms: Long) {
    val end = System.nanoTime() + ms * 1_000_000
    while (System.nanoTime() < end) Thread.sleep(5)
}

/** `fm-diag-logs`: 1,000 numbered lines of 50 characters through the platform logger. */
private fun logBurst() {
    for (n in 1..1000) Log.i("Fieldmart", "FM burst line ${n.toString().padStart(4, '0')} ".padEnd(50, '.'))
}
