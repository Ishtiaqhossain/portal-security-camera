package com.meta.portal.security

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.abs

/**
 * Motion detection by luma frame-differencing on the live WebRTC video.
 *
 * Each frame's Y (luma) plane is downsampled to a small grid and compared with
 * the previous grid; if enough cells changed beyond a threshold, motion fired.
 * Mirrors web-client/camera-sim.js so behavior matches the simulator.
 *
 * Attach as a sink on the local video track. Cheap enough to run every frame,
 * but we sample at most a few times per second.
 */
class MotionDetector(
    private val onMotion: (level: Int) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : VideoSink {

    @Volatile var enabled: Boolean = true

    private val gridW = 32
    private val gridH = 24
    private var prev: IntArray? = null
    private var lastSampleMs = 0L
    private var lastAlertMs = 0L

    override fun onFrame(frame: VideoFrame) {
        if (!enabled) return
        val now = nowMs()
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now

        val i420 = frame.buffer.toI420() ?: return
        try {
            val grid = downsampleLuma(i420)
            val prevGrid = prev
            prev = grid
            if (prevGrid == null) return

            var changed = 0
            for (i in grid.indices) {
                if (abs(grid[i] - prevGrid[i]) > PIXEL_DELTA) changed++
            }
            val fraction = changed.toFloat() / grid.size
            if (fraction > MOTION_FRACTION && now - lastAlertMs > ALERT_COOLDOWN_MS) {
                lastAlertMs = now
                val level = (fraction * 100).toInt().coerceIn(0, 100)
                onMotion(level)
            }
        } finally {
            i420.release()
        }
    }

    /** Average the Y plane into a gridW x gridH array, accounting for rotation. */
    private fun downsampleLuma(i420: VideoFrame.I420Buffer): IntArray {
        val w = i420.width
        val h = i420.height
        val strideY = i420.strideY
        val dataY = i420.dataY // direct ByteBuffer
        val out = IntArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val srcY = (gy * h) / gridH
            for (gx in 0 until gridW) {
                val srcX = (gx * w) / gridW
                val v = dataY.get(srcY * strideY + srcX).toInt() and 0xFF
                out[gy * gridW + gx] = v
            }
        }
        return out
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 200L   // ~5 fps sampling
        private const val ALERT_COOLDOWN_MS = 5_000L  // at most one alert / 5s
        private const val PIXEL_DELTA = 25            // per-cell luma change
        private const val MOTION_FRACTION = 0.04f     // >4% of cells changed
    }
}
