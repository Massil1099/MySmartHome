package fr.mastersid.massil_andrianina.mysmarthome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

data class UiOption(
    val rawLabel: String,
    val displayName: String
)

data class RoomUiOption(
    val rawLabel: String,
    val displayName: String,
    val objects: List<UiOption>
)

private val LED_OPTIONS = listOf(
    UiOption("one", "led1"),
    UiOption("two", "led2"),
    UiOption("three", "led3")
)

private val ACTION_OPTIONS = listOf(
    UiOption("on", "ON"),
    UiOption("off", "OFF")
)

private val ROOM_OPTIONS = listOf(
    RoomUiOption("marvin", "Salon", LED_OPTIONS),
    RoomUiOption("house", "Cuisine", LED_OPTIONS),
    RoomUiOption("bed", "Chambre", LED_OPTIONS)
)

class MainActivity : ComponentActivity() {

    private var micPermissionGranted by mutableStateOf(false)

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micPermissionGranted = checkMicPermission()

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

                var ip by remember { mutableStateOf("192.168.4.1") }
                var status by remember { mutableStateOf("Prêt") }

                val state = remember { VoiceCommandState() }
                val hasMicPermission = micPermissionGranted

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

                fun sendCommandToEsp32(
                    roomLabel: String,
                    objectLabel: String,
                    actionLabel: String
                ) {
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

                            val url =
                                "http://$ip/cmd?room=$encodedRoom&object=$encodedObject&action=$encodedAction"

                            status = "Envoi..."

                            val response = withContext(Dispatchers.IO) {
                                val conn = URL(url).openConnection() as HttpURLConnection
                                conn.connectTimeout = 4000
                                conn.readTimeout = 4000
                                conn.requestMethod = "GET"

                                val stream = try {
                                    conn.inputStream
                                } catch (_: Exception) {
                                    conn.errorStream
                                }

                                val body = stream?.bufferedReader()?.use { it.readText() }
                                    ?: "Aucune réponse"

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
                            modifier = Modifier.weight(1f)
                        ) {
                            composable("room") {
                                RoomSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        state.roomLabel = detectorRoom.predictLabel(input)
                                        state.objectLabel = ""
                                        state.actionLabel = ""
                                        status =
                                            "Pièce reconnue: ${displayRoomLabel(state.roomLabel)}"
                                    },
                                    onNext = {
                                        navController.navigate("object")
                                    }
                                )
                            }

                            composable("object") {
                                ObjectSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        state.objectLabel = detectorObject.predictLabel(input)
                                        state.actionLabel = ""
                                        status =
                                            "Objet reconnu: ${displayObjectLabel(state.objectLabel)}"
                                    },
                                    onNext = {
                                        navController.navigate("action")
                                    }
                                )
                            }

                            composable("action") {
                                ActionSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        state.actionLabel = detectorAction.predictLabel(input)
                                        status =
                                            "Action reconnue: ${displayActionLabel(state.actionLabel)}"
                                    },
                                    onSend = {
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
fun RoomSelectionScreen(
    state: VoiceCommandState,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: suspend () -> Unit,
    onNext: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isListening by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Choisissez une pièce",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            RecognizedWordCard(
                title = "Pièce reconnue",
                value = if (state.roomLabel.isBlank()) "Aucune" else displayRoomLabel(state.roomLabel),
                isSupported = mapRoomLabel(state.roomLabel) != null || state.roomLabel.isBlank()
            )

            ROOM_OPTIONS.forEach { room ->
                RoomCard(
                    title = room.displayName,
                    objects = room.objects.map { it.displayName },
                    selected = state.roomLabel == room.rawLabel,
                    onClick = {
                        state.roomLabel = room.rawLabel
                        state.objectLabel = ""
                        state.actionLabel = ""
                    }
                )
            }
        }

        BottomButtonsRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
            nextLabel = "Suivant",
            nextEnabled = mapRoomLabel(state.roomLabel) != null,
            onAskPermission = onAskPermission,
            onRecognize = {
                scope.launch {
                    isListening = true
                    try {
                        onRecognize()
                    } finally {
                        isListening = false
                    }
                }
            },
            onNext = onNext
        )
    }
}

