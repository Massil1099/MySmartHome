package fr.mastersid.massil_andrianina.mysmarthome.viewmodel

import fr.mastersid.massil_andrianina.mysmarthome.Labels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import fr.mastersid.massil_andrianina.mysmarthome.data.model.TFLiteKeywordDetector
import fr.mastersid.massil_andrianina.mysmarthome.voice.AudioRecorder
import fr.mastersid.massil_andrianina.mysmarthome.voice.StftLogFeatureExtractor

import kotlin.concurrent.thread

class AudioCommandViewModel(app: Application) : AndroidViewModel(app) {

    val recognizedLabel = MutableLiveData<String>()
    val isListening = MutableLiveData(false)

    private val recorder = AudioRecorder(16000)
    private val extractor = StftLogFeatureExtractor()
    private val detector = TFLiteKeywordDetector(app.applicationContext)

    fun recordAndPredictOnce() {
        if (isListening.value == true) return
        isListening.value = true
        recognizedLabel.value = "Écoute…"

        thread {
            try {
                val pcm16 = recorder.recordOneSecondPcm16()
                val pcmF = recorder.pcm16ToFloat(pcm16)

                val input = extractor.extract(pcmF)
                val idx = detector.predict(input)
                val label = Labels.LIST.getOrElse(idx) { "unknown($idx)" }

                recognizedLabel.postValue(label)
            } catch (e: Exception) {
                recognizedLabel.postValue("Erreur: ${e.message}")
            } finally {
                isListening.postValue(false)
            }
        }
    }
}