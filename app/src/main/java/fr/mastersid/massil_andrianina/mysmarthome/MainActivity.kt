package fr.mastersid.massil_andrianina.mysmarthome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.mastersid.massil_andrianina.mysmarthome.data.model.TFLiteKeywordDetector
import fr.mastersid.massil_andrianina.mysmarthome.network.Esp32HttpClient
import fr.mastersid.massil_andrianina.mysmarthome.voice.AudioRecorder
import fr.mastersid.massil_andrianina.mysmarthome.voice.StftLogFeatureExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Géré via Compose */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val detector = TFLiteKeywordDetector(this)
        val recorder = AudioRecorder(16000)
        val extractor = StftLogFeatureExtractor()

        setContent {
            val scope = rememberCoroutineScope()

            var hasMic by remember { mutableStateOf(checkMicPermission()) }
            var ip by remember { mutableStateOf("192.168.4.1") }
            var isListening by remember { mutableStateOf(false) }
            var prediction by remember { mutableStateOf("—") }
            var status by remember { mutableStateOf("Prêt") }

            LaunchedEffect(Unit) {
                hasMic = checkMicPermission()
            }

            fun askMic() {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            fun sendToESP32(cmd: String) {
                scope.launch {
                    status = "Envoi: $cmd"
                    val res = withContext(Dispatchers.IO) {
                        Esp32HttpClient.sendCommand(ip, cmd)
                    }
                    status = res.fold(
                        onSuccess = { "Réponse ESP32: $it" },
                        onFailure = { "Erreur ESP32: ${it.message}" }
                    )
                }
            }

            fun listenAndPredict() {
                if (!hasMic) {
                    askMic()
                    return
                }

                scope.launch {
                    isListening = true
                    prediction = "..."
                    status = "Écoute 1s"

                    try {
                        val pcm = withContext(Dispatchers.Default) {
                            recorder.recordOneSecondPcm16()
                        }
                        val floats = recorder.pcm16ToFloat(pcm)
                        val features = extractor.extract(floats)
                        val label = detector.predictLabel(features)
                        prediction = label

                        if (label == "on" || label == "off") {
                            sendToESP32(label)
                        } else {
                            status = "Commande non reconnue"
                        }

                    } catch (e: Exception) {
                        prediction = "Erreur"
                        status = e.message ?: "Erreur inconnue"
                    } finally {
                        isListening = false
                    }
                }
            }

            Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Commande vocale ESP32", style = MaterialTheme.typography.headlineSmall)

                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("IP ESP32") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { listenAndPredict() },
                        enabled = !isListening,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isListening) "Écoute..." else "Parler 1s")
                    }

                    Text("Prédiction : $prediction")
                    Text("Statut : $status")
                }
            }
        }
    }

    private fun checkMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}