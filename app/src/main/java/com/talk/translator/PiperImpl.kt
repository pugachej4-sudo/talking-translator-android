package com.talk.translator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Нейросетевая озвучка Piper через sherpa-onnx. Подключается только если сборка скачала sherpa-onnx. */
class PiperImpl : NeuralTts {
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    override fun load(modelDir: String): Boolean {
        val onnx = File(modelDir).walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: return false
        val dir = onnx.parentFile ?: return false
        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath
        )
        val mc = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu")
        try { tts?.release() } catch (e: Throwable) { }
        tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = mc))
        return true
    }

    override fun speak(text: String) {
        val t = tts ?: return
        val audio = t.generate(text = text, sid = 0, speed = 1.0f)
        play(audio.samples, audio.sampleRate)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        if (samples.isEmpty()) return
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        tr.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track = tr
        tr.play()
    }

    override fun stop() {
        track?.let { try { it.stop() } catch (e: Throwable) { }; try { it.release() } catch (e: Throwable) { } }
        track = null
    }

    override fun release() {
        stop()
        try { tts?.release() } catch (e: Throwable) { }
        tts = null
    }
}
