package fr.mastersid.massil_andrianina.mysmarthome.data.model

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

data class PredictionResult(
    val index: Int,
    val label: String?,
    val confidence: Float,
    val recognized: Boolean
)

class TFLiteKeywordDetector(
    context: Context,
    modelName: String,
    private val labels: List<String>
) {

    companion object {
        private const val DEFAULT_THRESHOLD = 0.6f
    }

    private val interpreter: Interpreter

    private val inputScale: Float
    private val inputZeroPoint: Int

    private val outputScale: Float
    private val outputZeroPoint: Int

    private val inputShape: IntArray
    private val outputShape: IntArray

    init {
        val model = loadModelFile(context, modelName)
        interpreter = Interpreter(model, Interpreter.Options().apply {
            setNumThreads(2)
        })

        val inputTensor = interpreter.getInputTensor(0)
        inputShape = inputTensor.shape() // ex: [1, 124, 128, 1]
        val inQuant = inputTensor.quantizationParams()
        inputScale = inQuant.scale
        inputZeroPoint = inQuant.zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        outputShape = outputTensor.shape() // ex: [1, nb_classes]
        val outQuant = outputTensor.quantizationParams()
        outputScale = outQuant.scale
        outputZeroPoint = outQuant.zeroPoint
    }

    fun predict(
        input: Array<Array<Array<FloatArray>>>,
        threshold: Float = DEFAULT_THRESHOLD
    ): PredictionResult {
        val inputBuffer = convertFloatInputToInt8Buffer(input)

        val numClasses = outputShape[1]
        val outputBuffer = ByteBuffer.allocateDirect(numClasses)
        outputBuffer.order(ByteOrder.nativeOrder())

        // Ici se fait l’inférence
        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()

        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY

        for (i in 0 until numClasses) {
            val q = outputBuffer.get().toInt()
            val dequantized = (q - outputZeroPoint) * outputScale

            if (dequantized > bestScore) {
                bestScore = dequantized
                bestIndex = i
            }
        }

        val bestLabel = labels.getOrNull(bestIndex)

        return if (bestIndex >= 0 && bestScore >= threshold && bestLabel != null) {
            PredictionResult(
                index = bestIndex,
                label = bestLabel,
                confidence = bestScore,
                recognized = true
            )
        } else {
            PredictionResult(
                index = bestIndex,
                label = null,
                confidence = if (bestIndex >= 0) bestScore else 0f,
                recognized = false
            )
        }
    }

    fun predictLabel(
        input: Array<Array<Array<FloatArray>>>,
        threshold: Float = DEFAULT_THRESHOLD
    ): String {
        val result = predict(input, threshold)
        return result.label ?: ""
    }

    private fun convertFloatInputToInt8Buffer(
        input: Array<Array<Array<FloatArray>>>
    ): ByteBuffer {
        val height = inputShape[1]
        val width = inputShape[2]
        val channels = inputShape[3]

        val buffer = ByteBuffer.allocateDirect(height * width * channels)
        buffer.order(ByteOrder.nativeOrder())

        for (h in 0 until height) {
            for (w in 0 until width) {
                for (c in 0 until channels) {
                    val f = input[0][h][w][c]
                    val q = (f / inputScale + inputZeroPoint)
                        .roundToInt()
                        .coerceIn(-128, 127)
                    buffer.put(q.toByte())
                }
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }
}