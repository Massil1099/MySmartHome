package fr.mastersid.massil_andrianina.mysmarthome.voice


import android.media.*
import kotlin.math.min

class AudioRecorder(
    private val sampleRate: Int = 16000
) {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun recordOneSecondPcm16(): ShortArray {
        val targetSamples = sampleRate // 1s
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, targetSamples * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val out = ShortArray(targetSamples)
        val temp = ShortArray(bufferSize / 2)

        recorder.startRecording()

        var written = 0
        while (written < targetSamples) {
            val read = recorder.read(temp, 0, min(temp.size, targetSamples - written))
            if (read > 0) {
                for (i in 0 until read) out[written + i] = temp[i]
                written += read
            }
        }

        recorder.stop()
        recorder.release()

        return out
    }

    fun pcm16ToFloat(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = (pcm[i] / 32768.0f)
        return out
    }
}