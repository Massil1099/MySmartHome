package fr.mastersid.massil_andrianina.mysmarthome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.*
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

// Option affichée dans l'interface
data class UiOption(
    val rawLabel: String,
    val displayName: String,
    val mappedName: String
)

// Pièce + objets affichés
data class RoomUiOption(
    val rawLabel: String,
    val displayName: String,
    val mappedName: String,
    val objects: List<UiOption>
)

// Objets disponibles
private val LED_OPTIONS = listOf(
    UiOption("one", "One", "led1"),
    UiOption("two", "Two", "led2"),
    UiOption("three", "Three", "led3")
)

// Actions disponibles
private val ACTION_OPTIONS = listOf(
    UiOption("on", "On", "allumer"),
    UiOption("off", "Off", "éteindre")
)

// Pièces disponibles
private val ROOM_OPTIONS = listOf(
    RoomUiOption("marvin", "Marvin", "salon", LED_OPTIONS),
    RoomUiOption("house", "House", "cuisine", LED_OPTIONS),
    RoomUiOption("bed", "Bed", "chambre", LED_OPTIONS)
)

class MainActivity : ComponentActivity() {

    // État permission micro
    private var micPermissionGranted by mutableStateOf(false)

    // Demande permission micro
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vérifie la permission au lancement
        micPermissionGranted = checkMicPermission()

        // Audio + extraction
        val recorder = AudioRecorder(16000)
        val extractor = StftLogFeatureExtractor()

        // Modèle pièce
        val detectorRoom = TFLiteKeywordDetector(
            context = this,
            modelName = "rooms_model_pruned_int8.tflite",
            labels = Labels.ROOMS
        )

        // Modèle objet
        val detectorObject = TFLiteKeywordDetector(
            context = this,
            modelName = "objects_model_pruned_int8.tflite",
            labels = Labels.OBJECTS
        )

        // Modèle action
        val detectorAction = TFLiteKeywordDetector(
            context = this,
            modelName = "actions_model_pruned_int8.tflite",
            labels = Labels.ACTIONS
        )

        setContent {
            MySmartHomeTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                // IP et statut
                var ip by remember { mutableStateOf("192.168.4.1") }
                var status by remember { mutableStateOf("Prêt") }

                // État global de la commande
                val state = remember { VoiceCommandState() }
                val hasMicPermission = micPermissionGranted

                // Demande permission
                fun askMicPermission() {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                // Enregistre puis extrait les features
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

                // Envoi HTTP à l'ESP32
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

                            // Vérifie la commande
                            if (room == null || obj == null || action == null) {
                                status = "Commande incomplète ou invalide"
                                return@launch
                            }

                            // Encode l'URL
                            val encodedRoom = URLEncoder.encode(room, "UTF-8")
                            val encodedObject = URLEncoder.encode(obj, "UTF-8")
                            val encodedAction = URLEncoder.encode(action, "UTF-8")

                            val url =
                                "http://$ip/cmd?room=$encodedRoom&object=$encodedObject&action=$encodedAction"

                            status = "Envoi..."

                            // Requête GET
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
                        // Champ IP
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            label = { Text("IP ESP32") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Texte d'état
                        Text("Statut : $status")

                        // Navigation entre les 3 écrans
                        NavHost(
                            navController = navController,
                            startDestination = "room",
                            modifier = Modifier.weight(1f)
                        ) {
                            // Écran pièce
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
                                        status = "Pièce reconnue : ${displayRoomLabel(state.roomLabel)}"

                                        // Passe automatiquement à l'écran suivant
                                        if (mapRoomLabel(state.roomLabel) != null) {
                                            navController.navigate("object")
                                        }
                                    }
                                )
                            }

                            // Écran objet
                            composable("object") {
                                ObjectSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        state.objectLabel = detectorObject.predictLabel(input)
                                        state.actionLabel = ""
                                        status = "Objet reconnu : ${displayObjectLabel(state.objectLabel)}"

                                        // Passe automatiquement à l'écran suivant
                                        if (mapObjectLabel(state.objectLabel) != null) {
                                            navController.navigate("action")
                                        }
                                    }
                                )
                            }

                            // Écran action
                            composable("action") {
                                ActionSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        state.actionLabel = detectorAction.predictLabel(input)
                                        status = "Action reconnue : ${displayActionLabel(state.actionLabel)}"

                                        // Envoie automatiquement la commande
                                        if (mapActionLabel(state.actionLabel) != null) {
                                            sendCommandToEsp32(
                                                roomLabel = state.roomLabel,
                                                objectLabel = state.objectLabel,
                                                actionLabel = state.actionLabel
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Vérifie permission micro
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
    onRecognize: suspend () -> Unit
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
            // Titre écran 1
            Text(
                text = "Choisissez une pièce",
                style = MaterialTheme.typography.headlineSmall
            )

            // Mot reconnu
            RecognizedWordCard(
                title = "Pièce reconnue",
                value = if (state.roomLabel.isBlank()) "Aucune"
                else displayRoomLabel(state.roomLabel),
                isSupported = mapRoomLabel(state.roomLabel) != null || state.roomLabel.isBlank()
            )

            // Liste des pièces
            ROOM_OPTIONS.forEach { room ->
                RoomCard(
                    title = room.displayName,
                    mappedName = room.mappedName,
                    objects = room.objects,
                    selected = state.roomLabel == room.rawLabel,
                    onClick = {
                        state.roomLabel = room.rawLabel
                        state.objectLabel = ""
                        state.actionLabel = ""
                    }
                )
            }
        }

        // Bouton parler
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
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
            }
        )
    }
}

