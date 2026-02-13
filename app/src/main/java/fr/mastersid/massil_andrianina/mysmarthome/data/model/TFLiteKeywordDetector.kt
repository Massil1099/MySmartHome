package fr.mastersid.massil_andrianina.mysmarthome.data.model

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class TFLiteKeywordDetector(context: Context) {

    private val interpreter: Interpreter

    init {
        try {
            val model = loadModelFile(context, "model_pruned_int8.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, options)
            Log.d("TFLite", "✅ Modèle chargé avec succès.")
        } catch (e: Exception) {
            Log.e("TFLite", "❌ Erreur de chargement du modèle : ${e.message}")
            throw e
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun runInference(input: Array<Array<FloatArray>>): Int {
        val output = Array(1) { FloatArray(NUM_CLASSES) } // Ex : 1x13
        interpreter.run(input, output)
        return output[0].indices.maxByOrNull { output[0][it] } ?: -1
    }

    companion object {
        const val NUM_CLASSES = 13  // ou 12, ou 35 selon ton modèle
    }
}
