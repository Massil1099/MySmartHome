package fr.mastersid.massil_andrianina.mysmarthome.data.model

import android.content.Context
import android.util.Log
import fr.mastersid.massil_andrianina.mysmarthome.data.audio.LogMelSpectrogram
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteKeywordDetector(context: Context) {

    private val interpreter: Interpreter
    private val logMel = LogMelSpectrogram(
        sampleRate = 16000,
        nFft = 512,
        hopLength = 128,
        nMels = 129,
        numFrames = 124
    )

    init {
        val model = loadModelFile(context, "model_pruned_int8.tflite")
        val options = Interpreter.Options().apply { setNumThreads(2) }
        interpreter = Interpreter(model, options)
        Log.d("TFLite", "✅ Modèle chargé.")
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    /**
     * Inference depuis un buffer audio PCM float [-1,1] (1 seconde)
     */
    fun runFromAudioPcmFloat(pcm: FloatArray): Int {
        // log-mel: [124][129]
        val feat = logMel.extractFromPcmFloat(pcm)

        // model input: [1][124][129][1]
        val input = Array(1) { Array(124) { Array(129) { FloatArray(1) } } }
        for (t in 0 until 124) {
            for (m in 0 until 129) {
                input[0][t][m][0] = feat[t][m]
            }
        }

        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter.run(input, output)

        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        Log.d("TFLite", "🔎 Résultat brut : ${output[0].joinToString(", ")}")
        Log.d("TFLite", "✅ Mot détecté (index) : $predictedIndex")

        return predictedIndex
    }

    /**
     * Ton test JSON (tu peux le garder tel quel).
     * Assure-toi que le JSON est bien (124,129,1) comme sample_logmel_yes.json.
     */
    fun runTestFromJson(context: Context, fileName: String = "sample_logmel_down.json"): Int {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(jsonString)

        val input = Array(1) { Array(124) { Array(129) { FloatArray(1) } } }

        for (i in 0 until jsonArray.length()) {
            val row = jsonArray.getJSONArray(i)
            for (j in 0 until row.length()) {
                // soit [val], soit directement val
                val vAny = row.get(j)
                val value = if (vAny is JSONArray) vAny.getDouble(0) else (vAny as Number).toDouble()
                input[0][i][j][0] = value.toFloat()
            }
        }

        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter.run(input, output)

        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
        Log.d("TFLite", "🔎 Résultat brut : ${output[0].joinToString(", ")}")
        Log.d("TFLite", "✅ Mot détecté (index) : $predictedIndex")

        return predictedIndex
    }

    companion object {
        const val NUM_CLASSES = 13
    }
}