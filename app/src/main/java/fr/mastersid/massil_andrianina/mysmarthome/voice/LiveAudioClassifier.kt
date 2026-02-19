package fr.mastersid.massil_andrianina.mysmarthome.voice

import android.content.Context
import android.util.Log
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.support.audio.TensorAudio
import java.util.concurrent.Executors

class LiveAudioClassifier(context: Context) {

    private val classifier: AudioClassifier
    private val audioTensor: TensorAudio
    private val executor = Executors.newSingleThreadExecutor()

    init {
        val modelPath = "model_pruned_int8.tflite"
        classifier = AudioClassifier.createFromFile(context, modelPath)
        audioTensor = classifier.createInputTensorAudio()
    }

    fun startListening(onResult: (String) -> Unit) {
        val audioRecord = classifier.createAudioRecord()
        audioRecord.startRecording()

        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val buffer = audioTensor.load(audioRecord)
                val output = classifier.classify(audioTensor)

                // Obtenir la classe la plus probable
                val topResult = output[0].categories.maxByOrNull { it.score }
                topResult?.let {
                    if (it.score > 0.6) {
                        Log.d("AudioClassifier", "Mot détecté : ${it.label} (${it.score})")
                        onResult(it.label)
                    }
                }

                Thread.sleep(1000) // fréquence d’écoute
            }
        }
    }

    fun stopListening() {
        executor.shutdownNow()
    }
}
