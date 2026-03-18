package fr.mastersid.massil_andrianina.mysmarthome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.mastersid.massil_andrianina.mysmarthome.ui.theme.MySmartHomeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MySmartHomeTheme {
                val navController = rememberNavController()
                val state = remember { VoiceCommandState() }

                NavHost(navController = navController, startDestination = "room") {
                    composable("room") {
                        VoiceStepScreen(
                            title = "Prononcez la pièce",
                            label = state.roomLabel,
                            onRecognize = { state.roomLabel = it },
                            onNext = { navController.navigate("object") }
                        )
                    }
                    composable("object") {
                        VoiceStepScreen(
                            title = "Prononcez l'objet",
                            label = state.objectLabel,
                            onRecognize = { state.objectLabel = it },
                            onNext = { navController.navigate("action") }
                        )
                    }
                    composable("action") {
                        VoiceStepScreen(
                            title = "Prononcez l'action",
                            label = state.actionLabel,
                            onRecognize = { state.actionLabel = it },
                            onNext = {
                                // Tu peux ici déclencher l'envoi à l'ESP32
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceStepScreen(
    title: String,
    label: String,
    onRecognize: (String) -> Unit,
    onNext: () -> Unit
) {
    var isListening by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = {
                isListening = true
                // À connecter avec reconnaissance audio réelle
                // Ici on simule un mot reconnu :
                onRecognize("on")
                isListening = false
            },
            enabled = !isListening
        ) {
            Text(if (isListening) "Écoute..." else "Parler")
        }

        Text("Reconnu : $label")

        Button(onClick = onNext, enabled = label.isNotBlank()) {
            Text("Suivant")
        }
    }
}

class VoiceCommandState {
    var roomLabel by mutableStateOf("")
    var objectLabel by mutableStateOf("")
    var actionLabel by mutableStateOf("")
}
