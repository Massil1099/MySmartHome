package fr.mastersid.massil_andrianina.mysmarthome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.mastersid.massil_andrianina.mysmarthome.network.Esp32HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WifiCommandScreen()
                }
            }
        }
    }
}

@Composable
fun WifiCommandScreen() {
    var ip by remember { mutableStateOf("192.168.4.1") }
    var status by remember { mutableStateOf("Prêt") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun send(cmd: String) {
        scope.launch {
            loading = true
            status = "Envoi: $cmd ..."
            val res = withContext(Dispatchers.IO) {
                Esp32HttpClient.sendCommand(ip, cmd)
            }
            status = res.fold(
                onSuccess = { "Réponse: $it" },
                onFailure = { "Erreur: ${it.message ?: it.javaClass.simpleName}" }
            )
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Test Wi-Fi ESP32 (Hotspot)", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP ESP32") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { send("on") }, enabled = !loading) { Text("ON") }
            Button(onClick = { send("off") }, enabled = !loading) { Text("OFF") }
        }

        if (loading) CircularProgressIndicator()

        Text(status)
        Text("URL: http://$ip/cmd?value=on|off", style = MaterialTheme.typography.bodySmall)
    }
}