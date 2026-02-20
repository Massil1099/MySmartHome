package fr.mastersid.massil_andrianina.mysmarthome.data.model

import android.content.Context
import android.util.Log
import fr.mastersid.massil_andrianina.mysmarthome.Labels
import fr.mastersid.massil_andrianina.mysmarthome.data.audio.LogMelSpectrogram
import org.json.JSONArray

import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteKeywordDetector(context: Context) {

    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context, "model_pruned_int8.tflite")
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
    }

    fun predict(input: Array<Array<Array<FloatArray>>>): Int {
        val output = Array(1) { FloatArray(Labels.LIST.size) }
        interpreter.run(input, output)
        val probs = output[0]
        var best = 0
        for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
        return best
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}