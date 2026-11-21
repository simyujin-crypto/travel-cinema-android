package com.example.travelcinema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object VideoRenderer {

    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val VIDEO_WIDTH = 1280
    private const val VIDEO_HEIGHT = 720
    private const val FRAME_RATE = 30
    private const val BIT_RATE = 4_000_000
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_USEC = 10_000L

    private val SECONDS_PER_PHOTO = 3.0
    private val MAX_SECONDS_PER_VIDEO = 8.0

    data class RenderMediaItem(
        val uriString: String,
        val isVideo: Boolean,
        val stopTitle: String,
        val caption: String
    )

    suspend fun renderTripVideo(
        context: Context,
        tripTitle: String,
        items: List<RenderMediaItem>,
        onProgress: (Float, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("영상으로 만들 미디어가 없습니다."))
        }

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val outFolder = File(moviesDir, "TravelCinema").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val safeTitle = tripTitle.replace(Regex("[^가-힣a-zA-Z0-9_-]"), "_").ifBlank { "TravelRecord" }
        val outFile = File(outFolder, "${safeTitle}_${stamp}.mp4")

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var muxerStarted = false
            var frameIndex = 0L

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                isFakeBoldText = true
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                textSize = 24f
            }
            val barPaint = Paint().apply {
                color = Color.argb(175, 18, 22, 28)
            }

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder?.signalEndOfInputStream()
                }
                val enc = encoder ?: return
                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                throw IllegalStateException("Muxer format changed after start")
                            }
                            val newFormat = enc.outputFormat
                            videoTrackIndex = muxer?.addTrack(newFormat) ?: -1
                            muxer?.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encodedData = enc.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0) {
                                if (!muxerStarted) {
                                    throw IllegalStateException("Muxer not started before writing data")
                                }
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            enc.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                return
                            }
                        }
                    }
                }
            }

            fun drawFrame(bitmap: Bitmap?, header: String, footer: String) {
                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    if (bitmap != null && !bitmap.isRecycled) {
                        val scale = min(
                            VIDEO_WIDTH.toFloat() / bitmap.width.coerceAtLeast(1),
                            VIDEO_HEIGHT.toFloat() / bitmap.height.coerceAtLeast(1)
                        )
                        val drawW = (bitmap.width * scale).toInt()
                        val drawH = (bitmap.height * scale).toInt()
                        val left = (VIDEO_WIDTH - drawW) / 2
                        val top = (VIDEO_HEIGHT - drawH) / 2
                        val dst = Rect(left, top, left + drawW, top + drawH)
                        canvas.drawBitmap(bitmap, null, dst, null)
                    }

                    canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), 72f, barPaint)
                    canvas.drawText(header, 28f, 48f, titlePaint)

                    if (footer.isNotBlank()) {
                        canvas.drawRect(
                            0f,
                            VIDEO_HEIGHT - 68f,
                            VIDEO_WIDTH.toFloat(),
                            VIDEO_HEIGHT.toFloat(),
                            barPaint
                        )
                        canvas.drawText(footer, 28f, VIDEO_HEIGHT - 24f, subPaint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                frameIndex++
                drainEncoder(false)
            }

            items.forEachIndexed { idx, item ->
                val uri = Uri.parse(item.uriString)
                val baseProgress = idx.toFloat() / items.size.coerceAtLeast(1)
                onProgress(baseProgress, "렌더링 중 (${idx + 1}/${items.size}): ${item.stopTitle}")

                if (!item.isVideo) {
                    val bmp = decodeBitmap(context, uri, VIDEO_WIDTH, VIDEO_HEIGHT)
                    val frameCount = (SECONDS_PER_PHOTO * FRAME_RATE).toInt().coerceAtLeast(1)
                    repeat(frameCount) {
                        drawFrame(bmp, "${item.stopTitle} · $tripTitle", item.caption)
                    }
                    bmp?.recycle()
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 3000L
                        val durationSec = (durationMs / 1000.0).coerceIn(1.0, MAX_SECONDS_PER_VIDEO)
                        val sampleCount = (durationSec * 2.0).toInt().coerceAtLeast(2)
                        val framesPerSample = ((durationSec * FRAME_RATE) / sampleCount)
                            .toInt()
                            .coerceAtLeast(1)

                        for (s in 0 until sampleCount) {
                            val timeUs = ((s.toDouble() / sampleCount) * durationSec * 1_000_000.0).toLong()
                            val frameBmp = retriever.getFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                            repeat(framesPerSample) {
                                drawFrame(
                                    frameBmp,
                                    "${item.stopTitle} (동영상) · $tripTitle",
                                    item.caption
                                )
                            }
                            frameBmp?.recycle()
                        }
                    } catch (_: Exception) {
                        val fallbackFrames = (2.0 * FRAME_RATE).toInt()
                        repeat(fallbackFrames) {
                            drawFrame(null, "${item.stopTitle} · $tripTitle", item.caption)
                        }
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            onProgress(0.98f, "영상 마무리 중...")
            drainEncoder(true)

            if (muxerStarted) {
                muxer.stop()
            }
            onProgress(1.0f, "완료: ${outFile.absolutePath}")
            Result.success(outFile)
        } catch (e: Exception) {
            if (outFile.exists()) {
                outFile.delete()
            }
            Result.failure(e)
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, reqW, reqH)
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}