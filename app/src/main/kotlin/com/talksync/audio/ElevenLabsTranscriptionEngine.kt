package com.talksync.audio

// ElevenLabs Scribe STT — high-accuracy batch transcription.
// Called after each Deepgram final to refine the displayed text.

import android.util.Log
import com.talksync.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class ElevenLabsResult(
    val text: String,
    val languageCode: String?,
)

class ElevenLabsTranscriptionEngine {

    companion object {
        private const val TAG = "ElevenLabsSTT"
        private const val API_URL = "https://api.elevenlabs.io/v1/speech-to-text"
        private const val MODEL_ID = "scribe_v1"
        const val PLACEHOLDER_API_KEY = "YOUR_ELEVENLABS_API_KEY_HERE"
        // Don't bother sending clips shorter than this — too noisy for meaningful refinement.
        private const val MIN_PCM_BYTES = 3_200 // 100ms @ 16kHz 16-bit mono
        // Hard cap to avoid huge uploads: 30s of audio
        private const val MAX_PCM_BYTES = 16_000 * 2 * 30
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = BuildConfig.ELEVENLABS_API_KEY.isNotBlank() &&
            BuildConfig.ELEVENLABS_API_KEY != PLACEHOLDER_API_KEY

    /**
     * Sends a raw PCM buffer (16-bit signed, mono, 16kHz) to ElevenLabs Scribe.
     * Returns the refined transcript text and detected language, or null on any failure.
     * [hintLanguage] is an optional BCP-47/ISO-639-1 code to bias recognition.
     */
    suspend fun transcribe(
        pcmBytes: ByteArray,
        sampleRate: Int = MIC_SAMPLE_RATE,
        hintLanguage: String? = null,
    ): ElevenLabsResult? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val trimmed = if (pcmBytes.size > MAX_PCM_BYTES) {
            pcmBytes.copyOf(MAX_PCM_BYTES)
        } else {
            pcmBytes
        }
        if (trimmed.size < MIN_PCM_BYTES) return@withContext null

        try {
            val wavBytes = pcmToWav(trimmed, sampleRate)
            val audioBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", audioBody)
                .addFormDataPart("model_id", MODEL_ID)
                .apply {
                    val langCode = hintLanguage?.take(2)?.lowercase()
                        ?.takeIf { it.isNotBlank() && it != "un" }
                    if (langCode != null) {
                        addFormDataPart("language_code", langCode)
                    }
                }
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .header("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .post(multipart)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from ElevenLabs Scribe")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val text = json.optString("text").trim().takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val lang = json.optString("language_code")
                    .takeIf { it.isNotBlank() && it != "null" }
                ElevenLabsResult(text = text, languageCode = lang)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ElevenLabs Scribe failed: ${t.message}")
            null
        }
    }

    /** Wraps raw PCM (16-bit signed, mono) in a 44-byte WAV header. */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size

        val out = ByteArrayOutputStream(44 + dataSize)
        val w = DataOutputStream(out)

        fun le32(v: Int) = w.write(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(),
        )
        fun le16(v: Int) = w.write(
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array(),
        )

        w.writeBytes("RIFF");  le32(36 + dataSize)
        w.writeBytes("WAVE")
        w.writeBytes("fmt "); le32(16)
        le16(1)                // PCM
        le16(channels.toInt())
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign.toInt())
        le16(bitsPerSample.toInt())
        w.writeBytes("data"); le32(dataSize)
        w.write(pcm)

        return out.toByteArray()
    }
}
