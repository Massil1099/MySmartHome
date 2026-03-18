package fr.mastersid.massil_andrianina.mysmarthome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.mastersid.massil_andrianina.mysmarthome.data.model.TFLiteKeywordDetector
import fr.mastersid.massil_andrianina.mysmarthome.ui.theme.MySmartHomeTheme
import fr.mastersid.massil_andrianina.mysmarthome.voice.AudioRecorder
import fr.mastersid.massil_andrianina.mysmarthome.voice.StftLogFeatureExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recorder = AudioRecorder(16000)
        val extractor = StftLogFeatureExtractor()

        val detectorRoom = TFLiteKeywordDetector(
            context = this,
            modelName = "rooms_model_pruned_int8.tflite",
            labels = Labels.ROOMS
        )

        val detectorObject = TFLiteKeywordDetector(
            context = this,
            modelName = "objects_model_pruned_int8.tflite",
            labels = Labels.OBJECTS
        )

        val detectorAction = TFLiteKeywordDetector(
            context = this,
            modelName = "actions_model_pruned_int8.tflite",
            labels = Labels.ACTIONS
        )

        setContent {
            MySmartHomeTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                var hasMicPermission by remember { mutableStateOf(checkMicPermission()) }
                var ip by remember { mutableStateOf("192.168.4.1") }
                var status by remember { mutableStateOf("Prêt") }

                val state = remember { VoiceCommandState() }

                LaunchedEffect(Unit) {
                    hasMicPermission = checkMicPermission()
                }

                fun askMicPermission() {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                suspend fun recordAndExtractInput(): Array<Array<Array<FloatArray>>> {
                    val pcm = withContext(Dispatchers.Default) {
                        recorder.recordOneSecondPcm16()
                    }
                    val floats = withContext(Dispatchers.Default) {
                        recorder.pcm16ToFloat(pcm)
                    }
                    return withContext(Dispatchers.Default) {
                        extractor.extract(floats)
                    }
                }

                fun sendCommandToEsp32(roomLabel: String, objectLabel: String, actionLabel: String) {
                    scope.launch {
                        try {
                            val room = mapRoomLabel(roomLabel)
                            val obj = mapObjectLabel(objectLabel)
                            val action = mapActionLabel(actionLabel)

                            if (room == null || obj == null || action == null) {
                                status = "Commande incomplète ou invalide"
                                return@launch
                            }

                            val encodedRoom = URLEncoder.encode(room, "UTF-8")
                            val encodedObject = URLEncoder.encode(obj, "UTF-8")
                            val encodedAction = URLEncoder.encode(action, "UTF-8")

                            val url = "http://$ip/cmd?room=$encodedRoom&object=$encodedObject&action=$encodedAction"

                            status = "Envoi..."

                            val response = withContext(Dispatchers.IO) {
                                val conn = URL(url).openConnection() as HttpURLConnection
                                conn.connectTimeout = 4000
                                conn.readTimeout = 4000
                                conn.requestMethod = "GET"
                                val body = conn.inputStream.bufferedReader().use { it.readText() }
                                conn.disconnect()
                                body
                            }

                            status = "Réponse ESP32: $response"
                        } catch (e: Exception) {
                            status = "Erreur ESP32: ${e.message}"
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            label = { Text("IP ESP32") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Statut: $status")

                        NavHost(
                            navController = navController,
                            startDestination = "room",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("room") {
                                VoiceStepScreen(
                                    title = "Prononcez la pièce",
                                    label = state.roomLabel,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        scope.launch {
                                            try {
                                                if (!hasMicPermission) {
                                                    askMicPermission()
                                                    return@launch
                                                }
                                                status = "Écoute pièce..."
                                                val input = recordAndExtractInput()
                                                state.roomLabel = detectorRoom.predictLabel(input)
                                                status = "Pièce reconnue: ${state.roomLabel}"
                                            } catch (e: Exception) {
                                                status = "Erreur pièce: ${e.message}"
                                            }
                                        }
                                    },
                                    onNext = { navController.navigate("object") }
                                )
                            }

                            composable("object") {
                                VoiceStepScreen(
                                    title = "Prononcez l'objet",
                                    label = state.objectLabel,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        scope.launch {
                                            try {
                                                if (!hasMicPermission) {
                                                    askMicPermission()
                                                    return@launch
                                                }
                                                status = "Écoute objet..."
                                                val input = recordAndExtractInput()
                                                state.objectLabel = detectorObject.predictLabel(input)
                                                status = "Objet reconnu: ${state.objectLabel}"
                                            } catch (e: Exception) {
                                                status = "Erreur objet: ${e.message}"
                                            }
                                        }
                                    },
                                    onNext = { navController.navigate("action") }
                                )
                            }

                            composable("action") {
                                VoiceStepScreen(
                                    title = "Prononcez l'action",
                                    label = state.actionLabel,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        scope.launch {
                                            try {
                                                if (!hasMicPermission) {
                                                    askMicPermission()
                                                    return@launch
                                                }
                                                status = "Écoute action..."
                                                val input = recordAndExtractInput()
                                                state.actionLabel = detectorAction.predictLabel(input)
                                                status = "Action reconnue: ${state.actionLabel}"
                                            } catch (e: Exception) {
                                                status = "Erreur action: ${e.message}"
                                            }
                                        }
                                    },
                                    onNext = {
                                        sendCommandToEsp32(
                                            roomLabel = state.roomLabel,
                                            objectLabel = state.objectLabel,
                                            actionLabel = state.actionLabel
                                        )
                                    }
                                )
                            }
                        }
                    }
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


@Composable
fun VoiceStepScreen(
    title: String,
    label: String,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: () -> Unit,
    onNext: () -> Unit
) {
    var isListening by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)

        if (!hasMicPermission) {
            Button(onClick = onAskPermission) {
                Text("Autoriser le micro")
            }
        } else {
            Button(
                onClick = {
                    isListening = true
                    onRecognize()
                    isListening = false
                },
                enabled = !isListening
            ) {
                Text(if (isListening) "Écoute..." else "Parler 1s")
            }
        }

        Text("Reconnu : $label")

        Button(
            onClick = onNext,
            enabled = label.isNotBlank()
        ) {
            Text("Suivant")
        }
    }
}

class VoiceCommandState {
    var roomLabel by mutableStateOf("")
    var objectLabel by mutableStateOf("")
    var actionLabel by mutableStateOf("")
}

object Labels {
    val ROOMS = listOf("bed", "house", "tree", "marvin", "sheila")
    val OBJECTS = listOf("one", "two", "three", "four", "five")
    val ACTIONS = listOf("on", "off", "up", "down", "stop")
}




fun mapRoomLabel(label: String): String? = when (label) {
    "bed" -> "chambre"
    "house" -> "cuisine"
    "marvin" -> "salon"
    else -> null
}

fun mapObjectLabel(label: String): String? = when (label) {
    "one" -> "led"
    "two" -> "led"
    "three" -> "led"
    else -> null
}

fun mapActionLabel(label: String): String? = when (label) {
    "on" -> "on"
    "off" -> "off"
    else -> null
}