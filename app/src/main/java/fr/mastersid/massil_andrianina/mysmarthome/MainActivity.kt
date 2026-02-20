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
import fr.mastersid.massil_andrianina.mysmarthome.voice.StftLogFeatureExtractor
import fr.mastersid.massil_andrianina.mysmarthome.ui.theme.MySmartHomeTheme
import fr.mastersid.massil_andrianina.mysmarthome.voice.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* résultat géré via l’état Compose */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Instances “métier”
        val detector = TFLiteKeywordDetector(this)
        val recorder = AudioRecorder(sampleRate = 16000)
        val extractor = StftLogFeatureExtractor(
            frameLength = 255,
            frameStep = 128,
            numFrames = 124,
            numBins = 128
        )

        setContent {
            MySmartHomeTheme {
                val scope = rememberCoroutineScope()

                var hasMicPermission by remember { mutableStateOf(checkMicPermission()) }
                var isListening by remember { mutableStateOf(false) }
                var predictedIndex by remember { mutableIntStateOf(-1) }
                var predictedLabel by remember { mutableStateOf("—") }
                var error by remember { mutableStateOf<String?>(null) }

                // petit helper
                fun askPermission() {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                // à chaque recomposition, on recheck
                LaunchedEffect(Unit) {
                    hasMicPermission = checkMicPermission()
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AudioCommandScreen(
                            hasMicPermission = hasMicPermission,
                            isListening = isListening,
                            predictedIndex = predictedIndex,
                            predictedLabel = predictedLabel,
                            error = error,
                            onRequestPermission = {
                                askPermission()
                                // refresh après demande
                                hasMicPermission = checkMicPermission()
                            },
                            onRecordOnce = {
                                if (!hasMicPermission) {
                                    askPermission()
                                    hasMicPermission = checkMicPermission()
                                    return@AudioCommandScreen
                                }

                                scope.launch {
                                    isListening = true
                                    error = null
                                    predictedLabel = "Écoute…"
                                    predictedIndex = -1

                                    try {
                                        // 1) record 1s PCM16
                                        val pcm16 = withContext(Dispatchers.Default) {
                                            recorder.recordOneSecondPcm16()
                                        }
                                        // 2) convert float
                                        val pcmF = withContext(Dispatchers.Default) {
                                            recorder.pcm16ToFloat(pcm16)
                                        }
                                        // 3) features STFT(log)
                                        val input = withContext(Dispatchers.Default) {
                                            extractor.extract(pcmF) // [1][124][128][1]
                                        }
                                        // 4) inference
                                        val idx = withContext(Dispatchers.Default) {
                                            detector.predict(input)
                                        }

                                        predictedIndex = idx
                                        predictedLabel = Labels.LIST.getOrElse(idx) { "unknown($idx)" }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Erreur inconnue"
                                        predictedLabel = "—"
                                        predictedIndex = -1
                                    } finally {
                                        isListening = false
                                        // recheck permission au cas où
                                        hasMicPermission = checkMicPermission()
                                    }
                                }
                            }
                        )
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
fun AudioCommandScreen(
    hasMicPermission: Boolean,
    isListening: Boolean,
    predictedIndex: Int,
    predictedLabel: String,
    error: String?,
    onRequestPermission: () -> Unit,
    onRecordOnce: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Commande vocale (1 seconde)",
            style = MaterialTheme.typography.headlineSmall
        )

        if (!hasMicPermission) {
            Text(
                text = "Permission micro requise.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRequestPermission) {
                Text("Autoriser le micro")
            }
        } else {
            Button(
                onClick = onRecordOnce,
                enabled = !isListening,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isListening) "Écoute..." else "Parler (1s)")
            }
        }

        Divider()

        Text(
            text = "Mot détecté : $predictedLabel",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Index : $predictedIndex",
            style = MaterialTheme.typography.bodyLarge
        )

        if (error != null) {
            Text(
                text = "Erreur: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AssistChip(
            onClick = {},
            label = { Text("Labels: ${Labels.LIST.joinToString(", ")}") }
        )
    }
}