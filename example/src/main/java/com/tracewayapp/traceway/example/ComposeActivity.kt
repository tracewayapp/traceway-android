package com.tracewayapp.traceway.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.compose.tracewayMask

/** A Compose screen: recorded from its semantics, routes reported through recordNavigation. */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                androidx.compose.runtime.DisposableEffect(nav) {
                    val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                        destination.route?.let { Traceway.recordNavigation("/$it") }
                    }
                    nav.addOnDestinationChangedListener(listener)
                    onDispose { nav.removeOnDestinationChangedListener(listener) }
                }
                NavHost(nav, startDestination = "orders") {
                    composable("orders") { Orders(onOpen = { nav.navigate("orders/$it") }) }
                    composable("orders/{id}") { entry -> OrderDetail(entry.arguments?.getString("id") ?: "") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Orders(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Orders", style = MaterialTheme.typography.headlineMedium) }
        items(listOf("8841", "8842", "8843")) { id ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Order #$id", Modifier.weight(1f))
                    Button(onClick = { onOpen(id) }, Modifier.testTag("open-$id")) { Text("Open") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun OrderDetail(id: String) {
    var note by remember { mutableStateOf("") }
    var gift by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Order #$id", style = MaterialTheme.typography.headlineMedium)
        Text("Shipping to 221B Baker Street", Modifier.tracewayMask())
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") })
        Row { Checkbox(checked = gift, onCheckedChange = { gift = it }); Text("Gift wrap") }
        Button(onClick = { Traceway.reportFullyDisplayed(); throw IllegalStateException("Order $id failed to load") }) {
            Text("Crash")
        }
    }
}
