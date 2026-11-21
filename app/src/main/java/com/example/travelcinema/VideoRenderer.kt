package com.example.travelcinema

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

object VideoRenderer {

    private const val FPS = 24
    private const val FRAME_DURATION_US = 1_000_000L / FPS
    private const val GOLD_COLOR = Color.rgb(212, 175, 55)
    private const val CREAM_COLOR = Color.rgb(250, 243, 228)

    fun renderTripVideoToGallery(
        context: Context,
        tripTitle: String,
        resolution: String,
        stops: List<TripStop>,
        routeMapUri: Uri?,
        bgmUri: Uri?,
        onProgress: (String) -> Unit = {}
    ): Result<String> {
        val (width, height) = if (resolution == "720p") 1280 to 720 else 1920 to 1080
        val bitrate = if (resolution == "720p") 4_500_000 else 8_000_000

        val tempDir = File(context.cacheDir, "render_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempMp4 = File(tempDir, "output.mp4")

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false
        var ptsUs = 0L

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(tempMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()

            fun drainEncoder(endOfStream: Boolean) {
                val enc = encoder ?: return
                val mx = muxer ?: return
                if (endOfStream) {
                    enc.signalEndOfInputStream()
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) error("Muxer already started")
                        trackIndex = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                trackIndex = mx.addTrack(enc.outputFormat)
                                mx.start()
                                muxerStarted = true
                            }
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            mx.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            fun encodeFrame(drawAction: (Canvas, Float) -> Unit, durationSec: Float) {
                val totalFrames = max(1, (durationSec * FPS).toInt())
                for (f in 0 until totalFrames) {
                    val progress = if (totalFrames <= 1) 1f else f.toFloat() / (totalFrames - 1).toFloat()
                    val canvas = inputSurface.lockCanvas(null)
                    try {
                        drawAction(canvas, progress)
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }
                    bufferInfo.presentationTimeUs = ptsUs
                    drainEncoder(false)
                    ptsUs += FRAME_DURATION_US
                }
            }

            val allItems = stops.flatMap { s -> s.items.map { s to it } }
            val firstPhoto = allItems.firstOrNull { !it.second.isVideo }
                ?.let { decodeBitmap(context, Uri.parse(it.second.uriString), maxSide = 1400) }
            val lastPhoto = allItems.lastOrNull { !it.second.isVideo }
                ?.let { decodeBitmap(context, Uri.parse(it.second.uriString), maxSide = 1400) }
                ?: firstPhoto

            val routeSummary = stops.map { it.place }.distinct().joinToString(" → ").ifBlank { tripTitle }
            val dates = stops.map { it.date }.filter { it.isNotBlank() }.sorted()
            val dateRange = when {
                dates.isEmpty() -> ""
                dates.first() == dates.last() -> dates.first()
                else -> "${dates.first()} ~ ${dates.last()}"
            }

            onProgress("오프닝 카드 렌더링 중...")
            encodeFrame(
                drawAction = { canvas, _ ->
                    drawCardFrame(
                        canvas = canvas,
                        width = width,
                        height = height,
                        hero = firstPhoto,
                        kicker = "TRAVEL MEMORY",
                        title = tripTitle,
                        line1 = dateRange,
                        line2 = routeSummary,
                        line3 = "방문지 ${stops.size}곳 · 기록 ${allItems.size}개"
                    )
                },
                durationSec = 3.0f
            )

            val routeMapBitmap = routeMapUri?.let { decodeBitmap(context, it, maxSide = 1600) }
                ?: createRouteMapBitmap(stops, max(640, (width * 0.75f).toInt()), max(400, (height * 0.65f).toInt()))

            onProgress("전체 이동경로 렌더링 중...")
            encodeFrame(
                drawAction = { canvas, p ->
                    val zoom = 1.0f + 0.035f * p
                    drawFramedPhotoScene(
                        canvas = canvas,
                        width = width,
                        height = height,
                        photo = routeMapBitmap,
                        tripTitle = tripTitle,
                        place = "전체 여행 이동경로",
                        subText = routeSummary,
                        counter = "ROUTE",
                        stopBadge = null,
                        progressFrac = 0f,
                        zoomScale = zoom
                    )
                },
                durationSec = 3.5f
            )

            val totalMedia = max(1, allItems.size)
            allItems.forEachIndexed { idx, (stop, item) ->
                val num = idx + 1
                onProgress("장면 인코딩 중 ($num / $totalMedia): ${item.displayName}")
                val mediaUri = Uri.parse(item.uriString)
                val bmp = if (item.isVideo) {
                    extractVideoThumbnail(context, mediaUri) ?: firstPhoto
                } else {
                    decodeBitmap(context, mediaUri, maxSide = 1600)
                }

                val zoomIn = (num % 2 == 1)
                val counterText = String.format("%02d / %02d", num, totalMedia)
                val badgeText = "STOP ${String.format("%02d", stop.order)} / ${String.format("%02d", stops.size)}"
                val progressFrac = stop.order.toFloat() / max(1, stops.size).toFloat()

                encodeFrame(
                    drawAction = { canvas, p ->
                        val scale = if (zoomIn) {
                            1.0f + 0.04f * p
                        } else {
                            1.04f - 0.04f * p
                        }
                        drawFramedPhotoScene(
                            canvas = canvas,
                            width = width,
                            height = height,
                            photo = bmp,
                            tripTitle = tripTitle,
                            place = stop.place,
                            subText = "${stop.date} ${stop.time}" + if (item.isVideo) " · 동영상" else "",
                            counter = counterText,
                            stopBadge = badgeText,
                            progressFrac = progressFrac,
                            zoomScale = scale
                        )
                    },
                    durationSec = if (item.isVideo) 3.6f else 3.0f
                )
            }

            onProgress("엔딩 카드 렌더링 중...")
            encodeFrame(
                drawAction = { canvas, _ ->
                    drawCardFrame(
                        canvas = canvas,
                        width = width,
                        height = height,
                        hero = lastPhoto,
                        kicker = "THE END",
                        title = tripTitle,
                        line1 = dateRange,
                        line2 = "함께한 ${stops.size}곳의 기억을 영상으로 남겼습니다",
                        line3 = "TRAVEL MEMORY ARCHIVE"
                    )
                },
                durationSec = 3.0f
            )

            drainEncoder(true)
            if (muxerStarted) {
                muxer.stop()
            }

            val savedUri = saveFileToMovies(context, tempMp4, tripTitle, resolution)
            val bgmNote = if (bgmUri != null) " (선택된 BGM은 기록 번들에 함께 보관됨)" else ""
            return Result.success("MP4 생성 및 갤러리 저장 완료: $savedUri$bgmNote")
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            tempDir.deleteRecursively()
        }
    }

    private fun drawBackdrop(canvas: Canvas, width: Int, height: Int, src: Bitmap?, dimAlpha: Int) {
        if (src == null) {
            val grad = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.rgb(10, 16, 30), Color.rgb(38, 26, 36),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = grad })
        } else {
            val smallW = max(32, width / 12)
            val smallH = max(18, height / 12)
            val tiny = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val blown = Bitmap.createScaledBitmap(tiny, width, height, true)
            canvas.drawBitmap(blown, 0f, 0f, null)
            tiny.recycle()
            blown.recycle()
        }
        canvas.drawColor(Color.argb(dimAlpha, 5, 7, 14))
    }

    private fun drawFramedPhotoScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        photo: Bitmap?,
        tripTitle: String,
        place: String,
        subText: String,
        counter: String,
        stopBadge: String?,
        progressFrac: Float,
        zoomScale: Float
    ) {
        drawBackdrop(canvas, width, height, photo, dimAlpha = 165)

        val s = height / 1080f
        val margin = width * 0.055f

        if (photo != null) {
            val maxW = width * 0.78f
            val maxH = height * 0.62f
            val fitScale = min(maxW / max(1, photo.width), maxH / max(1, photo.height)) * zoomScale
            val pw = max(2f, photo.width * fitScale)
            val ph = max(2f, photo.height * fitScale)
            val border = max(6f, 9f * s)
            val cy = height * 0.44f
            val left = (width - pw) / 2f
            val top = cy - ph / 2f

            val frameRect = RectF(left - border, top - border, left + pw + border, top + ph + border)
            canvas.drawRoundRect(
                RectF(frameRect.left + 4f, frameRect.top + 8f, frameRect.right + 4f, frameRect.bottom + 8f),
                18f * s, 18f * s,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
            )
            canvas.drawRoundRect(
                frameRect,
                16f * s, 16f * s,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CREAM_COLOR }
            )

            val dstRect = RectF(left, top, left + pw, top + ph)
            canvas.drawBitmap(photo, null, dstRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }

        // 상단 타이틀 & STOP 배지
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREAM_COLOR
            textSize = max(18f, 26f * s)
            isFakeBoldText = true
        }
        canvas.drawRect(
            margin, 48f * s, margin + 6f * s, 48f * s + 28f * s,
            Paint().apply { color = GOLD_COLOR }
        )
        canvas.drawText(tripTitle.uppercase(), margin + 18f * s, 70f * s, titlePaint)

        if (stopBadge != null) {
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GOLD_COLOR
                textSize = max(16f, 24f * s)
                isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(stopBadge, width - margin, 70f * s, badgePaint)
        }

        // 하단 자막 바
        val bottomY = height - 150f * s
        canvas.drawRect(
            margin, bottomY - 38f * s, margin + 7f * s, bottomY + 34f * s,
            Paint().apply { color = GOLD_COLOR }
        )
        val placePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREAM_COLOR
            textSize = max(28f, 52f * s)
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 220, 205)
            textSize = max(18f, 28f * s)
        }
        val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_COLOR
            textSize = max(20f, 34f * s)
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
        }

        canvas.drawText(place, margin + 22f * s, bottomY + 4f * s, placePaint)
        canvas.drawText(subText, margin + 22f * s, bottomY + 40f * s, subPaint)
        canvas.drawText(counter, width - margin, bottomY + 8f * s, counterPaint)

        // 하단 진행 바
        if (progressFrac > 0f) {
            val barY = height - 42f * s
            val barLeft = margin
            val barRight = width - margin
            canvas.drawRoundRect(
                RectF(barLeft, barY, barRight, barY + 5f * s),
                3f, 3f,
                Paint().apply { color = Color.argb(80, 255, 255, 255) }
            )
            canvas.drawRoundRect(
                RectF(barLeft, barY, barLeft + (barRight - barLeft) * progressFrac.coerceIn(0f, 1f), barY + 5f * s),
                3f, 3f,
                Paint().apply { color = GOLD_COLOR }
            )
        }
    }

    private fun drawCardFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        hero: Bitmap?,
        kicker: String,
        title: String,
        line1: String,
        line2: String,
        line3: String
    ) {
        drawBackdrop(canvas, width, height, hero, dimAlpha = 185)
        val s = height / 1080f
        val cx = width / 2f

        val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_COLOR
            textSize = max(18f, 28f * s)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.25f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREAM_COLOR
            textSize = max(36f, 84f * s)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val line1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_COLOR
            textSize = max(20f, 34f * s)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val line2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 220, 205)
            textSize = max(18f, 28f * s)
            textAlign = Paint.Align.CENTER
        }
        val line3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 196, 186)
            textSize = max(16f, 24f * s)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(kicker, cx, height * 0.30f, kickerPaint)
        canvas.drawText(title, cx, height * 0.42f, titlePaint)
        var y = height * 0.52f
        if (line1.isNotBlank()) {
            canvas.drawText(line1, cx, y, line1Paint)
            y += 52f * s
        }
        if (line2.isNotBlank()) {
            canvas.drawText(line2, cx, y, line2Paint)
        }
        if (line3.isNotBlank()) {
            canvas.drawText(line3, cx, height * 0.82f, line3Paint)
        }
    }

    private fun createRouteMapBitmap(stops: List<TripStop>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 34, 52))

        val gridPaint = Paint().apply {
            color = Color.rgb(30, 51, 77)
            strokeWidth = 2f
        }
        for (x in 0 until w step 60) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), gridPaint)
        for (y in 0 until h step 60) canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), gridPaint)

        val valid = stops.filter { it.lat != null && it.lng != null }
        if (valid.isEmpty()) return bmp

        val minLat = valid.minOf { it.lat!! }
        val maxLat = valid.maxOf { it.lat!! }
        val minLng = valid.minOf { it.lng!! }
        val maxLng = valid.maxOf { it.lng!! }
        val latSpan = max(maxLat - minLat, 0.008)
        val lngSpan = max(maxLng - minLng, 0.008)
        val padX = w * 0.14f
        val padY = h * 0.16f

        val pts = valid.mapIndexed { i, s ->
            val nx = (s.lng!! - minLng) / lngSpan
            val ny = (s.lat!! - minLat) / latSpan
            val px = padX + (nx * (w - padX * 2f)).toFloat()
            val py = (h - padY) - (ny * (h - padY * 2f)).toFloat()
            Triple(px, py, "${i + 1}. ${s.place}")
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_COLOR
            strokeWidth = 7f
            style = Paint.Style.STROKE
        }
        for (i in 0 until pts.size - 1) {
            canvas.drawLine(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second, linePaint)
        }

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_COLOR }
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CREAM_COLOR
            textSize = 24f
            isFakeBoldText = true
        }
        pts.forEach { (px, py, lbl) ->
            canvas.drawCircle(px, py, 16f, pinPaint)
            canvas.drawText(lbl, px + 22f, py + 8f, lblPaint)
        }
        return bmp
    }

    private fun decodeBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / sample > maxSide) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val rotation = getExifRotation(context, uri)
            if (rotation == 0) raw else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (rotated != raw) raw.recycle()
                rotated
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getExifRotation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun extractVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun saveFileToMovies(context: Context, srcFile: File, title: String, resolution: String): String {
        val safeTitle = title.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim().ifBlank { "travel_log" }
        val fileName = "${safeTitle}_${resolution}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TravelCinema")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("갤러리 저장 Uri 생성에 실패했습니다.")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(srcFile).use { input -> input.copyTo(out) }
        } ?: error("갤러리 출력 스트림을 열 수 없습니다.")

        return "Movies/TravelCinema/$fileName"
    }
}