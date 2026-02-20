package fr.mastersid.massil_andrianina.mysmarthome.voice


import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

class StftLogFeatureExtractor(
    private val frameLength: Int = 255,
    private val frameStep: Int = 128,
    private val numFrames: Int = 124,
    private val numBins: Int = 128,   // rfft bins for 255 => floor(255/2)+1 = 128
    private val eps: Double = 1e-6
) {
    /**
     * Input: 1s float PCM (16000)
     * Output: [1][124][128][1]
     */
    fun extract(pcm: FloatArray): Array<Array<Array<FloatArray>>> {
        // Pad/crop 16000
        val x = FloatArray(16000)
        if (pcm.size >= 16000) {
            for (i in 0 until 16000) x[i] = pcm[i]
        } else {
            for (i in pcm.indices) x[i] = pcm[i]
        }

        val fft = DoubleFFT_1D(frameLength.toLong())

        // Tensor input [1][124][128][1]
        val input = Array(1) { Array(numFrames) { Array(numBins) { FloatArray(1) } } }

        // For each frame
        for (t in 0 until numFrames) {
            val start = t * frameStep
            val frame = DoubleArray(frameLength * 2) // full complex packed by realForwardFull
            // copy samples (zero if out of bounds)
            for (i in 0 until frameLength) {
                val idx = start + i
                val v = if (idx in x.indices) x[idx].toDouble() else 0.0
                frame[i] = v
            }

            // FFT (full complex)
            fft.realForwardFull(frame)

            // Magnitude for bins 0..127 from complex (re, im)
            // frame layout: [re0, im0, re1, im1, ...]
            for (k in 0 until numBins) {
                val re = frame[2 * k]
                val im = frame[2 * k + 1]
                val mag = sqrt(re * re + im * im)
                val logMag = ln(mag + eps)
                input[0][t][k][0] = logMag.toFloat()
            }
        }
        return input
    }
}