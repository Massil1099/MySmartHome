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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.mastersid.massil_andrianina.mysmarthome.data.model.TFLiteKeywordDetector
import fr.mastersid.massil_andrianina.mysmarthome.network.Esp32HttpClient
import fr.mastersid.massil_andrianina.mysmarthome.ui.theme.MySmartHomeTheme
import fr.mastersid.massil_andrianina.mysmarthome.voice.AudioRecorder
import fr.mastersid.massil_andrianina.mysmarthome.voice.StftLogFeatureExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Seuil minimal de confiance pour accepter un mot reconnu
private const val MIN_CONFIDENCE = 0.6f

// Option affichée dans l'interface
data class UiOption(
    val rawLabel: String,
    val displayName: String,
    val mappedName: String
)

// Structure d'une pièce avec les objets disponibles dedans
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
    RoomUiOption("tree", "Tree", "chambre", LED_OPTIONS)
)

class MainActivity : ComponentActivity() {

    // État de permission micro
    private var micPermissionGranted by mutableStateOf(false)

    // Demande de permission micro
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vérification initiale de la permission
        micPermissionGranted = checkMicPermission()

        // Outils audio
        val recorder = AudioRecorder(16000)
        val extractor = StftLogFeatureExtractor()

        // Détecteur pour les pièces
        val detectorRoom = TFLiteKeywordDetector(
            context = this,
            modelName = "rooms_model_pruned_int8.tflite",
            labels = Labels.ROOMS
        )

        // Détecteur pour les objets
        val detectorObject = TFLiteKeywordDetector(
            context = this,
            modelName = "objects_model_pruned_int8.tflite",
            labels = Labels.OBJECTS
        )

        // Détecteur pour les actions
        val detectorAction = TFLiteKeywordDetector(
            context = this,
            modelName = "actions_model_pruned_int8.tflite",
            labels = Labels.ACTIONS
        )

