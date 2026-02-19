package fr.mastersid.massil_andrianina.mysmarthome.data.audio


import kotlin.math.*

/**
 * Log-Mel Spectrogram extractor (proche librosa):
 * - sr=16000
 * - n_fft=512
 * - hop=128
 * - n_mels=129
 * - center=true (padding n_fft/2)
 * - power spectrum
 * - mel filterbank (Slaney-like) + norm Slaney
 * - power_to_db(ref=max, top_db=80)
 * Output: [numFrames=124][nMels=129]
 */
class LogMelSpectrogram(
    private val sampleRate: Int = 16000,
    private val nFft: Int = 512,
    private val hopLength: Int = 128,
    private val nMels: Int = 129,
    private val numFrames: Int = 124,
    private val fMin: Double = 0.0,
    private val fMax: Double = sampleRate / 2.0
) {
    private val fft = FFT(nFft)
    private val window = hannWindow(nFft)
    private val melFilterBank = buildMelFilterBank()

    fun extractFromPcmFloat(audio: FloatArray): Array<FloatArray> {
        // 1) force length 1s = 16000
        val targetLen = sampleRate
        val y = FloatArray(targetLen)
        if (audio.size >= targetLen) {
            for (i in 0 until targetLen) y[i] = audio[i]
        } else {
            for (i in audio.indices) y[i] = audio[i]
            // reste = 0
        }

        // 2) center padding like librosa (n_fft/2 on both sides)
        val pad = nFft / 2
        val yPad = FloatArray(y.size + 2 * pad)
        // left pad zeros already
        for (i in y.indices) yPad[i + pad] = y[i]
        // right pad zeros already

        // 3) framing
        val possibleFrames = 1 + (yPad.size - nFft) / hopLength
        val framesToUse = min(numFrames, possibleFrames)

        // 4) compute mel energies for each frame
        val out = Array(numFrames) { FloatArray(nMels) } // pad with zeros if needed

        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val power = DoubleArray(nFft / 2 + 1)

        var globalMax = Double.NEGATIVE_INFINITY

        for (t in 0 until framesToUse) {
            val start = t * hopLength
            // windowed frame -> re/im
            for (i in 0 until nFft) {
                val v = yPad[start + i].toDouble() * window[i]
                re[i] = v
                im[i] = 0.0
            }

            // FFT
            fft.forward(re, im)

            // power spectrum (only 0..nFft/2)
            for (k in 0..(nFft / 2)) {
                val p = re[k] * re[k] + im[k] * im[k]
                power[k] = p
            }

            // mel = filterbank * power
            val melE = DoubleArray(nMels)
            for (m in 0 until nMels) {
                var s = 0.0
                val w = melFilterBank[m]
                for (k in w.indices) {
                    s += w[k] * power[k]
                }
                // avoid 0
                melE[m] = max(s, 1e-10)
                if (melE[m] > globalMax) globalMax = melE[m]
            }

            // store (temp, will convert to dB later)
            for (m in 0 until nMels) out[t][m] = melE[m].toFloat()
        }

        // 5) power_to_db(ref=np.max), top_db=80
        if (globalMax <= 0.0 || globalMax.isInfinite() || globalMax.isNaN()) globalMax = 1e-10
        val ref = globalMax
        val topDb = 80.0

        for (t in 0 until framesToUse) {
            // find max dB of this frame? librosa top_db uses global max in dB, so clamp to (maxDb - topDb)
            // But power_to_db(ref=np.max) means ref is global max of S => maxDb = 0
            // So clamp to [-topDb, 0]
            for (m in 0 until nMels) {
                val p = out[t][m].toDouble()
                val db = 10.0 * log10(p / ref)
                val clamped = max(db, -topDb) // max is 0 when p==ref
                out[t][m] = clamped.toFloat()
            }
        }

        // frames beyond framesToUse are already zeros; but in librosa you padded with constant 0 BEFORE dB.
        // Here they stay 0dB-ish; better to set them to -80dB to mimic "silence".
        for (t in framesToUse until numFrames) {
            for (m in 0 until nMels) out[t][m] = (-80f)
        }

        return out
    }

    private fun hannWindow(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            w[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i / n)
        }
        return w
    }

    /**
     * Mel filterbank proche librosa (slaney-ish + norm slaney)
     */
    private fun buildMelFilterBank(): Array<DoubleArray> {
        val nFreqs = nFft / 2 + 1
        val fftFreqs = DoubleArray(nFreqs) { it.toDouble() * sampleRate / nFft }

        // mel points
        val melMin = hzToMelSlaney(fMin)
        val melMax = hzToMelSlaney(fMax)
        val mels = DoubleArray(nMels + 2)
        for (i in mels.indices) {
            mels[i] = melMin + (melMax - melMin) * i / (nMels + 1).toDouble()
        }
        val hz = DoubleArray(mels.size) { melToHzSlaney(mels[it]) }

        // bin edges in FFT bins
        val bins = IntArray(hz.size) { f ->
            val target = hz[f]
            // nearest FFT bin
            val b = ((nFft + 1) * target / sampleRate).toInt()
            b.coerceIn(0, nFreqs - 1)
        }

        val fb = Array(nMels) { DoubleArray(nFreqs) { 0.0 } }

        for (m in 0 until nMels) {
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]

            if (center == left || right == center) continue

            for (k in left until center) {
                fb[m][k] = (k - left).toDouble() / (center - left).toDouble()
            }
            for (k in center until right) {
                fb[m][k] = (right - k).toDouble() / (right - center).toDouble()
            }
        }

        // Slaney norm: scale each filter by 2/(f_{m+2}-f_m) in Hz domain
        // (approx librosa.filters.mel norm="slaney")
        for (m in 0 until nMels) {
            val fLeft = hz[m]
            val fRight = hz[m + 2]
            val enorm = 2.0 / max(1e-10, (fRight - fLeft))
            for (k in 0 until nFreqs) {
                fb[m][k] *= enorm
            }
        }

        return fb
    }

    /**
     * Slaney mel scale (approx, proche librosa htk=False)
     * - below 1000 Hz: linear
     * - above 1000 Hz: log
     */
    private fun hzToMelSlaney(hz: Double): Double {
        val f = max(0.0, hz)
        val fSp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0

        return if (f < minLogHz) {
            f / fSp
        } else {
            minLogMel + ln(f / minLogHz) / logStep
        }
    }

    private fun melToHzSlaney(mel: Double): Double {
        val fSp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0

        return if (mel < minLogMel) {
            mel * fSp
        } else {
            minLogHz * exp(logStep * (mel - minLogMel))
        }
    }

    /**
     * Simple radix-2 FFT (Cooley–Tukey), inplace on re/im.
     */
    private class FFT(private val n: Int) {
        private val levels: Int
        private val cosTable: DoubleArray
        private val sinTable: DoubleArray

        init {
            require(n > 0 && (n and (n - 1)) == 0) { "nFft must be power of 2" }
            levels = (ln(n.toDouble()) / ln(2.0)).toInt()
            cosTable = DoubleArray(n / 2)
            sinTable = DoubleArray(n / 2)
            for (i in 0 until n / 2) {
                val angle = 2.0 * Math.PI * i / n
                cosTable[i] = cos(angle)
                sinTable[i] = sin(angle)
            }
        }

        fun forward(re: DoubleArray, im: DoubleArray) {
            // bit-reversed addressing permutation
            for (i in 0 until n) {
                val j = reverseBits(i, levels)
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }

            var size = 2
            while (size <= n) {
                val halfsize = size / 2
                val tablestep = n / size
                var i = 0
                while (i < n) {
                    var j = i
                    var k = 0
                    while (j < i + halfsize) {
                        val l = j + halfsize
                        val tpre =  re[l] * cosTable[k] + im[l] * sinTable[k]
                        val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                        re[l] = re[j] - tpre
                        im[l] = im[j] - tpim
                        re[j] += tpre
                        im[j] += tpim
                        j++
                        k += tablestep
                    }
                    i += size
                }
                size *= 2
            }
        }

        private fun reverseBits(x: Int, bits: Int): Int {
            var v = x
            var y = 0
            for (i in 0 until bits) {
                y = (y shl 1) or (v and 1)
                v = v ushr 1
            }
            return y
        }
    }
}