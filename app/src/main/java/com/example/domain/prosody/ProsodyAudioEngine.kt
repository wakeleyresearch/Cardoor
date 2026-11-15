package com.example.domain.prosody

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.domain.pipeline.MarkovianKernel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ProsodyPlaybackState(
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val activeSectionId: String? = null,
    val activeSegmentIndex: Int = 0,
    val register: MarkovianKernel.ProsodyRegister = MarkovianKernel.ProsodyRegister.WRITTEN_TO_BE_READ_OUT_LOUD,
    val error: String? = null
)

data class ProsodySegment(
    val sectionId: String,
    val title: String,
    val spokenText: String,
    val ssmlMarkup: String
)

class ProsodyAudioEngine(context: Context) {

    private val _state = MutableStateFlow(ProsodyPlaybackState())
    val state: StateFlow<ProsodyPlaybackState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var currentQueue: List<ProsodySegment> = emptyList()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("seg_")?.toIntOrNull() ?: return
                        val secId = currentQueue.getOrNull(idx)?.sectionId
                        _state.value = _state.value.copy(
                            isPlaying = true,
                            activeSegmentIndex = idx,
                            activeSectionId = secId
                        )
                    }

                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("seg_")?.toIntOrNull() ?: return
                        if (idx >= currentQueue.lastIndex) {
                            _state.value = _state.value.copy(isPlaying = false)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            error = "Audio playback failed on segment $utteranceId."
                        )
                    }
                })
                _state.value = _state.value.copy(isReady = true, error = null)
            } else {
                _state.value = _state.value.copy(
                    isReady = false,
                    error = "Device TTS engine unavailable (status=$status)."
                )
            }
        }
    }

    fun setRegister(register: MarkovianKernel.ProsodyRegister) {
        _state.value = _state.value.copy(register = register)
        tts?.setSpeechRate(register.rate)
        tts?.setPitch(register.pitch)
    }

    fun playSegments(segments: List<ProsodySegment>, startIndex: Int = 0) {
        val engine = tts
        if (engine == null || !_state.value.isReady) {
            _state.value = _state.value.copy(error = "Audio engine is still initializing.")
            return
        }
        if (segments.isEmpty()) return

        currentQueue = segments
        val reg = _state.value.register
        engine.setSpeechRate(reg.rate)
        engine.setPitch(reg.pitch)
        engine.stop()

        val boundedStart = startIndex.coerceIn(0, segments.lastIndex)
        for (i in boundedStart until segments.size) {
            val seg = segments[i]
            val mode = if (i == boundedStart) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            engine.speak(seg.spokenText, mode, params, "seg_$i")
            engine.playSilentUtterance(reg.pauseMs.toLong(), TextToSpeech.QUEUE_ADD, "pause_$i")
        }
        _state.value = _state.value.copy(
            isPlaying = true,
            activeSegmentIndex = boundedStart,
            activeSectionId = segments[boundedStart].sectionId,
            error = null
        )
    }

    fun stop() {
        tts?.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