@Composable
fun ObjectSelectionScreen(
    state: VoiceCommandState,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: suspend () -> Unit
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
            // Titre avec pièce choisie
            Text(
                text = selectedRoom?.let { "${it.displayName} (${it.mappedName})" }
                    ?: "Pièce non sélectionnée",
                style = MaterialTheme.typography.headlineSmall
            )

            // Sous-titre
            Text(
                text = "Choisissez un objet",
                style = MaterialTheme.typography.titleMedium
            )

            // Objet reconnu
            RecognizedWordCard(
                title = "Objet reconnu",
                value = if (state.objectLabel.isBlank()) "Aucun"
                else displayObjectLabel(state.objectLabel),
                isSupported = mapObjectLabel(state.objectLabel) != null || state.objectLabel.isBlank()
            )

            // Liste des objets
            (selectedRoom?.objects ?: LED_OPTIONS).forEach { obj ->
                SelectableCard(
                    title = obj.displayName,
                    mappedName = obj.mappedName,
                    subtitle = "Objet disponible",
                    selected = state.objectLabel == obj.rawLabel,
                    onClick = {
                        state.objectLabel = obj.rawLabel
                        state.actionLabel = ""
                    }
                )
            }
        }

        // Bouton parler
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
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
            }
        )
    }
}

@Composable
fun ActionSelectionScreen(
    state: VoiceCommandState,
    hasMicPermission: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: suspend () -> Unit
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
            // Rappel pièce choisie
            Text(
                text = displayRoomLabel(state.roomLabel),
                style = MaterialTheme.typography.headlineSmall
            )

            // Rappel objet choisi
            Text(
                text = displayObjectLabel(state.objectLabel),
                style = MaterialTheme.typography.titleMedium
            )

            // Titre action
            Text(
                text = "Choisissez une action",
                style = MaterialTheme.typography.titleMedium
            )

            // Action reconnue
            RecognizedWordCard(
                title = "Action reconnue",
                value = if (state.actionLabel.isBlank()) "Aucune"
                else displayActionLabel(state.actionLabel),
                isSupported = mapActionLabel(state.actionLabel) != null || state.actionLabel.isBlank()
            )

            // Liste des actions
            ACTION_OPTIONS.forEach { action ->
                SelectableCard(
                    title = action.displayName,
                    mappedName = action.mappedName,
                    subtitle = "Action disponible",
                    selected = state.actionLabel == action.rawLabel,
                    onClick = {
                        state.actionLabel = action.rawLabel
                    }
                )
            }
        }

        // Bouton parler
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isListening = isListening,
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
            }
        )
    }
}

@Composable
fun ListenButtonRow(
    hasMicPermission: Boolean,
    isListening: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        // Seul bouton restant
        Button(
            onClick = {
                if (hasMicPermission) onRecognize() else onAskPermission()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    !hasMicPermission -> "Autoriser micro"
                    isListening -> "Écoute..."
                    else -> "Parler"
                }
            )
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
            // Titre du mot reconnu
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            // Valeur reconnue
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun RoomCard(
    title: String,
    mappedName: String,
    objects: List<UiOption>,
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
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
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
            // Nom de la pièce
            Text(
                text = "$title ($mappedName)",
                style = MaterialTheme.typography.titleLarge
            )

            // Objets de la pièce
            Text(
                text = "Objets : " + objects.joinToString(", ") {
                    "${it.displayName} (${it.mappedName})"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SelectableCard(
    title: String,
    mappedName: String,
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
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
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
            // Nom affiché
            Text(
                text = "$title ($mappedName)",
                style = MaterialTheme.typography.titleLarge
            )
            // Petit texte descriptif
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// Stocke la commande courante
class VoiceCommandState {
    var roomLabel by mutableStateOf("")
    var objectLabel by mutableStateOf("")
    var actionLabel by mutableStateOf("")
}

// Labels bruts des modèles
object Labels {
    val ROOMS = listOf("bed", "house", "tree", "marvin", "sheila")
    val OBJECTS = listOf("one", "two", "three", "four", "five")
    val ACTIONS = listOf("on", "off", "up", "down", "stop")
}

// Convertit label pièce -> valeur ESP32
fun mapRoomLabel(label: String): String? = when (label) {
    "bed" -> "chambre"
    "house" -> "cuisine"
    "marvin" -> "salon"
    else -> null
}

// Convertit label objet -> valeur ESP32
fun mapObjectLabel(label: String): String? = when (label) {
    "one" -> "led1"
    "two" -> "led2"
    "three" -> "led3"
    else -> null
}

// Convertit label action -> valeur ESP32
fun mapActionLabel(label: String): String? = when (label) {
    "on" -> "on"
    "off" -> "off"
    else -> null
}

// Texte affiché pour les pièces
fun displayRoomLabel(label: String): String = when (label) {
    "bed" -> "Bed (chambre)"
    "house" -> "House (cuisine)"
    "marvin" -> "Marvin (salon)"
    "" -> "Aucune"
    else -> "$label (non supporté)"
}

// Texte affiché pour les objets
fun displayObjectLabel(label: String): String = when (label) {
    "one", "led1" -> "One (led1)"
    "two", "led2" -> "Two (led2)"
    "three", "led3" -> "Three (led3)"
    "" -> "Aucun"
    else -> "$label (non supporté)"
}

// Texte affiché pour les actions
fun displayActionLabel(label: String): String = when (label) {
    "on" -> "On (allumer)"
    "off" -> "Off (éteindre)"
    "" -> "Aucune"
    else -> "$label (non supporté)"
}