@Composable
fun ObjectSelectionScreen(
    state: VoiceCommandState,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: suspend () -> Unit,
    onNext: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isListening by remember { mutableStateOf(false) }

    val selectedRoom = ROOM_OPTIONS.find { it.rawLabel == state.roomLabel }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = selectedRoom?.displayName ?: "Pièce non sélectionnée",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Choisissez un objet",
                style = MaterialTheme.typography.titleMedium
            )

            RecognizedWordCard(
                title = "Objet reconnu",
                value = if (state.objectLabel.isBlank()) "Aucun" else displayObjectLabel(state.objectLabel),
                isSupported = mapObjectLabel(state.objectLabel) != null || state.objectLabel.isBlank()
            )

            (selectedRoom?.objects ?: LED_OPTIONS).forEach { obj ->
                SelectableCard(
                    title = obj.displayName,
                    subtitle = "Objet disponible dans ${selectedRoom?.displayName ?: "la pièce"}",
                    selected = state.objectLabel == obj.rawLabel,
                    onClick = {
                        state.objectLabel = obj.rawLabel
                        state.actionLabel = ""
                    }
                )
            }
        }

        BottomButtonsRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
            nextLabel = "Suivant",
            nextEnabled = mapObjectLabel(state.objectLabel) != null,
            onAskPermission = onAskPermission,
            onRecognize = {
                scope.launch {
                    isListening = true
                    try {
                        onRecognize()
                    } finally {
                        isListening = false
                    }
                }
            },
            onNext = onNext
        )
    }
}

@Composable
fun ActionSelectionScreen(
    state: VoiceCommandState,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: suspend () -> Unit,
    onSend: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isListening by remember { mutableStateOf(false) }

    val roomTitle = displayRoomLabel(state.roomLabel)
    val objectTitle = displayObjectLabel(state.objectLabel)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = roomTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Objet : $objectTitle",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Choisissez une action",
                style = MaterialTheme.typography.titleMedium
            )

            RecognizedWordCard(
                title = "Action reconnue",
                value = if (state.actionLabel.isBlank()) "Aucune" else displayActionLabel(state.actionLabel),
                isSupported = mapActionLabel(state.actionLabel) != null || state.actionLabel.isBlank()
            )

            ACTION_OPTIONS.forEach { action ->
                SelectableCard(
                    title = action.displayName,
                    subtitle = "Action disponible",
                    selected = state.actionLabel == action.rawLabel,
                    onClick = {
                        state.actionLabel = action.rawLabel
                    }
                )
            }
        }

        BottomButtonsRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
            nextLabel = "Envoyer",
            nextEnabled = mapActionLabel(state.actionLabel) != null,
            onAskPermission = onAskPermission,
            onRecognize = {
                scope.launch {
                    isListening = true
                    try {
                        onRecognize()
                    } finally {
                        isListening = false
                    }
                }
            },
            onNext = onSend
        )
    }
}

@Composable
fun BottomButtonsRow(
    hasMicPermission: Boolean,
    isListening: Boolean,
    nextLabel: String,
    nextEnabled: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                if (hasMicPermission) {
                    onRecognize()
                } else {
                    onAskPermission()
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                when {
                    !hasMicPermission -> "Autoriser micro"
                    isListening -> "Écoute..."
                    else -> "Parler"
                }
            )
        }

        Button(
            onClick = onNext,
            enabled = nextEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(nextLabel)
        }
    }
}

@Composable
fun RecognizedWordCard(
    title: String,
    value: String,
    isSupported: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSupported) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RoomCard(
    title: String,
    objects: List<String>,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Objets : ${objects.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SelectableCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
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
    "one" -> "led1"
    "two" -> "led2"
    "three" -> "led3"
    else -> null
}

fun mapActionLabel(label: String): String? = when (label) {
    "on" -> "on"
    "off" -> "off"
    else -> null
}

fun displayRoomLabel(label: String): String = when (label) {
    "bed" -> "Chambre"
    "house" -> "Cuisine"
    "marvin" -> "Salon"
    "" -> "Aucune"
    else -> "$label (non supporté)"
}

fun displayObjectLabel(label: String): String = when (label) {
    "one", "led1" -> "led1"
    "two", "led2" -> "led2"
    "three", "led3" -> "led3"
    "" -> "Aucun"
    else -> "$label (non supporté)"
}

fun displayActionLabel(label: String): String = when (label) {
    "on" -> "ON"
    "off" -> "OFF"
    "" -> "Aucune"
    else -> "$label (non supporté)"
}