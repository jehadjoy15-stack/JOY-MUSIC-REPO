/**
 * JOY MUSIC Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.joymusic.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.TextPaint
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.media3.datasource.cache.Cache
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.joymusic.music.R
import com.joymusic.music.lyrics.LyricsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

object VideoClipGenerator {
    private const val TAG = "VideoClipGenerator"

    private const val VIDEO_WIDTH = 720
    private const val VIDEO_HEIGHT = 1280
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1
    private const val VIDEO_BITRATE = 3_500_000

    suspend fun generateStoryVideo(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        startMs: Long,
        endMs: Long,
        lyricsEntries: List<LyricsEntry>,
        backgroundStyle: com.joymusic.music.ui.component.LyricsBackgroundStyle = com.joymusic.music.ui.component.LyricsBackgroundStyle.GRADIENT,
        customBackgroundColor: Int? = null,
        customTextColor: Int? = null,
        downloadCache: Cache? = null,
        playerCache: Cache? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(endMs > startMs) { "End time must be greater than start time" }

            onProgress?.invoke(0.05f)

            // 1. Extract source audio and transcode to clean AAC format
            val videoClipsDir = File(context.cacheDir, "video_clips").apply { mkdirs() }
            videoClipsDir.listFiles()?.filter {
                System.currentTimeMillis() - it.lastModified() > 3600_000L
            }?.forEach { it.delete() }

            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(35).trim()
            val sanitizedArtist = artist.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(25).trim()
            val outputFile = File(videoClipsDir, "$sanitizedTitle - $sanitizedArtist (Story).mp4")
            if (outputFile.exists()) outputFile.delete()

            val aacFile = File(videoClipsDir, "temp_aac_${System.currentTimeMillis()}.m4a")

            val sourceFile = AudioTrimmer.getOrDownloadSourceAudio(
                context = context,
                songId = songId,
                downloadCache = downloadCache,
                playerCache = playerCache,
            )

            // Direct AAC transcode for full Android 9+ compatibility
            val isAacReady = AudioTrimmer.trimAndTranscodeToAac(
                sourceFile = sourceFile,
                outputAacFile = aacFile,
                startMs = startMs,
                endMs = endMs,
            )

            val finalAudioFile = if (isAacReady && aacFile.exists() && aacFile.length() > 1000L) {
                aacFile
            } else {
                // Fallback to trimAudio if needed
                val audioTrimResult = AudioTrimmer.trimAudio(
                    context = context,
                    songId = songId,
                    title = title,
                    artist = artist,
                    startMs = startMs,
                    endMs = endMs,
                    downloadCache = downloadCache,
                    playerCache = playerCache,
                )
                audioTrimResult.getOrThrow()
            }

            onProgress?.invoke(0.15f)

            // 2. Prepare Artwork & Palette
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .allowHardware(false)
                .build()
            val rawCoverBitmap = imageLoader.execute(request).image?.toBitmap()
            val coverBitmap = rawCoverBitmap ?: createPlaceholderBitmap()

            val palette = Palette.from(coverBitmap).generate()
            val dominantColor = customBackgroundColor ?: palette.getDarkVibrantColor(
                palette.getDominantColor(Color.rgb(20, 24, 38))
            )
            val accentColor = customTextColor ?: palette.getVibrantColor(
                palette.getLightVibrantColor(Color.rgb(255, 120, 80))
            )

            val audioExtractor = MediaExtractor()
            var audioSourceTrackIndex = -1
            var audioFormat: MediaFormat? = null
            if (finalAudioFile.exists()) {
                try {
                    audioExtractor.setDataSource(finalAudioFile.absolutePath)
                    for (i in 0 until audioExtractor.trackCount) {
                        val format = audioExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioSourceTrackIndex = i
                            audioFormat = format
                            audioExtractor.selectTrack(i)
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error reading source audio track")
                }
            }

            // 5. Configure Video Encoder (Hardware MediaCodec)
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // Initialize EGL on inputSurface for hardware-accelerated rendering with exact timestamps
            val eglRenderer = EglRenderer(inputSurface)

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerAudioTrackIndex = -1
            var isMuxerStarted = false

            val durationMs = endMs - startMs
            val totalFrames = ((durationMs / 1000f) * FRAME_RATE).toInt().coerceAtLeast(1)

            val bufferInfo = MediaCodec.BufferInfo()

            // Pre-create Paints and Bitmaps
            val bgGradientShader = LinearGradient(
                0f, 0f, 0f, VIDEO_HEIGHT.toFloat(),
                intArrayOf(dominantColor, Color.rgb(12, 14, 20), Color.rgb(6, 7, 10)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val bgGradientPaint = Paint().apply {
                shader = bgGradientShader
                isAntiAlias = true
            }

            val blurredCoverBitmap = if (backgroundStyle == com.joymusic.music.ui.component.LyricsBackgroundStyle.BLUR) {
                getBlurredCoverBitmap(coverBitmap, VIDEO_WIDTH, VIDEO_HEIGHT)
            } else null

            val watermarkBgPaint = Paint().apply {
                color = Color.argb(160, 0, 0, 0)
                isAntiAlias = true
            }
            val watermarkTextPaint = TextPaint().apply {
                color = Color.WHITE
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val titlePaint = TextPaint().apply {
                color = Color.WHITE
                textSize = 40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val artistPaint = TextPaint().apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            val activeLyricPaint = TextPaint().apply {
                color = Color.WHITE
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val inactiveLyricPaint = TextPaint().apply {
                color = Color.argb(120, 255, 255, 255)
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            val cardRect = RectF(
                (VIDEO_WIDTH - 380f) / 2f,
                160f,
                (VIDEO_WIDTH + 380f) / 2f,
                540f
            )
            val roundedCover = getRoundedCornerBitmap(coverBitmap, 380, 380, 36f)

            // App logo for the branding badge
            val appLogoBitmap = createBitmap(40, 40).apply {
                val canvas = Canvas(this)
                val drawable = ContextCompat.getDrawable(context, R.drawable.app_logo)
                drawable?.setBounds(0, 0, 40, 40)
                drawable?.draw(canvas)
            }
            val roundedAppLogo = getRoundedCornerBitmap(appLogoBitmap, 40, 40, 10f)

            val lyricLines = lyricsEntries.filter { it.text.isNotBlank() }

            // Reusable canvas and bitmap for frame rendering
            val frameBitmap = createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT)
            val canvas = Canvas(frameBitmap)

            try {
                // 6. Render Video Frames Loop
                for (frameIndex in 0 until totalFrames) {
                    val ptsNs = (frameIndex * 1_000_000_000L) / FRAME_RATE
                    val currentFrameTimeMs = startMs + ((frameIndex * 1000L) / FRAME_RATE)

                    // Draw Background based on selected style
                    when (backgroundStyle) {
                        com.joymusic.music.ui.component.LyricsBackgroundStyle.BLUR -> {
                            if (blurredCoverBitmap != null) {
                                canvas.drawBitmap(blurredCoverBitmap, 0f, 0f, null)
                            } else {
                                canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), bgGradientPaint)
                            }
                            canvas.drawColor(Color.argb(165, 8, 10, 14))
                        }
                        com.joymusic.music.ui.component.LyricsBackgroundStyle.SOLID -> {
                            canvas.drawColor(dominantColor)
                            val darkOverlay = Paint().apply {
                                color = Color.argb(180, 0, 0, 0)
                            }
                            canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), darkOverlay)
                        }
                        com.joymusic.music.ui.component.LyricsBackgroundStyle.GRADIENT -> {
                            canvas.drawRect(0f, 0f, VIDEO_WIDTH.toFloat(), VIDEO_HEIGHT.toFloat(), bgGradientPaint)
                        }
                    }

                    // Draw Watermark Badge (Top Left Pill with actual App Logo)
                    val badgeWidth = 230f
                    val badgeHeight = 56f
                    val badgeLeft = 40f
                    val badgeTop = 60f
                    val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)
                    canvas.drawRoundRect(badgeRect, 28f, 28f, watermarkBgPaint)
                    canvas.drawBitmap(roundedAppLogo, badgeLeft + 12f, badgeTop + 8f, null)
                    canvas.drawText("JOY MUSIC", badgeLeft + 62f, badgeTop + 37f, watermarkTextPaint)

                    // Draw Album Art with subtle ambient pulse
                    val pulse = 1.0f + 0.007f * sin((frameIndex * 0.12f).toDouble()).toFloat()
                    canvas.save()
                    canvas.scale(pulse, pulse, cardRect.centerX(), cardRect.centerY())
                    canvas.drawBitmap(roundedCover, cardRect.left, cardRect.top, null)
                    canvas.restore()

                    // Draw Title & Artist
                    val cleanTitle = if (title.length > 30) title.take(28) + "…" else title
                    val cleanArtist = if (artist.length > 35) artist.take(33) + "…" else artist
                    canvas.drawText(cleanTitle, VIDEO_WIDTH / 2f, 605f, titlePaint)
                    canvas.drawText(cleanArtist, VIDEO_WIDTH / 2f, 650f, artistPaint)

                    // Draw Synced Lyrics or Dynamic Live Visualizer Wave (Apple Music Style with Staggered Push Wave Physics)
                    if (lyricLines.isNotEmpty()) {
                        val firstLineTime = lyricLines[0].time
                        val isIntro = currentFrameTimeMs < firstLineTime
                        val rawActiveIndex = lyricLines.indexOfLast { it.time <= currentFrameTimeMs }
                        val activeIndex = rawActiveIndex.coerceAtLeast(0)
                        val currentLine = lyricLines[activeIndex]
                        val nextLine = lyricLines.getOrNull(activeIndex + 1)

                        val lineStartTime = currentLine.time
                        val lineEndTime = nextLine?.time ?: (lineStartTime + 4000L)
                        val transitionDurationMs = 750f
                        val transitionStartMs = lineEndTime - transitionDurationMs

                        // Kinetic wobble on newly active line (from JOY MUSIC lyrics physics)
                        val timeSinceActiveStart = (currentFrameTimeMs - lineStartTime).coerceAtLeast(0L)
                        val lineWobble = if (!isIntro && timeSinceActiveStart in 0L..750L) {
                            if (timeSinceActiveStart < 125L) timeSinceActiveStart / 125f
                            else (1f - (timeSinceActiveStart - 125L) / 625f).coerceAtLeast(0f)
                        } else 0f

                        val lyricCenterY = 880f
                        val lineSpacing = 78f

                        // Render visible lines with staggered distance-delay wave
                        val range = (activeIndex - 2)..(activeIndex + 3)
                        for (idx in range) {
                            if (idx in lyricLines.indices) {
                                val line = lyricLines[idx]
                                
                                // Distance from incoming target line for stagger delay
                                val staggerDistance = Math.abs(idx - (activeIndex + 1))
                                val staggerDelayMs = (staggerDistance * 22f).coerceAtMost(180f)
                                val lineWindowStart = transitionStartMs + staggerDelayMs
                                val lineWindowDuration = (transitionDurationMs - staggerDelayMs).coerceAtLeast(100f)

                                val scrollProgress = if (nextLine != null && currentFrameTimeMs >= lineWindowStart) {
                                    val rawT = ((currentFrameTimeMs - lineWindowStart) / lineWindowDuration).coerceIn(0f, 1f)
                                    // FastOutSlowIn (0.4, 0.0, 0.2, 1.0) exact cubic bezier easing
                                    evaluateFastOutSlowIn(rawT)
                                } else 0f

                                val lineY = lyricCenterY + (idx - activeIndex - scrollProgress) * lineSpacing
                                val distFromCenter = Math.abs(lineY - lyricCenterY)

                                if (distFromCenter < 245f) {
                                    val focus = (1f - (distFromCenter / (lineSpacing * 1.15f))).coerceIn(0f, 1f)
                                    val smoothFocus = ((1f - cos(focus * Math.PI.toFloat())) / 2f).coerceIn(0f, 1f)

                                    val isCenterActive = !isIntro && (smoothFocus > 0.45f) && (idx == activeIndex)
                                    val bounceBoost = if (isCenterActive) lineWobble * 0.045f else 0f
                                    val scale = 0.88f + (if (isIntro) 0.08f else 0.16f) * smoothFocus + bounceBoost
                                    val alpha = if (isIntro) {
                                        (0.32f + 0.28f * smoothFocus).coerceIn(0f, 1f)
                                    } else {
                                        (0.28f + 0.72f * smoothFocus).coerceIn(0f, 1f)
                                    }

                                    val rawText = line.text

                                    val lyricPaint = TextPaint().apply {
                                        color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
                                        textSize = 33f
                                        typeface = Typeface.create(
                                            Typeface.DEFAULT,
                                            if (isCenterActive) Typeface.BOLD else Typeface.NORMAL
                                        )
                                        isAntiAlias = true
                                        textAlign = Paint.Align.CENTER
                                        // Subtle ambient text drop shadow for depth
                                        val shadowAlpha = if (isCenterActive) (170 * alpha).toInt() else (65 * alpha).toInt()
                                        setShadowLayer(
                                            if (isCenterActive) 14f else 8f,
                                            0f,
                                            if (isCenterActive) 4f else 2f,
                                            Color.argb(shadowAlpha, 0, 0, 0)
                                        )
                                    }

                                    canvas.save()
                                    canvas.scale(scale, scale, VIDEO_WIDTH / 2f, lineY - 8f)
                                    drawWrappedLyricText(
                                        canvas = canvas,
                                        text = rawText,
                                        centerX = VIDEO_WIDTH / 2f,
                                        centerY = lineY,
                                        paint = lyricPaint,
                                        maxWidth = VIDEO_WIDTH - 80f,
                                    )
                                    canvas.restore()
                                }
                            }
                        }
                    } else {
                        // Dynamic organic dancing audio visualizer bars
                        val barCount = 28
                        val barWidth = 13f
                        val barSpacing = 7f
                        val totalWaveWidth = (barCount * barWidth) + ((barCount - 1) * barSpacing)
                        val startX = (VIDEO_WIDTH - totalWaveWidth) / 2f
                        val waveCenterY = 880f

                        val barPaint = Paint().apply {
                            color = accentColor
                            isAntiAlias = true
                        }

                        for (b in 0 until barCount) {
                            val t = (frameIndex * 0.18f)
                            val wave1 = sin((t + b * 0.45f).toDouble()).toFloat()
                            val wave2 = cos((t * 0.7f - b * 0.3f).toDouble()).toFloat()
                            val combined = ((wave1 * 0.6f + wave2 * 0.4f) * 0.5f + 0.5f)
                            val barHeight = (26f + 90f * combined).coerceIn(16f, 120f)
                            val bx = startX + b * (barWidth + barSpacing)
                            val by = waveCenterY - (barHeight / 2f)
                            canvas.drawRoundRect(bx, by, bx + barWidth, by + barHeight, 6.5f, 6.5f, barPaint)
                        }
                    }

                    // Bottom Watermark
                    val bottomWatermarkPaint = TextPaint().apply {
                        color = Color.argb(140, 255, 255, 255)
                        textSize = 22f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("Shared via JOY MUSIC", VIDEO_WIDTH / 2f, 1220f, bottomWatermarkPaint)

                    // Render to EGL input surface with exact presentation timestamp
                    eglRenderer.renderFrame(frameBitmap, ptsNs)

                    // Drain encoder output
                    drainEncoder(
                        encoder = encoder,
                        bufferInfo = bufferInfo,
                        muxer = muxer,
                        isMuxerStarted = isMuxerStarted,
                        videoTrackIndex = videoTrackIndex,
                        audioFormat = audioFormat,
                        endOfStream = false,
                    ) { vIndex, aIndex ->
                        videoTrackIndex = vIndex
                        muxerAudioTrackIndex = aIndex
                        isMuxerStarted = true
                    }

                    if (frameIndex % 15 == 0) {
                        val progress = 0.20f + 0.65f * (frameIndex.toFloat() / totalFrames)
                        onProgress?.invoke(progress)
                    }
                }

                // Signal EOS
                encoder.signalEndOfInputStream()
                drainEncoder(
                    encoder = encoder,
                    bufferInfo = bufferInfo,
                    muxer = muxer,
                    isMuxerStarted = isMuxerStarted,
                    videoTrackIndex = videoTrackIndex,
                    audioFormat = audioFormat,
                    endOfStream = true,
                ) { vIndex, aIndex ->
                    videoTrackIndex = vIndex
                    muxerAudioTrackIndex = aIndex
                    isMuxerStarted = true
                }

                onProgress?.invoke(0.88f)

                // 7. Mux Audio Track into already-started MediaMuxer
                if (isMuxerStarted && muxerAudioTrackIndex >= 0 && audioSourceTrackIndex >= 0) {
                    val audioBuffer = ByteBuffer.allocateDirect(256 * 1024)
                    val audioBufferInfo = MediaCodec.BufferInfo()
                    while (true) {
                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                        if (audioBufferInfo.size < 0) break
                        audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioBufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrackIndex, audioBuffer, audioBufferInfo)
                        audioExtractor.advance()
                    }
                }

                onProgress?.invoke(0.98f)

            } finally {
                try {
                    eglRenderer.release()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error releasing EGL renderer")
                }

                try {
                    audioExtractor.release()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error releasing audio extractor")
                }

                try {
                    encoder.stop()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error stopping encoder")
                }
                encoder.release()
                inputSurface.release()

                if (isMuxerStarted) {
                    try {
                        muxer.stop()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Error stopping muxer")
                    }
                    muxer.release()
                }

                if (aacFile.exists()) {
                    try { aacFile.delete() } catch (_: Exception) {}
                }
            }

            onProgress?.invoke(1.0f)
            outputFile
        }
    }

    private fun transcodeToAac(sourceFile: File, outputAacFile: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false

        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    extractor.selectTrack(i)
                    break
                }
            }

            if (audioTrack < 0 || inputFormat == null) {
                return false
            }

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputAacFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var aacTrackIndex = -1

            val decodeInfo = MediaCodec.BufferInfo()
            val encodeInfo = MediaCodec.BufferInfo()

            var isExtractorEOS = false
            var isDecoderEOS = false
            var isEncoderEOS = false

            while (!isEncoderEOS) {
                // 1. Feed Extractor to Decoder
                if (!isExtractorEOS) {
                    val inIdx = decoder.dequeueInputBuffer(5000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Feed Decoder to Encoder
                if (!isDecoderEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(decodeInfo, 5000L)
                    if (outIdx >= 0) {
                        val pcmBuf = decoder.getOutputBuffer(outIdx)
                        if ((decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isDecoderEOS = true
                        }

                        if (decodeInfo.size > 0 && pcmBuf != null) {
                            val encInIdx = encoder.dequeueInputBuffer(5000L)
                            if (encInIdx >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                if (encInBuf != null) {
                                    encInBuf.clear()
                                    pcmBuf.position(decodeInfo.offset)
                                    pcmBuf.limit(decodeInfo.offset + decodeInfo.size)
                                    encInBuf.put(pcmBuf)

                                    val flags = if (isDecoderEOS) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    encoder.queueInputBuffer(encInIdx, 0, decodeInfo.size, decodeInfo.presentationTimeUs, flags)
                                }
                            }
                        } else if (isDecoderEOS) {
                            val encInIdx = encoder.dequeueInputBuffer(5000L)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                }

                // 3. Drain Encoder to Muxer
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encodeInfo, 5000L)
                    if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!isMuxerStarted) {
                            aacTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }
                    } else if (encOutIdx >= 0) {
                        val aacBuf = encoder.getOutputBuffer(encOutIdx)
                        if (aacBuf != null && encodeInfo.size > 0 && isMuxerStarted) {
                            aacBuf.position(encodeInfo.offset)
                            aacBuf.limit(encodeInfo.offset + encodeInfo.size)
                            muxer.writeSampleData(aacTrackIndex, aacBuf, encodeInfo)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if ((encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEncoderEOS = true
                            break
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error transcoding audio to AAC")
            false
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            decoder?.release()
            try { encoder?.stop() } catch (_: Exception) {}
            encoder?.release()
            if (isMuxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            muxer?.release()
            extractor.release()
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        isMuxerStarted: Boolean,
        videoTrackIndex: Int,
        audioFormat: MediaFormat?,
        endOfStream: Boolean,
        onMuxerStarted: (Int, Int) -> Unit,
    ) {
        var muxerStarted = isMuxerStarted
        var currentVideoTrackIndex = videoTrackIndex
        val timeoutUs = if (endOfStream) 10_000L else 0L

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    val newVideoFormat = encoder.outputFormat
                    currentVideoTrackIndex = muxer.addTrack(newVideoFormat)
                    val currentAudioTrackIndex = if (audioFormat != null) {
                        try {
                            muxer.addTrack(audioFormat)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Could not add audio track directly to muxer")
                            -1
                        }
                    } else -1
                    muxer.start()
                    muxerStarted = true
                    onMuxerStarted(currentVideoTrackIndex, currentAudioTrackIndex)
                }
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted && currentVideoTrackIndex >= 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(currentVideoTrackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, width: Int, height: Int, cornerRadius: Float): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val output = createBitmap(width, height)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun createPlaceholderBitmap(): Bitmap {
        val bmp = createBitmap(380, 380)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(35, 40, 55))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 90f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🎵", 190f, 220f, paint)
        return bmp
    }

    private fun getBlurredCoverBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val smallW = (width / 16).coerceAtLeast(16)
        val smallH = (height / 16).coerceAtLeast(16)
        val scaled = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
        val blurred = fastBlur(scaled, 6)
        return Bitmap.createScaledBitmap(blurred, width, height, true)
    }

    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var rbs: Int
        val routsum = IntArray(3)
        val rinsum = IntArray(3)

        for (curY in 0 until h) {
            rin_and_rout_init(routsum, rinsum)
            rsum = 0
            gsum = 0
            bsum = 0
            for (curI in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(curI, 0))]
                val sir = stack[curI + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rbs = radius + 1 - kotlin.math.abs(curI)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (curI > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (curY == 0) {
                    vmin[curX] = minOf(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer % div]

                routsum[0] += sir2[0]
                routsum[1] += sir2[1]
                routsum[2] += sir2[2]

                rinsum[0] -= sir2[0]
                rinsum[1] -= sir2[1]
                rinsum[2] -= sir2[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rin_and_rout_init(routsum, rinsum)
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curI in -radius..radius) {
                yi = maxOf(0, yp) + curX
                val sir = stack[curI + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = radius + 1 - kotlin.math.abs(curI)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (curI > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }

                if (curI < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (curX == 0) {
                    vmin[curY] = minOf(curY + radius + 1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                val sir2 = stack[stackpointer]

                routsum[0] += sir2[0]
                routsum[1] += sir2[1]
                routsum[2] += sir2[2]

                rinsum[0] -= sir2[0]
                rinsum[1] -= sir2[1]
                rinsum[2] -= sir2[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun rin_and_rout_init(routsum: IntArray, rinsum: IntArray) {
        routsum[0] = 0; routsum[1] = 0; routsum[2] = 0
        rinsum[0] = 0; rinsum[1] = 0; rinsum[2] = 0
    }

    private class EglRenderer(surface: Surface) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program = 0
        private var textureId = 0
        private val vertexBuffer: FloatBuffer

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        init {
            val quadData = floatArrayOf(
                -1f,  1f, 0f, 0f,
                -1f, -1f, 0f, 1f,
                 1f,  1f, 1f, 0f,
                 1f, -1f, 1f, 1f,
            )
            vertexBuffer = ByteBuffer.allocateDirect(quadData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quadData)
                    position(0)
                }

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = if (chosen && numConfigs[0] > 0 && configs[0] != null) {
                configs[0]!!
            } else {
                // Fallback for Android 9 / legacy GPU drivers without EGL_RECORDABLE_ANDROID
                val fallbackAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                )
                EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0)
                configs[0] ?: error("Unable to find suitable EGLConfig")
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun renderFrame(bitmap: Bitmap, ptsNs: Long) {
            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, bitmap.width, bitmap.height)

            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            val samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(samplerHandle, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)

            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    /**
     * FastOutSlowIn cubic bezier easing (0.4, 0.0, 0.2, 1.0) exact evaluator
     */
    private fun evaluateFastOutSlowIn(t: Float): Float {
        val p1x = 0.4f
        val p1y = 0.0f
        val p2x = 0.2f
        val p2y = 1.0f

        var low = 0f
        var high = 1f
        var s = t.coerceIn(0f, 1f)
        for (iter in 0 until 8) {
            val currentX = 3f * (1f - s) * (1f - s) * s * p1x + 3f * (1f - s) * s * s * p2x + s * s * s
            if (Math.abs(currentX - t) < 0.001f) break
            if (currentX < t) low = s else high = s
            s = (low + high) / 2f
        }
        return 3f * (1f - s) * (1f - s) * s * p1y + 3f * (1f - s) * s * s * p2y + s * s * s
    }

    /**
     * Draw text wrapped cleanly across lines when it exceeds maxWidth without cutting with '...' dots
     * and strictly preventing word-in-word collisions or overlapping.
     */
    private fun drawWrappedLyricText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        paint: TextPaint,
        maxWidth: Float,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val textWidth = paint.measureText(trimmed)
        if (textWidth <= maxWidth) {
            canvas.drawText(trimmed, centerX, centerY, paint)
        } else {
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return

            var line1 = ""
            var line2 = ""
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth || line1.isEmpty()) {
                    current = candidate
                } else {
                    if (line1.isEmpty()) {
                        line1 = current
                        current = word
                    } else {
                        current = candidate
                    }
                }
            }
            if (line1.isEmpty()) {
                line1 = current
            } else {
                line2 = current
            }

            if (line2.isEmpty()) {
                canvas.drawText(line1, centerX, centerY, paint)
            } else {
                val subLineSpacing = paint.textSize * 1.34f
                canvas.drawText(line1, centerX, centerY - (subLineSpacing * 0.52f), paint)
                canvas.drawText(line2, centerX, centerY + (subLineSpacing * 0.52f), paint)
            }
        }
    }
}
