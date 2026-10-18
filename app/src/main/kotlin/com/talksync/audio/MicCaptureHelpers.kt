package com.talksync.audio

// shared mic setup helpers. all the annoying hardware weirdness can live here.

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

internal const val MIC_TAG = "MicCapture"
internal const val MIC_SAMPLE_RATE = 16_000
internal const val MIC_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

internal data class AudioCaptureConfig(
    val audioSource: Int,
    val channelConfig: Int,
    val channelCount: Int,
    val label: String,
)

internal data class PreparedAudioRecord(
    val record: AudioRecord,
    val captureConfig: AudioCaptureConfig,
    val actualChannelCount: Int,
) {
    val isStereo: Boolean get() = actualChannelCount == 2
}

internal val defaultCaptureConfigs = listOf(
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.MIC,
        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
        channelCount = 2,
        label = "stereo mic",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.MIC,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "mono mic",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "voice recognition mono",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.CAMCORDER,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "camcorder mono",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.DEFAULT,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "default mono",
    ),
)

/**
 * Capture configs for facedown / tabletop mode.
 *
 * UNPROCESSED gives raw mic samples — no Android noise suppression, echo
 * cancellation, or AGC. This is better for multi-speaker at table distance
 * where Android's voice-tuned APM kills too much speech. Deepgram and
 * ElevenLabs handle the noise on their end.
 */
internal val facedownCaptureConfigs = listOf(
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.UNPROCESSED,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "unprocessed mono",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.MIC,
        channelConfig = AudioFormat.CHANNEL_IN_STEREO,
        channelCount = 2,
        label = "stereo mic",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.MIC,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "mono mic",
    ),
    AudioCaptureConfig(
        audioSource = MediaRecorder.AudioSource.DEFAULT,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        channelCount = 1,
        label = "default mono",
    ),
)

internal data class AudioRecordPrepareResult(
    val prepared: PreparedAudioRecord?,
    val errorSummary: String?,
)

internal fun prepareAudioRecord(
    audioManager: AudioManager?,
    configs: List<AudioCaptureConfig> = defaultCaptureConfigs,
): AudioRecordPrepareResult {
    val attempted = mutableListOf<String>()
    val preferredDevice = preferredExternalInputDevice(audioManager)
    for (cfg in configs) {
        val minBuffer = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            cfg.channelConfig,
            MIC_AUDIO_FORMAT,
        )
        if (minBuffer <= 0) {
            attempted += "${cfg.label}: buffer size unavailable ($minBuffer)"
            continue
        }
        val bufferSize = (minBuffer * 4).coerceAtLeast(MIC_SAMPLE_RATE * cfg.channelCount * 2 / 2)
        val record = try {
            AudioRecord(cfg.audioSource, MIC_SAMPLE_RATE, cfg.channelConfig, MIC_AUDIO_FORMAT, bufferSize)
        } catch (t: Throwable) {
            attempted += "${cfg.label}: ${t.message ?: t.javaClass.simpleName}"
            continue
        }
        preferredDevice?.let { device ->
            val ok = runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
            if (!ok) Log.w(MIC_TAG, "Unable to prefer external input device ${device.productName}")
        }
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            val actualChannelCount = when (record.channelCount) {
                2 -> 2
                1 -> 1
                else -> cfg.channelCount
            }
            if (cfg.channelCount == 2 && actualChannelCount != 2) {
                Log.w(MIC_TAG, "Requested stereo capture but device exposed $actualChannelCount channel(s)")
            }
            if (actualChannelCount == 1) {
                Log.w(MIC_TAG, "Falling back to mono AudioRecord capture")
            }
            return AudioRecordPrepareResult(
                prepared = PreparedAudioRecord(
                    record = record,
                    captureConfig = cfg,
                    actualChannelCount = actualChannelCount,
                ),
                errorSummary = null,
            )
        }
        attempted += "${cfg.label}: state=${record.state}"
        try { record.release() } catch (_: Throwable) {}
    }
    return AudioRecordPrepareResult(
        prepared = null,
        errorSummary = attempted.joinToString(separator = "; ", prefix = "AudioRecord init failed. "),
    )
}

/**
 * Returns the best available external input device in priority order:
 *   USB (condenser/presentation mic) > wired headset/lavalier > Bluetooth lapel.
 *
 * Returning null means fall through to the built-in mic.
 */
internal fun preferredExternalInputDevice(audioManager: AudioManager?): AudioDeviceInfo? {
    val manager = audioManager ?: return null
    val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    // USB mic/headset — highest quality, typical for presentation setups
    inputs.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }?.let { return it }
    // Wired 3.5mm headset / lavalier
    inputs.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
    }?.let { return it }
    // Bluetooth lapel / wireless mic
    inputs.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }?.let { return it }
    return null
}

// Keep old name as an alias so nothing else breaks.
@Suppress("unused")
internal fun preferredBluetoothInputDevice(audioManager: AudioManager?): AudioDeviceInfo? =
    preferredExternalInputDevice(audioManager)

internal fun describeInputDevice(device: AudioDeviceInfo): String {
    val typeLabel = when (device.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB mic"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired mic"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line-in mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        else -> "External mic"
    }
    val productName = device.productName?.toString()?.trim().orEmpty()
    return if (productName.isBlank()) typeLabel else "$typeLabel ($productName)"
}

internal data class InputRoute(
    val label: String,
    val bluetoothActive: Boolean,
)

internal fun resolveInputRoute(record: AudioRecord, captureLabel: String): InputRoute {
    val routedDevice = record.routedDevice
    val routeLabel = routedDevice?.let(::describeInputDevice) ?: captureLabel
    val bluetoothInput = routedDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    return InputRoute(label = routeLabel, bluetoothActive = bluetoothInput)
}

/** True if an external mic (USB, wired, or Bluetooth) is currently connected. */
internal fun hasExternalInputDevice(audioManager: AudioManager?): Boolean =
    preferredExternalInputDevice(audioManager) != null

internal fun downmixStereoToMono(stereoChunk: ByteArray): ByteArray {
    val frameCount = stereoChunk.size / 4
    val monoChunk = ByteArray(frameCount * 2)
    for (frame in 0 until frameCount) {
        val base = frame * 4
        val sampleL = ((stereoChunk[base + 1].toInt() shl 8) or
            (stereoChunk[base].toInt() and 0xFF)).toShort().toInt()
        val sampleR = ((stereoChunk[base + 3].toInt() shl 8) or
            (stereoChunk[base + 2].toInt() and 0xFF)).toShort().toInt()
        val mono = ((sampleL + sampleR) / 2).toShort()
        monoChunk[frame * 2] = (mono.toInt() and 0xFF).toByte()
        monoChunk[frame * 2 + 1] = ((mono.toInt() shr 8) and 0xFF).toByte()
    }
    return monoChunk
}

internal fun expandMonoToStereo(monoChunk: ByteArray): ByteArray {
    val frameCount = monoChunk.size / 2
    val stereoChunk = ByteArray(frameCount * 4)
    for (frame in 0 until frameCount) {
        val monoBase = frame * 2
        val stereoBase = frame * 4
        val low = monoChunk[monoBase]
        val high = monoChunk[monoBase + 1]
        stereoChunk[stereoBase] = low
        stereoChunk[stereoBase + 1] = high
        stereoChunk[stereoBase + 2] = low
        stereoChunk[stereoBase + 3] = high
    }
    return stereoChunk
}
