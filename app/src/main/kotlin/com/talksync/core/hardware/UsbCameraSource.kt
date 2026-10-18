package com.talksync.core.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.serenegiant.opengl.renderer.RendererHolder
import com.serenegiant.opengl.renderer.RendererHolderCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor.USBException
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * One USB Video Class camera, independent of Camera2 and of USB audio devices.
 *
 * Grant Android CAMERA permission before calling [start]. USB permission is requested here.
 * [onState] runs on the main thread. [onFrame] receives an owned NV21 array on a background
 * thread, at most 15 times/second; frames are dropped while that callback is busy. Its timestamp
 * is monotonic Android arrival time in NANOSECONDS, not a sensor exposure timestamp. Frames are
 * unmirrored, with zero software rotation. Stop any phone CameraX capture before using this.
 *
 * Close when leaving the bench screen/backgrounding the activity. A closed source cannot restart;
 * construct a new instance for retry. The renderer owns and drains the native preview SurfaceTexture
 * even without a visible preview, then releases it after the camera has stopped.
 *
 * API verified against the published com.herohan:UVCAndroid:1.0.13 sources JAR.
 */
class UsbCameraSource(
    context: Context,
    private val onFrame: (ByteArray, Int, Int, Long) -> Unit,
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("BubblUsbCamera").apply { start() }
    private val worker = Handler(cameraThread.looper)
    private val frameExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BubblUsbFrames")
    }
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val frameBusy = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val lastFrameNanos = AtomicLong(0)
    private val lastAcceptedNanos = AtomicLong(0)
    private val deliveredFirstFrame = AtomicBoolean(false)
    private val reportedBadFrame = AtomicBoolean(false)

    // Camera, renderer, monitor and device selection are confined to cameraThread.
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var renderer: RendererHolder? = null
    private var selectedDevice: UsbDevice? = null
    private val declinedDevices = mutableSetOf<String>()

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        worker.post {
            if (closed.get()) return@post
            if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                report("This device does not support USB host cameras.")
                return@post
            }
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                report("Allow camera access, then restart the USB camera test.")
                return@post
            }
            try {
                monitor = USBMonitor(appContext, deviceListener, worker).also { it.register() }
                report("Waiting for a USB webcam. Connect it through a powered USB hub.")
                selectAttachedCamera()
            } catch (e: Exception) {
                reportFailure("Could not monitor USB cameras", e)
            }
        }
    }

    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            if (!closed.get() && selectedDevice == null && isVideoDevice(device)) {
                selectAttachedCamera()
            }
        }

        override fun onDeviceOpen(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean,
        ) {
            if (closed.get() || device.deviceName != selectedDevice?.deviceName || camera != null) return
            openCamera(device, ctrlBlock)
        }

        override fun onDeviceClose(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            if (closed.get() || device.deviceName != selectedDevice?.deviceName) return
            releaseCamera()
            selectedDevice = null
            report("USB webcam disconnected. Reconnect it or restart the test.")
        }

        override fun onDetach(device: UsbDevice) {
            declinedDevices.remove(device.deviceName)
            if (closed.get()) return
            if (device.deviceName == selectedDevice?.deviceName) {
                releaseCamera()
                selectedDevice = null
                report("USB webcam detached. Waiting for a camera.")
            }
            if (selectedDevice == null) selectAttachedCamera()
        }

        override fun onCancel(device: UsbDevice) {
            if (closed.get() || device.deviceName != selectedDevice?.deviceName) return
            declinedDevices.add(device.deviceName)
            selectedDevice = null
            report("USB camera permission was declined or unavailable. Restart the test to try again.")
        }

        override fun onError(device: UsbDevice, e: USBException) {
            if (closed.get() || device.deviceName != selectedDevice?.deviceName) return
            declinedDevices.add(device.deviceName)
            releaseCamera()
            selectedDevice = null
            reportFailure("USB camera access failed; reconnect the webcam", e)
        }
    }

    private fun selectAttachedCamera() {
        if (closed.get() || selectedDevice != null) return
        val usb = appContext.getSystemService(UsbManager::class.java)
        val device = usb.deviceList.values
            .filter { isVideoDevice(it) && it.deviceName !in declinedDevices }
            .sortedWith(compareBy<UsbDevice> { it.vendorId != LOGITECH_VENDOR_ID }.thenBy { it.deviceName })
            .firstOrNull() ?: return
        selectedDevice = device
        report("Requesting USB access to ${deviceLabel(device)}…")
        monitor?.requestPermission(device)
    }

    private fun openCamera(device: UsbDevice, control: USBMonitor.UsbControlBlock) {
        val session = generation.incrementAndGet()
        lastAcceptedNanos.set(0)
        lastFrameNanos.set(0)
        deliveredFirstFrame.set(false)
        reportedBadFrame.set(false)
        report("Opening ${deviceLabel(device)}…")
        try {
            val requested = Size(UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480, 30, listOf(30))
            val opened = UVCCamera(UVCParam(requested, UVCCamera.getRecommendedPlatformQuirks()))
            camera = opened // Own immediately so every failure destroys the native handle.
            check(opened.open(control) == 0) { "The webcam could not be opened (it may be in use)." }
            val negotiated = negotiateSize(opened)
            val width = negotiated.width
            val height = negotiated.height
            val expectedBytes = width * height * 3 / 2
            val label = "${deviceLabel(device)}: ${width}×${height}, ${negotiated.fps} fps"
            opened.setFrameCallback({ buffer ->
                receiveFrame(buffer, width, height, expectedBytes, session, label)
            }, UVCCamera.PIXEL_FORMAT_NV21)

            // An undrained dummy SurfaceTexture eventually blocks ANativeWindow. RendererHolder
            // maintains its own EGL consumer and drains frames even without slave/display surfaces.
            renderer = RendererHolder(width, height, object : RendererHolderCallback {
                override fun onPrimarySurfaceCreate(surface: Surface) {
                    worker.post {
                        if (closed.get() || session != generation.get() || camera !== opened) return@post
                        try {
                            opened.stopPreview()
                            opened.setPreviewDisplay(surface)
                            opened.startPreview()
                            report("$label. Waiting for the first frame…")
                            worker.postDelayed({ checkFrames(session) }, FRAME_TIMEOUT_MS)
                        } catch (e: Exception) {
                            releaseCamera()
                            reportFailure("Could not start USB video", e)
                        }
                    }
                }

                override fun onFrameAvailable() = Unit

                override fun onPrimarySurfaceDestroy() {
                    worker.post {
                        if (!closed.get() && session == generation.get()) {
                            report("USB camera preview surface was lost. Restart the test.")
                        }
                    }
                }
            })
        } catch (e: LinkageError) {
            releaseCamera()
            reportFailure("USB camera native libraries could not load on this device", e)
        } catch (e: Exception) {
            releaseCamera()
            reportFailure("Could not open USB video; reconnect the webcam", e)
        }
    }

    private fun negotiateSize(opened: UVCCamera): Size {
        // Use only camera-advertised formats. Prefer VGA MJPEG, then other modest MJPEG/YUYV
        // modes; never silently allocate/decode an arbitrary webcam's maximum resolution.
        val candidates = opened.supportedSizeList.orEmpty()
            .filter {
                it.width in 2..1920 && it.height in 2..1080 && it.width % 2 == 0 && it.height % 2 == 0 &&
                    it.type in listOf(UVCCamera.UVC_VS_FRAME_MJPEG, UVCCamera.UVC_VS_FRAME_UNCOMPRESSED)
            }
            .sortedWith(compareBy<Size> { it.type != UVCCamera.UVC_VS_FRAME_MJPEG }
                .thenBy { abs(it.width - 640) + abs(it.height - 480) })
        for (candidate in candidates) {
            val rates = (candidate.fpsList.orEmpty() + candidate.fps).filter { it in 1..30 }.distinct()
                .sortedDescending()
            for (fps in rates) {
                try {
                    opened.setPreviewSize(Size(candidate.type, candidate.width, candidate.height, fps, rates))
                    val actual = checkNotNull(opened.previewSize)
                    check(actual.width == candidate.width && actual.height == candidate.height)
                    return actual.clone()
                } catch (_: IllegalArgumentException) {
                    // Another advertised mode can still work when bandwidth negotiation fails.
                }
            }
        }
        error("No usable MJPEG/YUYV mode at up to 1920×1080 and 30 fps was negotiated.")
    }

    private fun receiveFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        expectedBytes: Int,
        session: Long,
        label: String,
    ) {
        if (closed.get() || generation.get() != session) return
        val now = SystemClock.elapsedRealtimeNanos()
        lastFrameNanos.set(now)
        val view = buffer.duplicate().apply { clear() }
        if (view.remaining() != expectedBytes) {
            if (reportedBadFrame.compareAndSet(false, true)) {
                report("USB frame size differs from the negotiated NV21 mode. Restart the camera test.")
            }
            return
        }
        if (now - lastAcceptedNanos.get() < FRAME_INTERVAL_NS || !frameBusy.compareAndSet(false, true)) return
        lastAcceptedNanos.set(now)
        try {
            // Native memory is reused as soon as this callback returns; copy before dispatching.
            val bytes = ByteArray(expectedBytes)
            view.get(bytes)
            frameExecutor.execute {
                try {
                    if (!closed.get() && generation.get() == session) {
                        onFrame(bytes, width, height, now)
                        if (deliveredFirstFrame.compareAndSet(false, true)) report("Streaming $label")
                    }
                } catch (e: Exception) {
                    reportFailure("USB frame processing failed", e)
                } finally {
                    frameBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            frameBusy.set(false) // Normal if close raced this native callback.
        } catch (e: Exception) {
            frameBusy.set(false)
            reportFailure("Could not copy a USB camera frame", e)
        }
    }

    private fun checkFrames(session: Long) {
        if (closed.get() || generation.get() != session) return
        if (SystemClock.elapsedRealtimeNanos() - lastFrameNanos.get() > FRAME_TIMEOUT_MS * 1_000_000) {
            report("No USB video frames received. Check hub power, reconnect the webcam, and restart the test.")
        }
        worker.postDelayed({ checkFrames(session) }, FRAME_TIMEOUT_MS)
    }

    private fun releaseCamera() {
        generation.incrementAndGet()
        val oldCamera = camera
        camera = null
        // Destroy stops native capture before the renderer disposes its Surface and SurfaceTexture.
        runCatching { oldCamera?.destroy(true) }.onFailure { Log.w(TAG, "Camera cleanup failed", it) }
        val oldRenderer = renderer
        renderer = null
        runCatching { oldRenderer?.release() }.onFailure { Log.w(TAG, "Renderer cleanup failed", it) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        frameExecutor.shutdownNow()
        worker.post {
            releaseCamera()
            runCatching { monitor?.destroy() }.onFailure { Log.w(TAG, "USB monitor cleanup failed", it) }
            monitor = null
            selectedDevice = null
            cameraThread.quitSafely()
        }
    }

    private fun report(message: String) {
        main.post { if (!closed.get()) onState(message) }
    }

    private fun reportFailure(message: String, cause: Throwable) {
        Log.w(TAG, message, cause)
        report("$message. ${cause.message.orEmpty().take(160)}")
    }

    private fun deviceLabel(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() } ?: "USB webcam"

    private fun isVideoDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }

    private companion object {
        const val TAG = "UsbCameraSource"
        const val LOGITECH_VENDOR_ID = 0x046d
        const val FRAME_INTERVAL_NS = 1_000_000_000L / 15
        const val FRAME_TIMEOUT_MS = 5_000L
    }
}
