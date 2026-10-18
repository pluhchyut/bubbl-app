package com.talksync.music

// records a short chunk, wraps it like wav, then asks audd what song it probably is.

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Recognized song details returned by AudD.io.
 *
 * The [stableSeed] is a deterministic 64-bit hash of the title and artist —
 * the visual engine uses it so every play of the same song produces the
 * exact same generative artwork.
 */
data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val releaseDate: String?,
    val songLink: String?,
) {
    /** SHA-style 64-bit hash of "title|artist", lowercased and trimmed. */
    val stableSeed: Long
        get() = stableSongSeed(title, artist)

    val displayLine: String
        get() = if (artist.isBlank()) title else "$title — $artist"
}

/**
 * Recognition outcome. The screen surfaces all four states distinctly.
 */
sealed interface RecognitionResult {
    object NoMatch : RecognitionResult
    data class Match(val song: RecognizedSong) : RecognitionResult
    data class Failure(val reason: String) : RecognitionResult
}

/**
 * Thin wrapper around AudD.io's /recognize endpoint.
 *
 * Workflow:
 *   1. Open AudioRecord at 16kHz mono PCM16.
 *   2. Capture ~6 seconds — empirically enough for AudD to fingerprint.
 *   3. Wrap raw PCM bytes in a WAV header (ByteArrayOutputStream).
 *   4. POST as multipart/form-data with `api_token` + `file` + `return=...`.
 *   5. Parse JSON. `result == null` means no match.
 *
 * Mic permission must already have been granted by the caller — the
 * recognizer just inspects the permission and fails fast otherwise.
 */
class SongRecognizer {

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Capture and recognize. Suspends for up to ~30s while AudD responds.
     * Safe to call from the main thread — work is dispatched to IO.
     */
    suspend fun recognize(context: Context): RecognitionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext RecognitionResult.Failure("Microphone permission not granted.")
        }

        val pcm = try {
            captureClip(durationSeconds = CLIP_SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            return@withContext RecognitionResult.Failure(
                "Microphone capture failed: ${t.message ?: t.javaClass.simpleName}"
            )
        }
        if (pcm.isEmpty()) {
            return@withContext RecognitionResult.Failure("No audio captured.")
        }

        val wav = wrapAsWav(pcm, SAMPLE_RATE, channels = 1)

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_token", AudDConfig.effectiveToken)
            .addFormDataPart("return", "apple_music,spotify")
            .addFormDataPart(
                name = "file",
                filename = "clip.wav",
                body = wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(AUDD_URL)
            .post(body)
            .build()

        val raw = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext RecognitionResult.Failure(
                        "AudD HTTP ${response.code}"
                    )
                }
                response.body?.string().orEmpty()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audd request failed", t)
            return@withContext RecognitionResult.Failure(
                "Network error: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        if (raw.isBlank()) {
            return@withContext RecognitionResult.Failure("Empty response.")
        }

        return@withContext parseResponse(raw)
    }

    /**
     * Quick check whether recognition is even worth attempting — peeks at
     * the input level for ~250ms and reports whether anything audible was
     * captured. Returned RMS in 0..1.
     */
    suspend fun peekInputLevel(context: Context): Float = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext 0f
        }
        runCatching {
            val pcm = captureClip(durationSeconds = 0.25f)
            rmsFromPcm16(pcm)
        }.getOrDefault(0f)
    }

    private fun captureClip(durationSeconds: Float): ByteArray {
        val totalBytes = (SAMPLE_RATE * 2 * durationSeconds).toInt() // 16-bit mono
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord min buffer unavailable: $minBuffer")
        }
        val bufferSize = (minBuffer * 4).coerceAtLeast(SAMPLE_RATE * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try { record.release() } catch (_: Throwable) {}
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        return try {
            record.startRecording()
            val out = ByteArrayOutputStream(totalBytes)
            val chunk = ByteArray(4096)
            while (out.size() < totalBytes) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                val take = minOf(read, totalBytes - out.size())
                out.write(chunk, 0, take)
            }
            out.toByteArray()
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
        }
    }

    private fun parseResponse(raw: String): RecognitionResult {
        val json = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return RecognitionResult.Failure("Bad JSON: ${t.message}")
        }
        val status = json.optString("status")
        if (status == "error") {
            val errMsg = json.optJSONObject("error")?.optString("error_message")
                ?: json.optString("error")
            return RecognitionResult.Failure(
                if (errMsg.isNullOrBlank()) "AudD error" else "AudD error: $errMsg"
            )
        }
        val result = json.opt("result")
        if (result == null || result == JSONObject.NULL) {
            return RecognitionResult.NoMatch
        }
        if (result !is JSONObject) {
            return RecognitionResult.NoMatch
        }
        val title = result.optString("title").ifBlank {
            return RecognitionResult.NoMatch
        }
        val artist = result.optString("artist").orEmpty()
        val album = result.optString("album").takeIf { it.isNotBlank() }
        val releaseDate = result.optString("release_date").takeIf { it.isNotBlank() }
        val songLink = result.optString("song_link").takeIf { it.isNotBlank() }

        return RecognitionResult.Match(
            RecognizedSong(
                title = title,
                artist = artist,
                album = album,
                releaseDate = releaseDate,
                songLink = songLink,
            )
        )
    }

    private companion object {
        const val TAG = "SongRecognizer"
        const val SAMPLE_RATE = 16_000
        const val CLIP_SECONDS = 6f
        const val AUDD_URL = "https://api.audd.io/"
    }
}

/* -------------------------------------------------------------------------- */
/* Shared helpers — also used by the visualizer                              */
/* -------------------------------------------------------------------------- */

/**
 * Deterministic 64-bit FNV-1a hash of "title|artist", lowercased + trimmed.
 * Same song → same seed → identical visual style across plays and devices.
 */
fun stableSongSeed(title: String, artist: String): Long {
    val key = (title.trim() + "|" + artist.trim()).lowercase()
    var hash = -3750763034362895579L // FNV offset basis (64-bit)
    val prime = 1099511628211L
    for (ch in key) {
        hash = hash xor ch.code.toLong()
        hash *= prime
    }
    return hash
}

/** 16-bit signed little-endian PCM RMS (output 0..1). */
fun rmsFromPcm16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    var sumSq = 0.0
    var count = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        val n = sample / 32768.0
        sumSq += n * n
        count++
        i += 2
    }
    if (count == 0) return 0f
    return Math.sqrt(sumSq / count).toFloat().coerceIn(0f, 1f)
}

/** Wrap raw 16-bit little-endian mono/stereo PCM in a minimal RIFF WAV. */
fun wrapAsWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
    val bytesPerSample = 2
    val byteRate = sampleRate * channels * bytesPerSample
    val blockAlign = channels * bytesPerSample
    val totalDataLen = pcm.size + 36

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(totalDataLen)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)                          // fmt chunk size
    header.putShort(1)                         // audio format = PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort((bytesPerSample * 8).toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcm.size)

    return header.array() + pcm
}