        setContent {
            MySmartHomeTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                // IP de l'ESP32
                var ip by remember { mutableStateOf("192.168.4.1") }

                // Message affiché dans l'UI
                var status by remember { mutableStateOf("Prêt") }

                // État de connexion à l'ESP32
                var isEspConnected by remember { mutableStateOf(false) }

                // État de vérification de connexion
                var isCheckingConnection by remember { mutableStateOf(false) }

                // État global de la commande vocale
                val state = remember { VoiceCommandState() }

                // Permission micro actuelle
                val hasMicPermission = micPermissionGranted

                // Lance la demande de permission micro
                fun askMicPermission() {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                // Formate une confiance sur 2 décimales
                fun confText(value: Float): String {
                    return String.format(Locale.US, "%.2f", value)
                }

                // Enregistre 1 seconde puis extrait les features audio
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

                // Vérifie automatiquement si l'ESP32 est joignable
                fun checkEsp32Connection() {
                    scope.launch {
                        isCheckingConnection = true
                        try {
                            val result = withContext(Dispatchers.IO) {
                                Esp32HttpClient.checkConnection(ip)
                            }

                            if (result.isSuccess) {
                                isEspConnected = true
                                status = result.getOrNull() ?: "ESP32 connecté"
                            } else {
                                isEspConnected = false
                                status = "ESP32 non connecté"
                            }
                        } finally {
                            isCheckingConnection = false
                        }
                    }
                }

                // Envoie la commande finale à l'ESP32
                fun sendCommandToEsp32(
                    roomLabel: String,
                    objectLabel: String,
                    actionLabel: String
                ) {
                    scope.launch {
                        val room = mapRoomLabel(roomLabel)
                        val obj = mapObjectLabel(objectLabel)
                        val action = mapActionLabel(actionLabel)

                        // Vérifie que tout est bien mappé
                        if (room == null || obj == null || action == null) {
                            status = "Commande incomplète ou invalide"
                            return@launch
                        }

                        status = "Envoi..."

                        val result = withContext(Dispatchers.IO) {
                            Esp32HttpClient.sendCommand(
                                ip = ip,
                                room = room,
                                obj = obj,
                                action = action
                            )
                        }

                        if (result.isSuccess) {
                            status = "Réponse ESP32 : ${result.getOrNull()}"
                            isEspConnected = true
                        } else {
                            status = "Erreur ESP32 : ${result.exceptionOrNull()?.message}"
                            isEspConnected = false
                        }
                    }
                }

                // Vérifie automatiquement la connexion lorsque l'IP change
                LaunchedEffect(ip) {
                    if (ip.isNotBlank()) {
                        checkEsp32Connection()
                    } else {
                        isEspConnected = false
                        status = "IP vide"
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
                        // Champ d'édition de l'IP
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            label = { Text("IP ESP32") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Statut global
                        Text("Statut : $status")

                        // Message de connexion
                        if (!isEspConnected) {
                            Text(
                                text = if (isCheckingConnection) {
                                    "Vérification de la connexion à l'ESP32..."
                                } else {
                                    "ESP32 non connecté"
                                },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                text = "ESP32 connecté",
                                color = Color(0xFF2E7D32),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Navigation entre les 3 écrans
                        NavHost(
                            navController = navController,
                            startDestination = "room",
                            modifier = Modifier.weight(1f)
                        ) {
                            // Écran de choix de la pièce
                            composable("room") {
                                RoomSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    isEspConnected = isEspConnected,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        val result = detectorRoom.predict(input, MIN_CONFIDENCE)

                                        state.roomLabel = result.label ?: ""
                                        state.objectLabel = ""
                                        state.actionLabel = ""

                                        status = when {
                                            !result.recognized ->
                                                "Pièce non reconnue (conf=${confText(result.confidence)})"

                                            mapRoomLabel(state.roomLabel) == null ->
                                                "Pièce reconnue mais non supportée : ${displayRoomLabel(state.roomLabel)} (conf=${confText(result.confidence)})"

                                            else ->
                                                "Pièce reconnue : ${displayRoomLabel(state.roomLabel)} (conf=${confText(result.confidence)})"
                                        }

                                        // Navigation auto vers l'écran objet si la pièce est valide
                                        if (result.recognized && mapRoomLabel(state.roomLabel) != null) {
                                            navController.navigate("object")
                                        }
                                    }
                                )
                            }

                            // Écran de choix de l'objet
                            composable("object") {
                                ObjectSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    isEspConnected = isEspConnected,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        val result = detectorObject.predict(input, MIN_CONFIDENCE)

                                        state.objectLabel = result.label ?: ""
                                        state.actionLabel = ""

                                        status = when {
                                            !result.recognized ->
                                                "Objet non reconnu (conf=${confText(result.confidence)})"

                                            mapObjectLabel(state.objectLabel) == null ->
                                                "Objet reconnu mais non supporté : ${displayObjectLabel(state.objectLabel)} (conf=${confText(result.confidence)})"

                                            else ->
                                                "Objet reconnu : ${displayObjectLabel(state.objectLabel)} (conf=${confText(result.confidence)})"
                                        }

                                        // Navigation auto vers l'écran action si l'objet est valide
                                        if (result.recognized && mapObjectLabel(state.objectLabel) != null) {
                                            navController.navigate("action")
                                        }
                                    }
                                )
                            }

                            // Écran de choix de l'action
                            composable("action") {
                                ActionSelectionScreen(
                                    state = state,
                                    hasMicPermission = hasMicPermission,
                                    isEspConnected = isEspConnected,
                                    onAskPermission = { askMicPermission() },
                                    onRecognize = {
                                        val input = recordAndExtractInput()
                                        val result = detectorAction.predict(input, MIN_CONFIDENCE)

                                        state.actionLabel = result.label ?: ""

                                        status = when {
                                            !result.recognized ->
                                                "Action non reconnue (conf=${confText(result.confidence)})"

                                            mapActionLabel(state.actionLabel) == null ->
                                                "Action reconnue mais non supportée : ${displayActionLabel(state.actionLabel)} (conf=${confText(result.confidence)})"

                                            else ->
                                                "Action reconnue : ${displayActionLabel(state.actionLabel)} (conf=${confText(result.confidence)})"
                                        }

                                        // Envoi auto à l'ESP32 si l'action est valide
                                        if (result.recognized && mapActionLabel(state.actionLabel) != null) {
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

    // Vérifie si la permission micro est accordée
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
    isEspConnected: Boolean,
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
            // Titre de l'écran
            Text(
                text = "Choisissez une pièce",
                style = MaterialTheme.typography.headlineSmall
            )

            // Carte affichant la pièce reconnue
            RecognizedWordCard(
                title = "Pièce reconnue",
                value = if (state.roomLabel.isBlank()) "Aucune"
                else displayRoomLabel(state.roomLabel),
                isSupported = mapRoomLabel(state.roomLabel) != null || state.roomLabel.isBlank()
            )

            // Cartes de sélection manuelle des pièces
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

        // Bouton de lancement de l'écoute
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isEspConnected = isEspConnected,
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
    isEspConnected: Boolean,
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
            // Rappel de la pièce choisie
            Text(
                text = selectedRoom?.let { "${it.displayName} (${it.mappedName})" }
                    ?: "Pièce non sélectionnée",
                style = MaterialTheme.typography.headlineSmall
            )

            // Titre de l'écran
            Text(
                text = "Choisissez un objet",
                style = MaterialTheme.typography.titleMedium
            )

            // Carte affichant l'objet reconnu
            RecognizedWordCard(
                title = "Objet reconnu",
                value = if (state.objectLabel.isBlank()) "Aucun"
                else displayObjectLabel(state.objectLabel),
                isSupported = mapObjectLabel(state.objectLabel) != null || state.objectLabel.isBlank()
            )

            // Cartes de sélection manuelle des objets
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

        // Bouton de lancement de l'écoute
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isEspConnected = isEspConnected,
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
    isEspConnected: Boolean,
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
            // Rappel de la pièce choisie
            Text(
                text = displayRoomLabel(state.roomLabel),
                style = MaterialTheme.typography.headlineSmall
            )

            // Rappel de l'objet choisi
            Text(
                text = displayObjectLabel(state.objectLabel),
                style = MaterialTheme.typography.titleMedium
            )

            // Titre de l'écran
            Text(
                text = "Choisissez une action",
                style = MaterialTheme.typography.titleMedium
            )

            // Carte affichant l'action reconnue
            RecognizedWordCard(
                title = "Action reconnue",
                value = if (state.actionLabel.isBlank()) "Aucune"
                else displayActionLabel(state.actionLabel),
                isSupported = mapActionLabel(state.actionLabel) != null || state.actionLabel.isBlank()
            )

            // Cartes de sélection manuelle des actions
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

        // Bouton de lancement de l'écoute
        ListenButtonRow(
            hasMicPermission = hasMicPermission,
            isEspConnected = isEspConnected,
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
    isEspConnected: Boolean,
    isListening: Boolean,
    onAskPermission: () -> Unit,
    onRecognize: () -> Unit
) {
    // Le bouton est désactivé si l'ESP32 n'est pas connecté
    // ou si une écoute est déjà en cours
    val enabled = isEspConnected && !isListening

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Button(
            onClick = {
                if (hasMicPermission) {
                    onRecognize()
                } else {
                    onAskPermission()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(
                when {
                    !isEspConnected -> "ESP32 not connected"
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
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
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
            Text(
                text = "$title ($mappedName)",
                style = MaterialTheme.typography.titleLarge
            )

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
            Text(
                text = "$title ($mappedName)",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// État global de la commande en cours
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

// Convertit un label de pièce vers la valeur attendue par l'ESP32
fun mapRoomLabel(label: String): String? = when (label) {
    "tree" -> "chambre"
    "house" -> "cuisine"
    "marvin" -> "salon"
    else -> null
}

// Convertit un label d'objet vers la valeur attendue par l'ESP32
fun mapObjectLabel(label: String): String? = when (label) {
    "one" -> "led1"
    "two" -> "led2"
    "three" -> "led3"
    else -> null
}

// Convertit un label d'action vers la valeur attendue par l'ESP32
fun mapActionLabel(label: String): String? = when (label) {
    "on" -> "on"
    "off" -> "off"
    else -> null
}

// Texte affiché pour les pièces
fun displayRoomLabel(label: String): String = when (label) {
    "tree" -> "Tree (chambre)"
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